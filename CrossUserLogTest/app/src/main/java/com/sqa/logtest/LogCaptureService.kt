package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogCaptureService : Service() {

    companion object {
        const val TAG = "LogCaptureService"
        // ── Change this to the package you want to trace across all user profiles ──
        const val TARGET_PKG = "com.abc.xyz"

        const val CHANNEL_ID  = "log_capture"
        const val NOTIF_ID    = 1
        const val ACTION_STOP = "com.sqa.logtest.STOP"
    }

    private var logcatProcess: java.lang.Process? = null
    private var captureThread: Thread? = null
    @Volatile private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate  myUid=${android.os.Process.myUid()}  myPid=${android.os.Process.myPid()}")
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        startCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested via ACTION_STOP")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY          // restart automatically if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatProcess?.destroy()
        captureThread?.interrupt()
        Log.i(TAG, "onDestroy — capture stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        val targetUids = resolveTargetUids()
        Log.i(TAG, "Target UIDs for $TARGET_PKG: $targetUids")

        // Owner-profile external storage = /sdcard/ = /storage/emulated/0/
        // Accessible by adb pull, file managers, etc.
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()

        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(outDir, "${TARGET_PKG}_$ts.txt")
        outputFile = file

        updateNotification("Writing → ${file.name}")

        captureThread = Thread({
            runCapture(file, targetUids)
        }, "logcat-capture")
        captureThread!!.isDaemon = true
        captureThread!!.start()
    }

    private fun runCapture(outFile: File, targetUids: Set<Int>) {
        try {
            // -v uid,threadtime — adds UID column and full timestamp to each line
            // -b all            — read main + system + crash + radio + kernel buffers
            // (no -d)           — stream continuously, don't dump-and-exit
            val proc = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-v", "uid,threadtime", "-b", "all"
            ))
            logcatProcess = proc

            PrintWriter(outFile.bufferedWriter()).use { writer ->
                // ── File header ──────────────────────────────────────────────
                writer.println("# CrossUserLogTest")
                writer.println("# Target package : $TARGET_PKG")
                writer.println("# Service UID    : ${android.os.Process.myUid()} " +
                        (if (android.os.Process.myUid() == 1000) "(system — cross-user access ENABLED)"
                         else "(NOT 1000 — platform signing missing, cross-user access BLOCKED)"))
                writer.println("# Target UIDs    : $targetUids")
                writer.println("#   Logic: owner UID + (userId * 100_000) for each secondary profile")
                writer.println("# Started        : ${Date()}")
                writer.println("# Output         : ${outFile.absolutePath}")
                writer.println("# Format         : date time uid pid tid level tag: message")
                writer.println("#")
                writer.println("# Lines below are filtered to $TARGET_PKG only.")
                writer.println("# ─────────────────────────────────────────────────────────")
                writer.flush()

                if (targetUids.isEmpty()) {
                    writer.println("# WARNING: $TARGET_PKG not found in any profile — no UID to filter on.")
                    writer.println("# Install the target package and restart this service.")
                    writer.flush()
                    Log.w(TAG, "$TARGET_PKG not installed anywhere — nothing to capture")
                    return
                }

                // ── Stream logcat lines ──────────────────────────────────────
                var lineCount = 0
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (matchesTarget(line, targetUids)) {
                        writer.println(line)
                        lineCount++
                        // Flush every 10 lines so the file is useful even if the service dies
                        if (lineCount % 10 == 0) writer.flush()
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Capture thread interrupted — normal shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
        }
    }

    // ── UID resolution ────────────────────────────────────────────────────────
    //
    // Android multi-user UID formula:
    //   userN_uid = userId * 100_000 + (owner_uid % 100_000)
    //
    // We don't need MANAGE_USERS or hidden APIs — we compute the expected UID
    // for each user slot (0–9) from the owner's base UID. If the package isn't
    // installed in a given profile, no log lines will match that slot's UID,
    // so probing unused slots is harmless.

    private fun resolveTargetUids(): Set<Int> {
        val ownerUid = try {
            packageManager.getApplicationInfo(TARGET_PKG, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$TARGET_PKG not installed in owner profile")
            return emptySet()
        }

        Log.i(TAG, "$TARGET_PKG owner UID: $ownerUid")
        val baseOffset = ownerUid % 100_000

        return buildSet {
            add(ownerUid)                               // user 0 (owner)
            for (userId in 1..9) {                     // secondary profiles 1–9
                add(userId * 100_000 + baseOffset)     // e.g. user 10 → 1_000_000 + offset
            }
        }
    }

    // ── Line matcher ──────────────────────────────────────────────────────────
    //
    // logcat -v uid,threadtime line format:
    //   MM-DD HH:MM:SS.mmm  <uid>  <pid>  <tid>  <L>  <tag>: <message>
    //   e.g.: 07-15 10:23:45.123  10234  1234  5678  D  MyTag: hello

    private val lineRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+""")

    private fun matchesTarget(line: String, targetUids: Set<Int>): Boolean {
        val uid = lineRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
        return uid in targetUids
    }

    // ── Notification ──────────────────────────────────────────────────────────

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

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    fun getOutputFilePath(): String = outputFile?.absolutePath ?: "not started"
}
