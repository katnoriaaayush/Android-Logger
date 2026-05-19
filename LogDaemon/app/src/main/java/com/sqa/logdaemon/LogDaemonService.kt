package com.sqa.logdaemon

import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import com.sqa.logdaemon.session.SessionManager
import com.sqa.logdaemon.usb.UsbMountLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background daemon.
 *
 *  - Started at boot (BootReceiver)
 *  - Kept alive by android:persistent="true" in the manifest (system app)
 *  - Receives forwarded USB / MEDIA intents from UsbStateReceiver
 *  - No notification, no foreground state
 *
 * Lifecycle for a session:
 *   USB ATTACHED → wait briefly for FS mount → look for log.sinfo →
 *   start SessionManager → keep running →
 *   USB DETACHED → stop SessionManager → idle
 */
class LogDaemonService : Service() {

    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSession: SessionManager? = null

    @Volatile
    private var currentUsbPath: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LogDaemonService created")
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

            null -> {
                // Service started by BootReceiver — just stay alive
                Log.i(TAG, "Service start (no action) — entering idle state")
            }

            else -> Log.i(TAG, "Ignoring unhandled action: $action")
        }

        // START_STICKY: if the system kills us, restart with a null intent.
        // For system apps with persistent=true, this is mostly belt-and-suspenders.
        return START_STICKY
    }

    private fun handleUsbAttached() {
        // If we already have a session running, ignore. We only support one
        // active USB at a time. The next attach can take over after detach.
        if (currentSession != null) {
            Log.i(TAG, "USB attach while session already running — ignoring")
            return
        }

        daemonScope.launch {
            // The USB filesystem may not be mounted immediately after the
            // attach broadcast. Poll briefly (up to ~5s) for log.sinfo to
            // appear on a removable volume.
            var usbRoot: File? = null
            repeat(MOUNT_POLL_ATTEMPTS) { attempt ->
                usbRoot = UsbMountLocator.findUsbWithConfig(this@LogDaemonService)
                if (usbRoot != null) return@repeat
                Log.i(TAG, "Waiting for USB mount... attempt ${attempt + 1}")
                delay(MOUNT_POLL_INTERVAL_MS)
            }

            val root = usbRoot
            if (root == null) {
                Log.i(TAG, "No USB volume with log.sinfo found — staying idle")
                return@launch
            }

            // Guard against duplicate broadcasts for the same volume
            if (root.absolutePath == currentUsbPath) {
                Log.i(TAG, "Duplicate attach for ${root.absolutePath} — ignoring")
                return@launch
            }

            Log.i(TAG, "Starting session on ${root.absolutePath}")
            val session = SessionManager(applicationContext, root)
            val started = session.start()
            if (started) {
                currentSession = session
                currentUsbPath = root.absolutePath
            } else {
                Log.w(TAG, "Session failed to start (see _error.log on USB)")
            }
        }
    }

    private fun handleUsbDetached() {
        val session = currentSession
        if (session == null) {
            Log.i(TAG, "USB detach with no active session — nothing to do")
            return
        }

        Log.i(TAG, "USB detached — stopping session")
        daemonScope.launch {
            try {
                session.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping session", e)
            } finally {
                currentSession = null
                currentUsbPath = null
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "LogDaemonService destroyed")
        currentSession?.stop()
        currentSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LogDaemon.Service"
        private const val MOUNT_POLL_ATTEMPTS = 10
        private const val MOUNT_POLL_INTERVAL_MS = 500L
    }
}
