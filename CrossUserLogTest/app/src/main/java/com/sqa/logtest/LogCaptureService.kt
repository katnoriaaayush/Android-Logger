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
        val appId = resolveTargetAppId()

        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()

        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(outDir, "${TARGET_PKG}_$ts.txt")
        outputFile = file

        updateNotification("Writing → ${file.name}")

        captureThread = Thread({ runCapture(file, appId) }, "logcat-capture")
        captureThread!!.isDaemon = true
        captureThread!!.start()
    }

    private fun runCapture(outFile: File, appId: Int) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-v", "uid,threadtime", "-b", "all"
            ))
            logcatProcess = proc

            PrintWriter(outFile.bufferedWriter()).use { writer ->
                val myUid = android.os.Process.myUid()
                writer.println("# CrossUserLogTest")
                writer.println("# Target package : $TARGET_PKG")
                writer.println("# Service UID    : $myUid " +
                        if (myUid == 1000) "(system — cross-user access ENABLED)"
                        else "(NOT 1000 — platform signing missing, cross-user access BLOCKED)")
                writer.println("# Match rule     : uid % 100_000 == $appId")
                writer.println("#   This matches $TARGET_PKG in ALL user profiles regardless of")
                writer.println("#   their user ID (Android assigns secondary user IDs from 10+,")
                writer.println("#   so probing 1..9 would miss every real secondary profile).")
                writer.println("# Started        : ${Date()}")
                writer.println("# Output         : ${outFile.absolutePath}")
                writer.println("# Format         : [userN] date time uid pid tid level tag: message")
                writer.println("# ─────────────────────────────────────────────────────────")
                writer.flush()

                if (appId < 0) {
                    writer.println("# WARNING: $TARGET_PKG not found in owner profile — nothing to capture.")
                    writer.println("# Install the package and restart this service.")
                    writer.flush()
                    Log.w(TAG, "$TARGET_PKG not installed — appId=-1, capture aborted")
                    return
                }

                Log.i(TAG, "Streaming logcat — matching uid%100_000==$appId for $TARGET_PKG")
                var lineCount = 0
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val uid = extractUid(line) ?: return@forEachLine
                    if (uid % 100_000 != appId) return@forEachLine

                    // Prefix each line with which user profile it came from
                    val userId = uid / 100_000
                    val prefix = if (userId == 0) "[owner]" else "[user$userId]"
                    writer.println("$prefix $line")
                    lineCount++
                    if (lineCount % 10 == 0) writer.flush()
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Capture thread interrupted — normal shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}", e)
        }
    }

    // ── AppId resolution ──────────────────────────────────────────────────────
    //
    // Android multi-user UID formula:  fullUid = userId * 100_000 + appId
    //
    // We only need the appId (uid % 100_000). Matching on this catches
    // the same package in every user profile no matter what userId Android
    // assigned (secondary users start at ID 10, not 1, in AOSP).

    private fun resolveTargetAppId(): Int {
        return try {
            val ownerUid = packageManager.getApplicationInfo(TARGET_PKG, 0).uid
            val appId = ownerUid % 100_000
            Log.i(TAG, "$TARGET_PKG owner uid=$ownerUid  appId=$appId")
            appId
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$TARGET_PKG not installed in owner profile")
            -1
        }
    }

    // ── Line parser ───────────────────────────────────────────────────────────
    //
    // logcat -v uid,threadtime line format:
    //   MM-DD HH:MM:SS.mmm  <uid>  <pid>  <tid>  <L>  <tag>: <message>

    private val lineRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+""")

    private fun extractUid(line: String): Int? =
        lineRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
