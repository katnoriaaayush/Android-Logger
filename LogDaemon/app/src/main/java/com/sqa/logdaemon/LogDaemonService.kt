package com.sqa.logdaemon

import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import com.sqa.logdaemon.usb.UsbMountLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Thin launcher for the native helper daemon.
 *
 * The actual log capture runs in a native binary (liblogdaemon_helper.so) that
 * is bundled in the APK's lib/<abi>/ directory and extracted by the package
 * manager to applicationInfo.nativeLibraryDir at install time. This service
 * forks the helper with ProcessBuilder; the helper immediately calls setsid()
 * to detach itself, becoming reparented to init (PID 1) and surviving Samsung's
 * profile-switch kills of this Android service.
 *
 * Lifecycle:
 *   USB ATTACHED  -> find USB with log.sinfo -> if no helper running, launch one
 *   USB DETACHED  -> helper detects USB removal itself and exits; we just clear state
 *   Service restart (profile switch) -> if helper still alive, do nothing; else relaunch
 */
class LogDaemonService : Service() {

    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentUsbPath: String? = null

    override fun onCreate() {
        super.onCreate()
        LogDaemonApp.serviceRunning = true
        Log.i(TAG, "LogDaemonService created")
        // Self-heal: if we restarted (e.g. profile switch killed the service)
        // and the USB is still present, re-check whether the helper is running.
        resumeIfUsbPresent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            Intent.ACTION_MEDIA_MOUNTED -> handleUsbAttached()

            UsbManager.ACTION_USB_DEVICE_DETACHED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED -> handleUsbDetached()

            null -> Log.i(TAG, "Service start (no action) — idle")
            else -> Log.i(TAG, "Ignoring action: $action")
        }

        return START_STICKY
    }

    private fun resumeIfUsbPresent() {
        daemonScope.launch {
            val root = UsbMountLocator.findUsbWithConfig(this@LogDaemonService) ?: return@launch
            Log.i(TAG, "USB already mounted at ${root.absolutePath} on service start")
            ensureHelperRunning(root)
        }
    }

    private fun handleUsbAttached() {
        daemonScope.launch {
            var usbRoot: File? = null
            repeat(MOUNT_POLL_ATTEMPTS) { attempt ->
                usbRoot = UsbMountLocator.findUsbWithConfig(this@LogDaemonService)
                if (usbRoot != null) return@repeat
                Log.i(TAG, "Waiting for USB mount... attempt ${attempt + 1}")
                delay(MOUNT_POLL_INTERVAL_MS)
            }

            val root = usbRoot ?: run {
                Log.i(TAG, "No USB volume with log.sinfo found")
                return@launch
            }

            ensureHelperRunning(root)
        }
    }

    private fun ensureHelperRunning(usbRoot: File) {
        val path = usbRoot.absolutePath

        if (isHelperAlive(path)) {
            Log.i(TAG, "Helper already running for $path — nothing to do")
            currentUsbPath = path
            return
        }

        launchHelper(path)
        currentUsbPath = path
    }

    private fun launchHelper(usbPath: String) {
        val helperFile = File(applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
        if (!helperFile.exists() || !helperFile.canExecute()) {
            Log.e(TAG, "Native helper not present or not executable at ${helperFile.absolutePath}")
            return
        }

        try {
            // The helper double-forks and setsid()'s before doing any work,
            // so this ProcessBuilder.start() returns almost immediately and
            // the launched child (the "first parent") exits. The grandchild
            // is reparented to init and outlives this service.
            val proc = ProcessBuilder(helperFile.absolutePath, usbPath)
                .redirectErrorStream(true)
                .start()
            // Don't wait — helper detaches itself. Just confirm spawn succeeded.
            Log.i(TAG, "Launched helper launcher (will detach) for $usbPath, launcher pid=${proc.pid()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch helper", e)
        }
    }

    private fun isHelperAlive(usbPath: String): Boolean {
        val pidFile = File(usbPath, ".logdaemon.pid")
        if (!pidFile.exists()) return false
        val pid = pidFile.readText().trim().toIntOrNull() ?: return false
        return File("/proc/$pid").exists()
    }

    private fun handleUsbDetached() {
        // The helper polls for USB presence and exits on its own. We just
        // clear our tracking state — no need to signal the helper, and on
        // a profile-switch false eject we want it to keep running anyway.
        Log.i(TAG, "USB detach event — helper will self-terminate if USB is really gone")
        currentUsbPath = null
    }

    override fun onDestroy() {
        LogDaemonApp.serviceRunning = false
        Log.i(TAG, "LogDaemonService destroyed (helper continues independently)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LogDaemon.Service"
        private const val MOUNT_POLL_ATTEMPTS = 10
        private const val MOUNT_POLL_INTERVAL_MS = 500L
        private const val HELPER_LIB_NAME = "liblogdaemon_helper.so"
    }
}
