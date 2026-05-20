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
 * Collects logcat output for a single package across all Android user profiles.
 *
 * Filters by PID, not UID, because apps sharing the system UID (1000) cannot be
 * distinguished from other system services by UID alone.
 *
 * On a multi-user device each active user profile runs its own instance of the
 * package as a separate process (same package name, same UID, different PID).
 * resolveAllPids() finds every such process so that a single logcat command
 * covers owner + all user profiles simultaneously.
 *
 * Auto-recovers when all instances die: the outer loop re-polls for PIDs and
 * re-attaches logcat once any instance restarts.
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
            val pids = waitForAnyPids() ?: break
            Log.i(TAG, "[$packageName] attaching to ${pids.size} pid(s): $pids")

            val (read, parsed) = attachToPids(logcatPath, pids)
            totalRead += read
            totalParsed += parsed

            if (!running) break
            Log.i(TAG, "[$packageName] all pids gone, watching for restart")
        }

        Log.i(TAG, "[$packageName] collector stopped — total: read=$totalRead parsed=$totalParsed")
    }

    /**
     * Polls until at least one instance of the package is running (owner or
     * any user profile). Returns all PIDs found in that poll, or null if
     * stop() was called before any process appeared.
     */
    private suspend fun waitForAnyPids(): List<Int>? {
        var attempt = 0
        while (running) {
            val pids = resolveAllPids(packageName)
            if (pids.isNotEmpty()) return pids
            attempt++
            if (attempt == 1 || attempt % 20 == 0) {
                Log.d(TAG, "[$packageName] waiting for process to start (attempt $attempt)")
            }
            delay(PID_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Returns the PIDs of every running process whose cmdline matches the
     * given package name (one entry per active Android user profile).
     *
     * Process names in /proc/<pid>/cmdline look like:
     *   "com.your.app"         (main process)
     *   "com.your.app:remote"  (private process)
     * Only the main process (no colon suffix) is matched.
     */
    private fun resolveAllPids(pkg: String): List<Int> {
        val result = mutableListOf<Int>()
        val dirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            ?: return result
        for (dir in dirs) {
            try {
                val cmdline = File(dir, "cmdline").readText().trimEnd('\u0000')
                if (cmdline.substringBefore(':') == pkg) {
                    result.add(dir.name.toInt())
                }
            } catch (_: Exception) {
                // process may have died between listing and reading
            }
        }
        return result
    }

    /**
     * Runs logcat filtered by all supplied PIDs and pumps lines into the writers.
     * One --pid flag per PID covers owner + every user-profile instance in a single
     * logcat invocation. Returns when logcat exits (all processes died or stop()
     * was called).
     */
    private fun attachToPids(logcatPath: String, pids: List<Int>): Pair<Long, Long> {
        val command = buildList {
            add(logcatPath)
            add("-v"); add("threadtime")
            pids.forEach { add("--pid=$it") }
            add("*:$minLevel")
        }

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
                    Log.d(TAG, "[$packageName] progress: read=$read parsed=$parsed rejected=$rejected")
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "[$packageName] attach failed", e)
        } finally {
            try { process?.destroy() } catch (_: Exception) {}
            process = null
        }
        Log.i(TAG, "[$packageName] detached (read=$read parsed=$parsed rejected=$rejected)")
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
