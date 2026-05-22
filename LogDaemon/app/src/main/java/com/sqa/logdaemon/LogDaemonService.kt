package com.sqa.logdaemon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * One-shot launcher for the native helper daemon.
 *
 * At boot: checks /proc/*/cmdline for a running helper, launches it if absent,
 * then calls stopSelf(). The helper double-forks and setsid()s — it outlives
 * this service and survives profile-switch kills.
 *
 * On USB hot-plug (ACTION_MEDIA_MOUNTED from UsbStateReceiver): writes the USB
 * path to a hint file in app storage. The running helper's find_usb() polls
 * this file as a fallback when it cannot list /mnt/media_rw/ directly. If the
 * helper is not alive, it is (re)launched with the path as argv[1].
 */
class LogDaemonService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LogDaemonService created")
        val usbHint = findUsbHint()
        if (usbHint != null) writeHintFile(usbHint)

        if (isHelperAlive()) {
            Log.i(TAG, "Helper already running")
        } else {
            launchHelper(usbHint)
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) {
            // Try the mounted path from the intent URI first (ACTION_MEDIA_MOUNTED
            // data is file:///storage/<uuid>; translate to /mnt/media_rw/<uuid>).
            // Fall back to a full scan if the URI path isn't usable yet.
            val usbPath = usbPathFromIntent(intent) ?: findUsbHint()
            if (usbPath != null) {
                Log.i(TAG, "USB mounted: $usbPath — updating hint file")
                writeHintFile(usbPath)
                if (!isHelperAlive()) {
                    Log.i(TAG, "Helper not running, launching now")
                    launchHelper(usbPath)
                }
                // If helper IS alive it discovers the new path via the hint file
                // on its next find_usb() poll (within USB_SCAN_INTERVAL_SEC).
            } else {
                Log.w(TAG, "MEDIA_MOUNTED but no USB with log.sinfo found (data=${intent.data})")
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * ACTION_MEDIA_MOUNTED carries a file:///storage/<uuid> URI.
     * Translate the UUID segment to /mnt/media_rw/<uuid> and verify log.sinfo.
     */
    private fun usbPathFromIntent(intent: Intent): String? {
        val storagePath = intent.data?.path ?: return null          // /storage/3C21-40FC
        val uuid = File(storagePath).name.takeIf { it.isNotEmpty() } ?: return null
        val mediaRwPath = File("/mnt/media_rw", uuid)
        return if (File(mediaRwPath, "log.sinfo").canRead()) mediaRwPath.absolutePath
               else null
    }

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
        return File("/mnt/media_rw").listFiles()
            ?.firstOrNull { dir -> dir.isDirectory && File(dir, "log.sinfo").canRead() }
            ?.absolutePath
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
        private const val TAG             = "LogDaemon.Service"
        private const val HELPER_LIB_NAME = "liblogdaemon_helper.so"
        private const val HINT_FILENAME   = "usb_hint"
        const val         ENV_HINT_FILE   = "LOGDAEMON_HINT_FILE"
    }
}
