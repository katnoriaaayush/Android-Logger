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
        const val TARGET_PKG  = "com.abc.xyz"
        const val CHANNEL_ID  = "log_capture"
        const val NOTIF_ID    = 1
        const val ACTION_STOP = "com.sqa.logtest.STOP"

        // How often to re-scan /proc for new/dead PIDs of TARGET_PKG (ms)
        const val PID_REFRESH_MS = 15_000L
    }

    private var logcatProcess: java.lang.Process? = null
    private var captureThread: Thread? = null
    private var pidRefreshThread: Thread? = null
    @Volatile private var outputFile: File? = null

    // pid → userId  (kept current by the PID-refresh thread)
    // ConcurrentHashMap so the capture thread can read without locking
    private val pidToUser = ConcurrentHashMap<Int, Int>()

    // ─── lifecycle ────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate uid=${android.os.Process.myUid()}")
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        startPidRefresher()
        startCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatProcess?.destroy()
        captureThread?.interrupt()
        pidRefreshThread?.interrupt()
        Log.i(TAG, "onDestroy")
    }

    // ─── PID resolver ─────────────────────────────────────────────────────────
    //
    // Scans /proc/*/cmdline every PID_REFRESH_MS milliseconds.
    // Android app process names equal their package name, with an optional
    // colon-suffix for multi-process components (e.g. com.abc.xyz:service).
    // /proc/<pid>/status gives us the UID → we compute userId = uid / 100_000.
    //
    // Running as UID 1000 (system) we can read all processes' /proc entries,
    // including those from secondary user profiles.

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
        val found = mutableMapOf<Int, Int>()   // pid → userId

        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            try {
                // cmdline: null-terminated arguments; first = process name
                val raw = File(dir, "cmdline").readBytes()
                val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) raw.size else it }
                val processName = raw.copyOf(end).toString(Charsets.UTF_8)

                // Match "com.abc.xyz" or "com.abc.xyz:anyprocess"
                if (processName != TARGET_PKG && !processName.startsWith("$TARGET_PKG:"))
                    return@forEach

                // Read effective UID from /proc/<pid>/status (line "Uid: real eff saved fs")
                val uid = File(dir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.split("\t")?.getOrNull(1)?.trim()?.toIntOrNull()
                } ?: 0

                found[pid] = uid / 100_000   // 0 = owner, 10 = user10, etc.
            } catch (_: Exception) { /* process died mid-scan */ }
        }

        // Sync into the shared map
        pidToUser.clear()
        pidToUser.putAll(found)

        if (found.isNotEmpty()) {
            Log.d(TAG, "PIDs for $TARGET_PKG: " +
                    found.entries.joinToString { "pid=${it.key} user=${it.value}" })
            updateNotification("Capturing — ${found.size} process(es) found")
        } else {
            Log.d(TAG, "No running processes found for $TARGET_PKG")
            updateNotification("Waiting for $TARGET_PKG to start…")
        }
    }

    // ─── logcat capture ───────────────────────────────────────────────────────

    private fun startCapture() {
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()

        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filteredFile = File(outDir, "${TARGET_PKG}_${ts}_filtered.txt")
        val completeFile = File(outDir, "logcat_${ts}_complete.txt")
        outputFile = filteredFile

        captureThread = Thread({ runCapture(filteredFile, completeFile) }, "logcat-capture")
        captureThread!!.isDaemon = true
        captureThread!!.start()
    }

    private fun runCapture(filteredFile: File, completeFile: File) {
        try {
            // -v uid,threadtime  → date time uid pid tid level tag: message
            // -b all             → all ring buffers
            val proc = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-v", "uid,threadtime", "-b", "all"
            ))
            logcatProcess = proc

            val myUid = android.os.Process.myUid()
            val started = Date()

            fun writeHeader(w: PrintWriter, label: String, extra: String) {
                w.println("# CrossUserLogTest — $label")
                w.println("# Target package : $TARGET_PKG")
                w.println("# Service UID    : $myUid " +
                        if (myUid == 1000) "(system — cross-user PIDs visible)"
                        else "(NOT 1000 — may only see owner-profile PIDs)")
                w.println("# PID refresh    : every ${PID_REFRESH_MS / 1000}s via /proc scan")
                w.println("# Started        : $started")
                w.println(extra)
                w.println("# Format         : date time uid pid tid level tag: message")
                w.println("# ─────────────────────────────────────────────────────────")
                w.flush()
            }

            PrintWriter(filteredFile.bufferedWriter()).use { filtered ->
                PrintWriter(completeFile.bufferedWriter()).use { complete ->

                    writeHeader(filtered, "filtered (${TARGET_PKG} only)",
                        "# Match rule     : pid in {pids of $TARGET_PKG} — multi-process & all user profiles\n" +
                        "# Prefix         : [owner] or [userN] shows source profile")
                    writeHeader(complete, "complete logcat dump",
                        "# Contents       : every log line from all buffers and all processes")

                    var filteredCount = 0
                    var totalCount = 0

                    proc.inputStream.bufferedReader().forEachLine { line ->
                        // ── complete: write every line ──────────────────────
                        complete.println(line)
                        totalCount++

                        // ── filtered: write only lines from TARGET_PKG PIDs ─
                        val pid    = extractPid(line) ?: run {
                            if (totalCount % 100 == 0) complete.flush()
                            return@forEachLine
                        }
                        val userId = pidToUser[pid]   // null → not our process
                        if (userId != null) {
                            val profile = if (userId == 0) "[owner]" else "[user$userId]"
                            filtered.println("$profile $line")
                            filteredCount++
                            if (filteredCount % 10 == 0) filtered.flush()
                        }

                        if (totalCount % 100 == 0) complete.flush()
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Capture thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
        }
    }

    // ─── line parser ──────────────────────────────────────────────────────────
    //
    // logcat -v uid,threadtime format:
    //   MM-DD HH:MM:SS.mmm  <uid>  <pid>  <tid>  <L>  <tag>: <msg>
    //
    // Group 1 = PID (we skip UID — \d+ without capture — then capture PID).

    private val lineRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+(\d+)\s+""")

    private fun extractPid(line: String): Int? =
        lineRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // ─── notification ─────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Log Capture", NotificationManager.IMPORTANCE_LOW)
        )
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
