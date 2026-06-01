package com.sqa.logdaemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver got action=${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context.startService(Intent(context, LogDaemonService::class.java))
                Log.i(TAG, "LogDaemonService start requested")
            }
        }
    }

    companion object {
        private const val TAG = "LogDaemon.Boot"
    }
}
