package com.sqa.logdaemon

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone logcat capture utility.
 *
 * Usage:
 *   val capture = LogCapture()
 *   capture.start(listOf("com.example.app", "com.other.pkg"))
 *   // ...later
 *   capture.stop()
 *   val file = capture.outputFile   // File in Downloads/
 *
 * Requires in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.READ_LOGS" />
 *   <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
 *
 * For non-privileged apps on Android 11+, MANAGE_EXTERNAL_STORAGE (or
 * MediaStore) is needed to write to Downloads. As a priv-app this works
 * with WRITE_EXTERNAL_STORAGE alone.
 */
class LogCapture {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile private var _outputFile: File? = null

    /** The file currently being written, or null if not started. */
    val outputFile: File? get() = _outputFile

    /** True while a capture is actively running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Start capturing logcat lines for [packages] into a file in Downloads.
     *
     * @param packages  Package names to filter (e.g. "com.example.app").
     * @param fileName  Output file name. Defaults to "logcapture_<timestamp>.log".
     */
    fun start(
        packages: List<String>,
        fileName: String = "logcapture_${timestamp()}.log"
    ) {
        require(packages.isNotEmpty()) { "packages must not be empty" }
        stop()

        val outFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        _outputFile = outFile

        job = scope.launch {
            Log.i(TAG, "Starting capture → ${outFile.absolutePath}")
            runCapture(packages.toList(), outFile)
            Log.i(TAG, "Capture ended")
        }
    }

    /** Stop an active capture. Safe to call even if not running. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Cancel and release internal resources. Call when you are done with
     * this instance entirely (e.g. in onDestroy). After release(), do not
     * call start() again — create a new instance instead.
     */
    fun release() {
        stop()
        scope.cancel()
    }

    // ── capture logic ─────────────────────────────────────────────────────────

    private suspend fun runCapture(packages: List<String>, outFile: File) {
        val pidMap = mutableMapOf<Int, String>()   // pid -> package name
        rescanPids(packages, pidMap)

        val proc = ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "*:V")
            .redirectErrorStream(false)
            .start()

        try {
            outFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("# LogCapture started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                writer.write("# Packages: ${packages.joinToString()}\n\n")
                writer.flush()

                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                var lastRescan = System.currentTimeMillis()
                var lineCount = 0L

                while (currentCoroutineContext().isActive) {
                    val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break

                    val now = System.currentTimeMillis()
                    if (now - lastRescan >= PID_RESCAN_MS) {
                        rescanPids(packages, pidMap)
                        lastRescan = now
                    }

                    val pid = extractPid(line) ?: continue
                    if (pid !in pidMap) continue

                    writer.write(line)
                    writer.newLine()
                    lineCount++

                    // Flush every 64 lines to balance throughput and durability
                    if (lineCount and 0x3F == 0L) writer.flush()
                }

                writer.flush()
                Log.i(TAG, "Wrote $lineCount lines to ${outFile.name}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
        } finally {
            proc.destroy()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Scan /proc to build a pid→packageName map.
     * Handles multi-process apps ("com.pkg:subproc") by stripping the suffix.
     */
    private fun rescanPids(packages: List<String>, pidMap: MutableMap<Int, String>) {
        pidMap.clear()
        File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.forEach { dir ->
                val pid = dir.name.toInt()
                try {
                    val bytes = File(dir, "cmdline").readBytes()
                    val nullIdx = bytes.indexOfFirst { it == 0.toByte() }
                    val cmdline = (if (nullIdx >= 0) bytes.copyOf(nullIdx) else bytes)
                        .toString(Charsets.UTF_8)
                    val pkgName = cmdline.split(':').first().trim()
                    if (pkgName in packages) pidMap[pid] = pkgName
                } catch (_: Exception) {}
            }
    }

    /**
     * Extract the PID field from a logcat threadtime-format line:
     *   MM-DD HH:MM:SS.mmm  PID  TID  LEVEL  TAG: message
     */
    private fun extractPid(line: String): Int? {
        val parts = line.trimStart().split(Regex("\\s+"))
        return if (parts.size >= 3) parts[2].toIntOrNull() else null
    }

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG             = "LogCapture"
        private const val PID_RESCAN_MS   = 2_000L
    }
}
