package com.sqa.logdaemon.collector

import android.util.Log
import com.sqa.logdaemon.writer.LogWriter
import com.sqa.logdaemon.writer.StatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Collects logcat output for a single package.
 *
 * Filters by PID, not UID, because apps sharing the system UID (1000)
 * cannot be distinguished from other system services by UID alone.
 *
 * Auto-recovers if the target app crashes or restarts: detects PID change
 * and re-attaches logcat to the new PID without dropping the session.
 */
class PackageLogCollector(
    val packageName: String,
    private val uid: Int,
    private val minLevel: Char,
    private val rawWriter: LogWriter,
    private val tsvWriter: LogWriter,
    private val stats: StatsCollector,
    private val scope: CoroutineScope
) {
    private var process: Process? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    /**
     * Outer loop: resolves the current PID for the package, runs logcat
     * against that PID, and restarts the inner attach if the process dies
     * or restarts during the session.
     */
    private suspend fun runLoop() {
        val logcatPath = findLogcatBinary()
        if (logcatPath == null) {
            Log.e(TAG, "[$packageName] FATAL: could not find logcat binary")
            return
        }
        Log.i(TAG, "[$packageName] using logcat at: $logcatPath (uid=$uid)")

        var totalRead = 0L
        var totalParsed = 0L

        while (running) {
            val pid = waitForPid() ?: break
            Log.i(TAG, "[$packageName] attaching to pid=$pid")

            val (read, parsed) = attachToPid(logcatPath, pid)
            totalRead += read
            totalParsed += parsed

            if (!running) break
            Log.i(TAG, "[$packageName] pid=$pid disappeared — watching for restart")
        }

        Log.i(TAG, "[$packageName] collector stopped — total: read=$totalRead parsed=$totalParsed")
    }

    /**
     * Polls every PID_POLL_INTERVAL_MS for the target package's PID.
     * Returns the PID when found, or null if running was set to false.
     */
    private suspend fun waitForPid(): Int? {
        var attempt = 0
        while (running) {
            val pid = resolvePid(packageName)
            if (pid != null) return pid
            attempt++
            if (attempt == 1 || attempt % 20 == 0) {
                Log.d(TAG, "[$packageName] waiting for process to start (attempt $attempt)")
            }
            delay(PID_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Resolves the PID of the given package by reading /proc.
     *
     * Walks /proc/<pid>/cmdline for each numeric directory, matches against
     * the target package name.
     *
     * Process names look like:
     *   - "com.your.app"               (main process)
     *   - "com.your.app:remote"        (private process)
     * We accept the main one (no colon).
     */
    private fun resolvePid(pkg: String): Int? {
        val procDir = File("/proc")
        val pids = procDir.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return null
        for (dir in pids) {
            try {
                val cmdline = File(dir, "cmdline").readText().trimEnd('\u0000')
                val procName = cmdline.substringBefore(':')
                if (procName == pkg) {
                    return dir.name.toInt()
                }
            } catch (_: Exception) {
                // Process may have died between listing and reading
            }
        }
        return null
    }

    /**
     * Runs logcat filtered by PID and pumps lines into the writers.
     * Returns when the logcat process exits (target process died or stop()
     * was called).
     */
    private fun attachToPid(logcatPath: String, pid: Int): Pair<Long, Long> {
        val command = listOf(
            logcatPath,
            "-v", "threadtime",
            "--pid=$pid",
            "*:$minLevel"
        )

        var read = 0L
        var parsed = 0L
        var rejected = 0L

        try {
            Log.i(TAG, "[$packageName] command: ${command.joinToString(" ")}")
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            process = proc

            // Quick liveness check
            Thread.sleep(200)
            try {
                val exit = proc.exitValue()
                val out = proc.inputStream.bufferedReader().readText()
                Log.e(TAG, "[$packageName] logcat exited immediately code=$exit output:\n$out")
                return read to parsed
            } catch (_: IllegalThreadStateException) { /* still running */ }

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            while (running) {
                val line = reader.readLine() ?: break
                read++
                if (read <= 3) Log.i(TAG, "[$packageName] RAW #$read: $line")

                val entry = LogcatLineParser.parse(line)
                if (entry == null) {
                    rejected++
                    if (rejected <= 3) Log.w(TAG, "[$packageName] PARSE REJECT: $line")
                    continue
                }
                parsed++

                try {
                    rawWriter.write(entry)
                    tsvWriter.write(entry)
                    stats.record(packageName, entry)
                } catch (e: Exception) {
                    Log.e(TAG, "[$packageName] write failed (USB pulled?)", e)
                    running = false
                    break
                }

                if (parsed % 100L == 0L) {
                    Log.d(TAG, "[$packageName] progress: read=$read parsed=$parsed rejected=$rejected (pid=$pid)")
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "[$packageName] attach failed", e)
        } finally {
            try { process?.destroy() } catch (_: Exception) {}
            process = null
        }
        Log.i(TAG, "[$packageName] detached from pid=$pid (read=$read parsed=$parsed rejected=$rejected)")
        return read to parsed
    }

    fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) {}
        try { rawWriter.flush() } catch (_: Exception) {}
        try { tsvWriter.flush() } catch (_: Exception) {}
    }

    fun close() {
        stop()
        try { rawWriter.close() } catch (_: Exception) {}
        try { tsvWriter.close() } catch (_: Exception) {}
        try { job?.cancel() } catch (_: Exception) {}
    }

    private fun findLogcatBinary(): String? {
        val candidates = listOf("/system/bin/logcat", "/system/xbin/logcat", "/vendor/bin/logcat")
        return candidates.firstOrNull { File(it).canExecute() }
    }

    companion object {
        private const val TAG = "LogDaemon.Collector"
        private const val PID_POLL_INTERVAL_MS = 500L
    }
}
