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
                Log.i("BootReceiver", "Boot event: ${intent.action} — starting services")
                context.startForegroundService(
                    Intent(context, LogCaptureService::class.java)
                )

                // USB-sync pathway: start the user-0 producer (singleUser → routed
                // to user 0; self-stops in any other user) …
                context.startForegroundService(
                    Intent(context, LoggerService::class.java)
                )
                // … and the USB watcher (StorageVolumeCallback + immediate scan).
                // NOTE: this BootReceiver runs per-user; the watcher only sees the
                // USB in whichever user is foreground, so opening the app in the
                // foreground user is the reliable way to register the watcher there.
                context.startForegroundService(
                    Intent(context, UsbWatchService::class.java)
                )
            }
        }
    }
}
