package com.sqa.logdaemon.session

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.sqa.logdaemon.collector.PackageLogCollector
import com.sqa.logdaemon.config.ConfigParser
import com.sqa.logdaemon.config.DaemonConfig
import com.sqa.logdaemon.writer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the lifecycle of a single collection session: from USB attach
 * (with valid log.sinfo) through to detach. Owns:
 *
 *   - the session folder on the USB drive
 *   - per-package collectors
 *   - the stats collector
 *   - the metadata + summary writers
 *
 * Call start() once on USB attach, stop() once on detach. Instances are
 * single-use; create a new SessionManager for the next session.
 */
class SessionManager(
    private val context: Context,
    private val usbRoot: File
) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val collectors = mutableListOf<PackageLogCollector>()
    private val stats = StatsCollector()

    private var sessionFolder: File? = null
    private var startMillis: Long = 0
    private var summaryWritten = false

    /**
     * Returns true if the session started successfully.
     * Returns false if the config is missing/invalid (caller should stay idle).
     */
    fun start(): Boolean {
        startMillis = System.currentTimeMillis()
        val configFile = File(usbRoot, CONFIG_FILENAME)

        // 1. Parse config
        val parseResult = ConfigParser().parse(configFile)
        val (config, warnings) = when (parseResult) {
            is ConfigParser.ParseResult.Success -> parseResult.config to parseResult.warnings
            is ConfigParser.ParseResult.Failure -> {
                Log.e(TAG, "Config parse failed: ${parseResult.errors.joinToString("; ")}")
                writeErrorFile(parseResult.errors)
                return false
            }
        }

        // 2. Create session folder
        val folderName = SESSION_FOLDER_FORMAT.format(Date(startMillis))
        val folder = File(usbRoot, "$LOGS_DIR/$folderName")
        if (!folder.mkdirs()) {
            Log.e(TAG, "Failed to create session folder: ${folder.absolutePath}")
            return false
        }
        sessionFolder = folder
        Log.i(TAG, "Session folder: ${folder.absolutePath}")

        // 3. Resolve UIDs and prepare per-package status
        val packageStatus = resolvePackageStatus(config)

        // 4. Write _session.meta up front
        try {
            SessionMetaWriter(File(folder, META_FILENAME)).write(
                startMillis = startMillis,
                configPath = configFile.absolutePath,
                config = config,
                configWarnings = warnings,
                packageStatus = packageStatus
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write _session.meta", e)
        }

        // 5. Start a collector per Found package
        var startedAny = false
        packageStatus.forEach { (pkg, status) ->
            if (status !is PackageStatus.Found) return@forEach
            try {
                val rawFile = File(folder, "$pkg.log")
                val tsvFile = File(folder, "$pkg.log.tsv")

                val collector = PackageLogCollector(
                    packageName = pkg,
                    uid = status.uid,
                    minLevel = config.minLevel,
                    rawWriter = RawLogWriter(rawFile),
                    tsvWriter = TsvLogWriter(tsvFile),
                    stats = stats,
                    scope = sessionScope
                )
                collector.start()
                collectors.add(collector)
                startedAny = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start collector for $pkg", e)
            }
        }

        if (!startedAny) {
            Log.w(TAG, "No collectors started (no installed packages from config)")
        }

        Log.i(TAG, "Session started — ${collectors.size} collector(s) running")
        return true
    }

    /**
     * Stops all collectors, flushes writers, and writes _summary.tsv.
     * Safe to call multiple times.
     */
    fun stop() {
        if (sessionFolder == null) return
        val endMillis = System.currentTimeMillis()

        // 1. Stop all collectors
        collectors.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        collectors.clear()

        // 2. Write summary (only once)
        if (!summaryWritten) {
            summaryWritten = true
            try {
                val summaryFile = File(sessionFolder, SUMMARY_FILENAME)
                SummaryWriter(summaryFile).write(startMillis, endMillis, stats)
                Log.i(TAG, "Summary written: ${summaryFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write _summary.tsv", e)
            }
        }

        try { sessionScope.coroutineContext[Job]?.cancel() } catch (_: Exception) {}
        Log.i(TAG, "Session stopped — ${stats.totalEntries()} total entries collected")
    }

    private fun resolvePackageStatus(config: DaemonConfig): Map<String, PackageStatus> {
        val pm = context.packageManager
        // Build a snapshot of currently running processes: packageName -> runtimeUid
        val runningUids = getRunningProcessUids()

        return config.packages.associateWith { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val declaredUid = info.uid

                // Prefer the runtime UID if the process is alive — handles
                // sharedUserId apps (e.g. system UID 1000) where the
                // declared UID may not match the actual filter target.
                val runtimeUid = runningUids[pkg]
                val effectiveUid = runtimeUid ?: declaredUid

                if (runtimeUid != null && runtimeUid != declaredUid) {
                    Log.w(TAG, "[$pkg] declared uid=$declaredUid but running as uid=$runtimeUid " +
                            "(likely sharedUserId) — using runtime uid for logcat filter")
                } else {
                    Log.i(TAG, "[$pkg] resolved uid=$effectiveUid (running=${runtimeUid != null})")
                }

                PackageStatus.Found(effectiveUid)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "[$pkg] not installed")
                PackageStatus.NotInstalled
            } catch (e: Exception) {
                Log.e(TAG, "[$pkg] resolution error", e)
                PackageStatus.Error(e.message ?: "unknown")
            }
        }
    }

    /**
     * Returns a map of packageName -> runtime UID for currently-running
     * processes, by shelling out to `ps -A -o UID,NAME`. This catches
     * processes that don't match their declared ApplicationInfo.uid
     * (e.g. apps with android:sharedUserId="android.uid.system" run as
     * UID 1000 regardless of their assigned app UID).
     */
    private fun getRunningProcessUids(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val process = ProcessBuilder("ps", "-A", "-o", "UID,NAME")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2) return@forEachLine
                    val uidStr = parts[0]
                    val procName = parts.last()
                    // Skip header row, kernel threads
                    val uid = parseUid(uidStr) ?: return@forEachLine
                    // Process name can be "com.your.app" or "com.your.app:remote"
                    val pkg = procName.substringBefore(':')
                    // Only keep entries that look like real package names
                    if (pkg.contains('.')) {
                        result.putIfAbsent(pkg, uid)
                    }
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate running processes; falling back to declared UIDs only", e)
        }
        return result
    }

    private fun parseUid(s: String): Int? {
        // `ps -o UID` can output numeric UID or symbolic name (e.g. "system", "u0_a245")
        s.toIntOrNull()?.let { return it }
        return when {
            s == "system" -> 1000
            s == "root" -> 0
            s == "radio" -> 1001
            s == "bluetooth" -> 1002
            // u0_a245 -> 10245
            s.startsWith("u0_a") -> s.removePrefix("u0_a").toIntOrNull()?.let { 10000 + it }
            else -> null
        }
    }

    private fun writeErrorFile(errors: List<String>) {
        try {
            val errFile = File(usbRoot, ERROR_FILENAME)
            errFile.writeText(buildString {
                appendLine("Log Daemon: failed to start session")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine()
                errors.forEach { appendLine("- $it") }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error file", e)
        }
    }

    companion object {
        private const val TAG = "LogDaemon.Session"
        private const val CONFIG_FILENAME = "log.sinfo"
        private const val LOGS_DIR = "logs"
        private const val META_FILENAME = "_session.meta"
        private const val SUMMARY_FILENAME = "_summary.tsv"
        private const val ERROR_FILENAME = "_error.log"
        private val SESSION_FOLDER_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
