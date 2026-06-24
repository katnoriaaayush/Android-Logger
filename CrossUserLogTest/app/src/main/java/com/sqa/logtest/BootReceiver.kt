package com.sqa.logtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // LOCKED_BOOT_COMPLETED fires before user unlock (direct boot mode).
            // BOOT_COMPLETED fires after unlock. We handle both so the service
            // starts as early as possible when declared directBootAware.
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.i("BootReceiver", "Boot event: ${intent.action} — starting LogCaptureService")
                context.startForegroundService(
                    Intent(context, LogCaptureService::class.java)
                )
            }
        }
    }
}
