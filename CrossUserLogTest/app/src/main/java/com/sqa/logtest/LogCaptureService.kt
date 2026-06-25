package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LogCaptureService : Service() {

    companion object {
        const val TAG = "LogCaptureService"
        const val TARGET_PKG       = "com.abc.xyz"
        const val CHANNEL_ID       = "log_capture"
        const val NOTIF_ID         = 1
        const val ACTION_STOP       = "com.sqa.logtest.STOP"
        const val EXTRA_FILTER_MODE = "filter_mode"
        const val FILTER_PID        = "pid"     // /proc-based PID tracking only
        const val FILTER_UID        = "uid"     // UID modulo only
        const val FILTER_HYBRID     = "hybrid"  // UID pre-filter → PID verification
        const val PID_REFRESH_MS    = 15_000L
    }

    private var logcatProcess: java.lang.Process? = null
    private var captureThread: Thread? = null
    private var pidRefreshThread: Thread? = null
    @Volatile private var filterMode = FILTER_PID
    @Volatile private var outputFile: File? = null

    private val pidToUser = ConcurrentHashMap<Int, Int>()

    // ─── lifecycle ────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate uid=${android.os.Process.myUid()}")
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        // capture starts in onStartCommand once filterMode is known from the intent
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (captureThread == null) {
            filterMode = intent?.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_PID
            Log.i(TAG, "Starting capture — filterMode=$filterMode")
            if (filterMode == FILTER_PID || filterMode == FILTER_HYBRID) startPidRefresher()
            startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatProcess?.destroy()
        captureThread?.interrupt()
        pidRefreshThread?.interrupt()
        Log.i(TAG, "onDestroy")
    }

    // ─── PID resolver (used only in FILTER_PID mode) ──────────────────────────

    private fun startPidRefresher() {
        pidRefreshThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                refreshPids()
                try { Thread.sleep(PID_REFRESH_MS) }
                catch (e: InterruptedException) { break }
            }
        }, "pid-refresher").also { it.isDaemon = true; it.start() }
    }

    private fun refreshPids() {
        val found = mutableMapOf<Int, Int>()

        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            try {
                val raw = File(dir, "cmdline").readBytes()
                val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) raw.size else it }
                val processName = raw.copyOf(end).toString(Charsets.UTF_8)
                if (processName != TARGET_PKG && !processName.startsWith("$TARGET_PKG:"))
                    return@forEach
                val uid = File(dir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.split("\t")?.getOrNull(1)?.trim()?.toIntOrNull()
                } ?: 0
                found[pid] = uid / 100_000
            } catch (_: Exception) {}
        }

        pidToUser.clear()
        pidToUser.putAll(found)

        if (found.isNotEmpty()) {
            Log.d(TAG, "PIDs: " + found.entries.joinToString { "pid=${it.key} user=${it.value}" })
            updateNotification("[PID] Capturing — ${found.size} process(es)")
        } else {
            updateNotification("[PID] Waiting for $TARGET_PKG…")
        }
    }

    // ─── logcat capture ───────────────────────────────────────────────────────

    private fun startCapture() {
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filteredFile = File(outDir, "${TARGET_PKG}_${ts}_${filterMode}_filtered.txt")
        val completeFile = File(outDir, "logcat_${ts}_complete.txt")
        outputFile = filteredFile
        captureThread = Thread({ runCapture(filteredFile, completeFile) }, "logcat-capture")
        captureThread!!.isDaemon = true
        captureThread!!.start()
    }

    private fun runCapture(filteredFile: File, completeFile: File) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "uid,threadtime", "-b", "all"))
            logcatProcess = proc

            val myUid = android.os.Process.myUid()
            val started = Date()

            // Resolved once for both modes
            val targetAppId: Int = try {
                packageManager.getPackageUid(TARGET_PKG, 0) % 100_000
            } catch (_: Exception) { -1 }

            fun writeHeader(w: PrintWriter, label: String, extra: String) {
                w.println("# CrossUserLogTest — $label")
                w.println("# Target package : $TARGET_PKG  (appId=$targetAppId)")
                w.println("# Filter mode    : $filterMode")
                w.println("# Service UID    : $myUid " +
                        if (myUid == 1000) "(system)" else "(NOT 1000)")
                w.println("# Started        : $started")
                w.println(extra)
                w.println("# Format         : date time uid pid tid level tag: message")
                w.println("# ─────────────────────────────────────────────────────────")
                w.flush()
            }

            PrintWriter(filteredFile.bufferedWriter()).use { filtered ->
                PrintWriter(completeFile.bufferedWriter()).use { complete ->

                    val filterDesc = when (filterMode) {
                        FILTER_PID    -> "user-0: pid in pidToUser (/proc scan every ${PID_REFRESH_MS/1000}s)" +
                                         " | secondary users: uid%100_000==appId"
                        FILTER_UID    -> "uid % 100_000 == $targetAppId (all users, no PID check)"
                        FILTER_HYBRID -> "step1: uid%100_000==$targetAppId  step2 (user-0): pid in pidToUser"
                        else          -> filterMode
                    }
                    writeHeader(filtered, "filtered ($filterMode mode)", "# Match rule     : $filterDesc")
                    writeHeader(complete, "complete logcat dump", "# Contents       : every log line")

                    var filteredCount = 0
                    var totalCount = 0

                    proc.inputStream.bufferedReader().forEachLine { line ->
                        complete.println(line)
                        totalCount++
                        if (totalCount % 100 == 0) complete.flush()

                        val uid = extractUid(line) ?: return@forEachLine

                        when (filterMode) {
                            FILTER_PID -> {
                                val userId = uid / 100_000
                                if (userId == 0) {
                                    // Owner profile: /proc scan works — use precise PID map
                                    val pid = extractPid(line) ?: return@forEachLine
                                    if (pidToUser[pid] == null) return@forEachLine
                                    filtered.println("[owner] $line")
                                } else {
                                    // Secondary user: SELinux blocks /proc reads from platform_app
                                    // domain even at UID 1000. Fall back to UID modulo — secondary-
                                    // user system services use UIDs like 1001000, 1001001 which
                                    // never match a regular app's appId.
                                    if (uid % 100_000 != targetAppId) return@forEachLine
                                    filtered.println("[user$userId] $line")
                                }
                                filteredCount++
                                if (filteredCount % 10 == 0) filtered.flush()
                            }
                            FILTER_UID -> {
                                if (uid % 100_000 != targetAppId) return@forEachLine
                                val userId = uid / 100_000
                                val profile = if (userId == 0) "[owner]" else "[user$userId]"
                                filtered.println("$profile $line")
                                filteredCount++
                                if (filteredCount % 10 == 0) filtered.flush()
                            }
                            FILTER_HYBRID -> {
                                // Step 1: UID pre-filter — fast broad net, cross-user aware
                                if (uid % 100_000 != targetAppId) return@forEachLine
                                val userId = uid / 100_000

                                if (userId == 0) {
                                    // Step 2: PID verification for user-0 eliminates shared-UID
                                    // system processes. If the map is still empty (first /proc scan
                                    // hasn't completed yet) we accept on UID alone to avoid
                                    // dropping early boot logs.
                                    val pid = extractPid(line) ?: return@forEachLine
                                    if (pidToUser.isNotEmpty() && pidToUser[pid] == null) return@forEachLine
                                }
                                // Secondary users: UID is sufficient — their system services
                                // (UID 1001000, 1001001 …) never share an appId with user apps.

                                val profile = if (userId == 0) "[owner]" else "[user$userId]"
                                filtered.println("$profile $line")
                                filteredCount++
                                if (filteredCount % 10 == 0) filtered.flush()
                            }
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Capture thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
        }
    }

    // ─── line parsers ─────────────────────────────────────────────────────────
    //
    // logcat -v uid,threadtime:  MM-DD HH:MM:SS.mmm  <uid>  <pid>  <tid>  L  tag: msg

    private val pidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+(\d+)\s+""")
    private val uidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+""")

    private fun extractPid(line: String): Int? =
        pidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractUid(line: String): Int? =
        uidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // ─── notification ─────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Log Capture", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrossUserLog: $TARGET_PKG")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))

    fun getOutputFilePath(): String = outputFile?.absolutePath ?: "not started"
}
