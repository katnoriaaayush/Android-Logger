package com.sqa.logdaemon.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sqa.logdaemon.LogDaemonService

/**
 * Forwards USB attach/detach and media mount/unmount events to the daemon
 * service. We listen to MEDIA_MOUNTED because that's when the USB drive is
 * actually accessible at a filesystem path — USB_DEVICE_ATTACHED fires earlier,
 * before the kernel has mounted the FS.
 */
class UsbStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "USB/media event: $action data=${intent.dataString}")

        val forward = Intent(context, LogDaemonService::class.java).apply {
            this.action = action
            intent.data?.let { data = it }
        }
        try {
            context.startService(forward)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward to LogDaemonService", e)
        }
    }

    companion object {
        private const val TAG = "LogDaemon.UsbRx"
    }
}
