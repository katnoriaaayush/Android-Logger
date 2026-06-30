package com.sqa.logtest

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * LoggerService — the user-0 log PRODUCER for the USB-sync pathway.
 *
 * Capture method: the proven UID+PID hybrid filter (same as LogCaptureService
 * FILTER_HYBRID) —
 *   • UID pre-filter:  uid % 100_000 == targetAppId  (cross-user aware)
 *   • PID verify:      pid ∈ amPidSet                (kills shared-UID noise)
 *     amPidSet = ActivityManager.getRunningAppProcesses() (user 0 + cross-user)
 *                merged with `ps -A` (secondary-user processes AM omits),
 *                refreshed every PID_REFRESH_MS.
 *
 * Output: rotated, completed segments in user-0 INTERNAL storage
 *   dataDir/logs/current.log   ← active, NEVER exposed by the provider
 *   dataDir/logs/log-<ms>.log  ← completed, exposed to UsbSyncService via LogProvider
 *
 * singleUser + gated on user 0 so the per-user install never spawns a second
 * producer in a secondary profile.
 */
class LoggerService : Service() {

    companion object {
        const val TAG            = "LoggerService"
        const val TARGET_PKG     = "com.abc.xyz"
        const val CHANNEL_ID     = "logger"
        const val NOTIF_ID       = 4
        const val ACTION_STOP    = "com.sqa.logtest.LOGGER_STOP"
        const val ROTATE_BYTES   = 256L * 1024      // rotate a completed segment at 256 KB
        const val ROTATE_MS      = 15_000L          // …or after 15 s of new data
        const val PID_REFRESH_MS = 3_000L

        /** Set while the user-0 instance is alive; used by LogProvider.call("rotate"). */
        @Volatile var instance: LoggerService? = null

        /** Completed/active logs live here, in user-0 internal storage. */
        fun logsDir(ctx: Context): File = File(ctx.dataDir, "logs")
    }

    private var captureThread: Thread? = null
    private var pidThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var amPidSet: Set<Int> = emptySet()
    @Volatile private var targetAppId = -1
    @Volatile private var logcatProc: java.lang.Process? = null

    private val rotateLock = Any()
    private var current: PrintWriter? = null
    private var currentFile: File? = null
    private var currentBytes = 0L
    private var lastRotateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Logger starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val userId = android.os.Process.myUid() / 100_000
        if (userId != 0) {
            Log.w(TAG, "running in user $userId, not owner — stopping (logger is user-0 only)")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            instance = this
            targetAppId = try { packageManager.getPackageUid(TARGET_PKG, 0) % 100_000 } catch (_: Exception) { -1 }
            Log.i(TAG, "start uid=${android.os.Process.myUid()} targetAppId=$targetAppId")
            openCurrent()
            pidThread     = Thread(::pidLoop, "logger-pid").also { it.isDaemon = true; it.start() }
            captureThread = Thread(::captureLoop, "logger-capture").also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        instance = null
        logcatProc?.destroy()
        captureThread?.interrupt()
        pidThread?.interrupt()
        synchronized(rotateLock) { current?.flush(); current?.close() }
        Log.i(TAG, "onDestroy")
    }

    // ─── PID set (UID+PID hybrid) ────────────────────────────────────────────

    private fun pidLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            refreshPids()
            try { Thread.sleep(PID_REFRESH_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun refreshPids() {
        val set = mutableSetOf<Int>()
        // ActivityManager — user 0 (and cross-user for UID 1000 callers)
        getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.filter { it.processName == TARGET_PKG || it.processName.startsWith("$TARGET_PKG:") }
            ?.forEach { set.add(it.pid) }
        // ps -A — secondary-user processes AM omits (toybox domain reads cross-user /proc)
        try {
            val p = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            p.inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = line.trim().split("\\s+".toRegex())
                    if (cols.size >= 2) {
                        val name = cols.last()
                        if (name == TARGET_PKG || name.startsWith("$TARGET_PKG:"))
                            cols[1].toIntOrNull()?.let { set.add(it) }
                    }
                }
            }
            p.waitFor()
        } catch (_: Exception) {}
        amPidSet = set
        updateNotification("PIDs:${set.size} · ${currentFile?.name ?: "-"}")
    }

    // ─── capture ─────────────────────────────────────────────────────────────

    private val uidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+""")
    private val pidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+(\d+)\s+""")

    private fun captureLoop() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "uid,threadtime", "-b", "all"))
            logcatProc = proc
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (!running) return@forEachLine
                val uid = uidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
                if (uid % 100_000 != targetAppId) return@forEachLine          // step 1: UID
                val pid = pidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
                if (pid !in amPidSet) return@forEachLine                       // step 2: PID
                val userId = uid / 100_000
                writeLine("${if (userId == 0) "[owner]" else "[user$userId]"} $line")
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture error: ${e.message}", e)
        }
    }

    // ─── rotating writer ─────────────────────────────────────────────────────

    private fun openCurrent() {
        val dir = logsDir(this)
        dir.mkdirs()
        currentFile = File(dir, "current.log")
        // append mode: never truncate; a leftover current.log from a prior run is
        // preserved and rolled into the next completed segment on rotate.
        current = PrintWriter(BufferedWriter(FileWriter(currentFile!!, true)))
        currentBytes = currentFile!!.length()
        lastRotateMs = System.currentTimeMillis()
    }

    private fun writeLine(s: String) {
        synchronized(rotateLock) {
            val w = current ?: return
            w.println(s)
            currentBytes += s.length + 1
            val now = System.currentTimeMillis()
            if (currentBytes >= ROTATE_BYTES || (now - lastRotateMs >= ROTATE_MS && currentBytes > 0)) {
                rotateLocked()
            }
        }
    }

    /** Caller must hold rotateLock. Renames current.log → log-<ms>.log, reopens fresh. */
    private fun rotateLocked() {
        val w = current ?: return
        w.flush()
        w.close()
        val cf = currentFile
        if (cf != null && cf.length() > 0) {
            val done = File(cf.parentFile, "log-${System.currentTimeMillis()}.log")
            if (cf.renameTo(done)) Log.i(TAG, "rotated → ${done.name} (${done.length()}B)")
            else Log.w(TAG, "rotate rename failed for ${cf.name}")
        }
        openCurrent()
    }

    /** Forced rotation requested cross-user by UsbSyncService (via LogProvider.call). */
    fun forceRotate() {
        synchronized(rotateLock) { if (currentBytes > 0) rotateLocked() }
    }

    // ─── notification ────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Logger", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Logger: $TARGET_PKG")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
}
