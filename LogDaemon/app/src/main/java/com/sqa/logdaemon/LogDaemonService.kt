package com.sqa.logdaemon

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStreamWriter

/**
 * Long-running user-0 service that captures logcat and streams filtered
 * lines to the native helper over abstract Unix socket @logdaemon.
 *
 * Responsibilities:
 *   - Launch the native helper if not already running
 *   - captureLoop(): wait for USB → parse log.sinfo → connect to helper
 *     → spawn logcat, scan PIDs, stream "pkg\tline\n" indefinitely
 *   - On USB mount events: update hint file, relaunch helper if dead
 *
 * The helper owns USB writes and the 8 MB ring buffer for FUSE gaps.
 * This service is intentionally singleUser=true (logcat is global —
 * user 0 sees all profiles). It must not be stopped while running.
 */
class LogDaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val myUser = UserHandle.getUserId(Process.myUid())
        Log.i(TAG, "LogDaemonService created (Logv3, user=$myUser)")

        // Only user 0 manages USB hint and launches the native helper.
        // The helper is a single native process; user 0 owns it.
        if (myUser == 0) {
            val usbHint = findUsbHint()
            if (usbHint != null) writeHintFile(usbHint)
            if (!isHelperAlive()) launchHelper(usbHint)
        }

        scope.launch { captureLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // USB mount events are only relevant in user 0 — MEDIA_MOUNTED fires there.
        if (intent?.action == Intent.ACTION_MEDIA_MOUNTED &&
            UserHandle.getUserId(Process.myUid()) == 0) {
            val usbPath = usbPathFromIntent(intent) ?: findUsbHint()
            if (usbPath != null) {
                Log.i(TAG, "USB mounted: $usbPath — updating hint file")
                writeHintFile(usbPath)
                if (!isHelperAlive()) {
                    Log.i(TAG, "Helper not running, launching now")
                    launchHelper(usbPath)
                }
            } else {
                Log.w(TAG, "MEDIA_MOUNTED but no USB with log.sinfo found (data=${intent.data})")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "LogDaemonService destroyed")
    }

    // ── capture loop ──────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        val myUser = UserHandle.getUserId(Process.myUid())

        // SecureFolder and Work Profiles run as user ≥ 2. Logcat in those users
        // is isolated to their own processes — not useful for our use case.
        // User 0 (owner) and user 1 (secondary user profile) are the targets.
        if (myUser >= 2) {
            Log.i(TAG, "captureLoop: user=$myUser is SecureFolder/Work Profile, skipping capture")
            return
        }
        Log.i(TAG, "captureLoop: user=$myUser starting")

        while (true) {
            try {
                Log.i(TAG, "captureLoop: waiting for USB")
                val usbPath = waitForUsb()
                Log.i(TAG, "captureLoop: USB at $usbPath")

                val packages = parseLogSinfo(usbPath)
                if (packages.isNullOrEmpty()) {
                    Log.w(TAG, "captureLoop: no packages in log.sinfo, retry in 10s")
                    delay(10_000)
                    continue
                }
                Log.i(TAG, "captureLoop: packages=${packages.joinToString()}")

                val socket = connectToHelper()
                if (socket == null) {
                    Log.w(TAG, "captureLoop: could not connect to helper, retry in 5s")
                    delay(5_000)
                    continue
                }

                try {
                    streamLogcat(packages, socket)
                } finally {
                    runCatching { socket.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "captureLoop: error, restarting in 5s", e)
                delay(5_000)
            }
        }
    }

    // Poll findUsbHint() every 3 s until a valid USB path is found.
    private suspend fun waitForUsb(): String {
        while (true) {
            val path = findUsbHint()
            if (path != null) return path
            delay(3_000)
        }
    }

    // Read the [packages] section from log.sinfo on the given USB path.
    private fun parseLogSinfo(usbPath: String): List<String>? {
        val configFile = File(usbPath, "log.sinfo")
        if (!configFile.canRead()) {
            Log.w(TAG, "Cannot read log.sinfo at $usbPath")
            return null
        }
        val packages = mutableListOf<String>()
        var inPackages = false
        configFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEachLine
            if (line.startsWith('[')) {
                inPackages = (line == "[packages]")
                return@forEachLine
            }
            if (inPackages) packages.add(line)
        }
        return packages
    }

    // Connect to the helper's abstract Unix socket @logdaemon.
    // Only user 0 relaunches the helper; user 1 waits for it to appear.
    // Returns null after 5 attempts.
    private suspend fun connectToHelper(): LocalSocket? {
        val myUser = UserHandle.getUserId(Process.myUid())
        repeat(5) { attempt ->
            if (!isHelperAlive()) {
                if (myUser == 0) {
                    val hint = findUsbHint()
                    Log.i(TAG, "Helper not alive, launching (attempt ${attempt + 1})")
                    launchHelper(hint)
                    delay(800)
                } else {
                    Log.i(TAG, "Helper not alive yet (user=$myUser, attempt ${attempt + 1}), waiting...")
                    delay(2_000)
                }
            }
            try {
                val socket = LocalSocket()
                socket.connect(
                    LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                )
                Log.i(TAG, "Connected to helper @$SOCKET_NAME")
                return socket
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt ${attempt + 1}: ${e.message}")
                delay(2_000)
            }
        }
        return null
    }

    // Spawn logcat, scan PIDs, and stream matching lines to the helper socket.
    // Runs until logcat exits, the socket write fails, or the coroutine is cancelled.
    private suspend fun streamLogcat(packages: List<String>, socket: LocalSocket) {
        val pidMap = mutableMapOf<Int, String>()
        rescanPids(packages, pidMap)

        val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)

        val proc = ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "*:V")
            .redirectErrorStream(false)
            .start()

        try {
            val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
            var lastRescan = System.currentTimeMillis()
            var linesStreamed = 0L

            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break

                val now = System.currentTimeMillis()
                if (now - lastRescan >= PID_RESCAN_INTERVAL_MS) {
                    rescanPids(packages, pidMap)
                    lastRescan = now
                }

                val pid = extractPid(line) ?: continue
                val pkg = pidMap[pid] ?: continue

                try {
                    writer.write("$pkg\t$line\n")
                    writer.flush()
                    linesStreamed++
                } catch (e: Exception) {
                    Log.w(TAG, "Socket write failed after $linesStreamed lines: ${e.message}")
                    break
                }
            }
            Log.i(TAG, "streamLogcat ended after $linesStreamed lines")
        } finally {
            proc.destroy()
        }
    }

    // Scan /proc to build a pid→package map for the given package names.
    private fun rescanPids(packages: List<String>, pidMap: MutableMap<Int, String>) {
        pidMap.clear()
        File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.forEach { dir ->
                val pid = dir.name.toInt()
                try {
                    val bytes = File(dir, "cmdline").readBytes()
                    val nullIdx = bytes.indexOfFirst { it == 0.toByte() }
                    val cmdline = if (nullIdx >= 0) bytes.copyOf(nullIdx).toString(Charsets.UTF_8)
                                  else bytes.toString(Charsets.UTF_8)
                    // cmdline may be "com.example.app" or "com.example.app:subproc"
                    val pkgName = cmdline.split(':').first().trim()
                    if (pkgName in packages) {
                        pidMap[pid] = pkgName
                    }
                } catch (_: Exception) {}
            }
    }

    // Extract PID from a logcat threadtime line:
    //   MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: MSG
    private fun extractPid(line: String): Int? {
        val parts = line.trimStart().split(Regex("\\s+"))
        return if (parts.size >= 3) parts[2].toIntOrNull() else null
    }

    // ── USB / hint helpers ────────────────────────────────────────────────────

    private fun isHelperAlive(): Boolean {
        return File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.any { dir ->
                try {
                    File(dir, "cmdline").readBytes()
                        .toString(Charsets.UTF_8)
                        .contains(HELPER_LIB_NAME)
                } catch (_: Exception) { false }
            } ?: false
    }

    private fun findUsbHint(): String? {
        val volumes = File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" }
            ?: emptyList()
        Log.d(TAG, "Scanning /storage/ — ${volumes.size} volume(s): ${volumes.joinToString { it.name }}")
        val match = volumes.firstOrNull { File(it, "log.sinfo").canRead() }
        if (match == null) {
            Log.i(TAG, "No volume with log.sinfo found under /storage/")
            return null
        }
        Log.i(TAG, "log.sinfo found on volume ${match.name}, resolving write path...")
        return resolveWritablePath(match.name)
    }

    private fun resolveWritablePath(uuid: String): String? {
        val mediaRw = File("/mnt/media_rw", uuid)
        if (File(mediaRw, "log.sinfo").canRead()) {
            Log.i(TAG, "Write path: ${mediaRw.absolutePath} [raw vold mount]")
            return mediaRw.absolutePath
        }
        Log.i(TAG, "/mnt/media_rw/$uuid not accessible, trying /storage/$uuid")
        val storage = File("/storage", uuid)
        if (File(storage, "log.sinfo").canRead()) {
            Log.i(TAG, "Write path: ${storage.absolutePath} [FUSE overlay]")
            return storage.absolutePath
        }
        Log.e(TAG, "Neither /mnt/media_rw/$uuid nor /storage/$uuid accessible")
        return null
    }

    private fun usbPathFromIntent(intent: Intent): String? {
        val uuid = intent.data?.path?.let { File(it).name }
            ?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "MEDIA_MOUNTED intent has no data URI")
            return null
        }
        Log.i(TAG, "MEDIA_MOUNTED intent uuid=$uuid, resolving write path...")
        return resolveWritablePath(uuid)
    }

    private fun writeHintFile(usbPath: String) {
        try {
            hintFile().writeText(usbPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write hint file", e)
        }
    }

    private fun hintFile(): File = File(filesDir, HINT_FILENAME)

    private fun launchHelper(usbHint: String?) {
        val helperFile = File(applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
        if (!helperFile.exists() || !helperFile.canExecute()) {
            Log.e(TAG, "Helper not found or not executable: ${helperFile.absolutePath}")
            return
        }
        val cmd = mutableListOf(helperFile.absolutePath)
        if (usbHint != null) {
            cmd.add(usbHint)
            Log.i(TAG, "Passing USB hint to helper: $usbHint")
        }
        try {
            ProcessBuilder(cmd)
                .apply { environment()[ENV_HINT_FILE] = hintFile().absolutePath }
                .redirectErrorStream(true)
                .start()
            Log.i(TAG, "Helper launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch helper", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG                  = "LogDaemon.Service"
        private const val HELPER_LIB_NAME      = "liblogdaemon_helper.so"
        private const val HINT_FILENAME        = "usb_hint"
        private const val SOCKET_NAME          = "logdaemon"
        private const val PID_RESCAN_INTERVAL_MS = 2_000L
        const val         ENV_HINT_FILE        = "LOGDAEMON_HINT_FILE"
    }
}
