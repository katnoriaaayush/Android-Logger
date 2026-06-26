package com.sqa.logtest

import android.app.ActivityManager
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
        const val TAG               = "LogCaptureService"
        const val TARGET_PKG        = "com.abc.xyz"
        const val CHANNEL_ID        = "log_capture"
        const val NOTIF_ID          = 1
        const val ACTION_STOP       = "com.sqa.logtest.STOP"
        const val EXTRA_FILTER_MODE = "filter_mode"
        const val FILTER_PID        = "pid"     // batch /proc scan, 15-s window
        const val FILTER_UID        = "uid"     // UID modulo only, no PID check
        const val FILTER_HYBRID     = "hybrid"  // UID pre-filter + ActivityManager PID verify
        const val PID_REFRESH_MS    = 15_000L
    }

    private var logcatProcess: java.lang.Process? = null
    private var captureThread: Thread? = null
    private var bgThread: Thread? = null
    @Volatile private var filterMode   = FILTER_PID
    @Volatile private var targetAppId  = -1
    @Volatile private var outputFile: File? = null

    // FILTER_PID: pid → userId (0 = owner, 10 = user10, …)
    private val pidToUser = ConcurrentHashMap<Int, Int>()

    // FILTER_HYBRID: set of PIDs belonging to TARGET_PKG populated by AM and kept
    // current via OnUidImportanceListener — event-driven, no polling.
    @Volatile private var amPidSet: Set<Int> = emptySet()
    private var uidImportanceListener: ActivityManager.OnUidImportanceListener? = null

    // ─── lifecycle ───────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate uid=${android.os.Process.myUid()}")
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (captureThread == null) {
            filterMode  = intent?.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_PID
            targetAppId = try { packageManager.getPackageUid(TARGET_PKG, 0) % 100_000 } catch (_: Exception) { -1 }
            Log.i(TAG, "filterMode=$filterMode targetAppId=$targetAppId")
            when (filterMode) {
                FILTER_PID    -> startBgThread("pid-refresher", ::refreshPids)
                FILTER_HYBRID -> {
                    refreshAmPids()       // sync: amPidSet populated before first logcat line
                    registerUidListener() // event-driven: refresh on every process start/stop
                }
            }
            startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatProcess?.destroy()
        captureThread?.interrupt()
        bgThread?.interrupt()
        unregisterUidListener()
        Log.i(TAG, "onDestroy")
    }

    // ─── background thread helper ────────────────────────────────────────────────

    private fun startBgThread(name: String, task: () -> Unit) {
        bgThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                task()
                try { Thread.sleep(PID_REFRESH_MS) } catch (e: InterruptedException) { break }
            }
        }, name).also { it.isDaemon = true; it.start() }
    }

    // ─── FILTER_PID: batch /proc scan ────────────────────────────────────────────

    private fun refreshPids() {
        val found = mutableMapOf<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            try {
                val raw = File(dir, "cmdline").readBytes()
                val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) raw.size else it }
                val name = raw.copyOf(end).toString(Charsets.UTF_8)
                if (name != TARGET_PKG && !name.startsWith("$TARGET_PKG:")) return@forEach
                val uid = File(dir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.split("\t")?.getOrNull(1)?.trim()?.toIntOrNull()
                } ?: 0
                found[pid] = uid / 100_000
            } catch (_: Exception) {}
        }
        pidToUser.clear()
        pidToUser.putAll(found)
        updateNotification(if (found.isNotEmpty()) "[PID] ${found.size} process(es)" else "[PID] Waiting…")
    }

    // ─── FILTER_HYBRID: ActivityManager PID refresh ──────────────────────────────

    private fun refreshAmPids() {
        val am = getSystemService(ActivityManager::class.java)
        val newSet = am.runningAppProcesses
            ?.filter { it.processName == TARGET_PKG || it.processName.startsWith("$TARGET_PKG:") }
            ?.map { it.pid }
            ?.toHashSet()
            ?: hashSetOf()
        amPidSet = newSet
        Log.d(TAG, "AM PIDs for $TARGET_PKG: $newSet")
        updateNotification(if (newSet.isNotEmpty()) "[Hybrid] ${newSet.size} PID(s) via AM" else "[Hybrid] Waiting for $TARGET_PKG…")
    }

    private fun registerUidListener() {
        val am = getSystemService(ActivityManager::class.java)
        val listener = object : ActivityManager.OnUidImportanceListener {
            override fun onUidImportance(uid: Int, importance: Int) {
                if (uid % 100_000 == targetAppId) {
                    Log.d(TAG, "UID $uid importance→$importance — refreshing AM PIDs")
                    Thread { refreshAmPids() }.start()
                }
            }
        }
        uidImportanceListener = listener
        am.addOnUidImportanceListener(listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE)
    }

    private fun unregisterUidListener() {
        uidImportanceListener?.let {
            try { getSystemService(ActivityManager::class.java).removeOnUidImportanceListener(it) }
            catch (_: Exception) {}
            uidImportanceListener = null
        }
    }

    // ─── logcat capture ──────────────────────────────────────────────────────────

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

            val myUid   = android.os.Process.myUid()
            val started = Date()

            fun writeHeader(w: PrintWriter, label: String, rule: String) {
                w.println("# CrossUserLogTest — $label")
                w.println("# Target package : $TARGET_PKG  (appId=$targetAppId)")
                w.println("# Filter mode    : $filterMode")
                w.println("# Service UID    : $myUid${if (myUid == 1000) " (system)" else " (NOT 1000)"}")
                w.println("# Started        : $started")
                w.println("# Match rule     : $rule")
                w.println("# Format         : date time uid pid tid level tag: message")
                w.println("# ─────────────────────────────────────────────────────────")
                w.flush()
            }

            val ruleDesc = when (filterMode) {
                FILTER_PID    -> "user-0: pid∈pidToUser (/proc batch scan every ${PID_REFRESH_MS/1000}s) | secondary: uid%100_000==appId"
                FILTER_UID    -> "uid%100_000==$targetAppId (all users, no PID check)"
                FILTER_HYBRID -> "uid%100_000==$targetAppId → pid∈amPidSet (event-driven via OnUidImportanceListener)"
                else          -> filterMode
            }

            PrintWriter(filteredFile.bufferedWriter()).use { filtered ->
                PrintWriter(completeFile.bufferedWriter()).use { complete ->
                    writeHeader(filtered, "filtered ($filterMode)", ruleDesc)
                    writeHeader(complete, "complete logcat dump", "every line from all buffers")

                    var filteredCount = 0
                    var totalCount    = 0

                    proc.inputStream.bufferedReader().forEachLine { line ->
                        complete.println(line)
                        totalCount++
                        if (totalCount % 100 == 0) complete.flush()

                        val uid = extractUid(line) ?: return@forEachLine

                        when (filterMode) {

                            FILTER_PID -> {
                                val userId = uid / 100_000
                                if (userId == 0) {
                                    val pid = extractPid(line) ?: return@forEachLine
                                    if (pidToUser[pid] == null) return@forEachLine
                                    filtered.println("[owner] $line")
                                } else {
                                    if (uid % 100_000 != targetAppId) return@forEachLine
                                    filtered.println("[user$userId] $line")
                                }
                                filteredCount++
                                if (filteredCount % 10 == 0) filtered.flush()
                            }

                            FILTER_UID -> {
                                if (uid % 100_000 != targetAppId) return@forEachLine
                                val userId = uid / 100_000
                                filtered.println("${if (userId == 0) "[owner]" else "[user$userId]"} $line")
                                filteredCount++
                                if (filteredCount % 10 == 0) filtered.flush()
                            }

                            FILTER_HYBRID -> {
                                // Step 1: UID pre-filter — fast, cross-user aware
                                if (uid % 100_000 != targetAppId) return@forEachLine
                                val userId = uid / 100_000
                                val pid    = extractPid(line) ?: return@forEachLine

                                // Step 2: PID verify against ActivityManager snapshot
                                if (pid !in amPidSet) return@forEachLine

                                filtered.println("${if (userId == 0) "[owner]" else "[user$userId]"} $line")
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

    // ─── line parsers ─────────────────────────────────────────────────────────────
    // logcat -v uid,threadtime:  MM-DD HH:MM:SS.mmm  <uid>  <pid>  <tid>  L  tag: msg

    private val uidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+""")
    private val pidRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+(\d+)\s+""")

    private fun extractUid(line: String): Int? = uidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun extractPid(line: String): Int? = pidRe.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // ─── notification ─────────────────────────────────────────────────────────────

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
