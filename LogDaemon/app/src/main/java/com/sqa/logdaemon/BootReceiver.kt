package com.sqa.logdaemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the LogDaemonService when the device finishes booting.
 * Handles both BOOT_COMPLETED (normal boot) and LOCKED_BOOT_COMPLETED
 * (Direct Boot, before user unlock) so the daemon is up ASAP.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver got action=${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val serviceIntent = Intent(context, LogDaemonService::class.java)
                context.startService(serviceIntent)
                Log.i(TAG, "LogDaemonService start requested")
            }
        }
    }

    companion object {
        private const val TAG = "LogDaemon.Boot"
    }
}
