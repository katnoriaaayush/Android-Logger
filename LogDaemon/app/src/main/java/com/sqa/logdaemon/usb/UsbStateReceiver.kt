package com.sqa.logdaemon.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sqa.logdaemon.LogDaemonService

/**
 * Listens for ACTION_MEDIA_MOUNTED so the service can update the USB hint file
 * and launch the helper if it isn't already running. This handles the hot-plug
 * case: USB attached after the device booted and the service already ran.
 */
class UsbStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        Log.i(TAG, "USB mounted: ${intent.dataString}")
        context.startService(
            Intent(context, LogDaemonService::class.java).apply {
                action = intent.action
                data = intent.data  // file:///storage/<uuid> — needed to extract volume path
            }
        )
    }

    companion object {
        private const val TAG = "LogDaemon.UsbRx"
    }
}
