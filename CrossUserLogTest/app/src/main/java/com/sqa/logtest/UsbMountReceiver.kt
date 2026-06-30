package com.sqa.logtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * UsbMountReceiver — fires in the FOREGROUND user when removable media mounts.
 * Pure trigger: kicks off UsbSyncService, which does the work with a foreground
 * notification so it survives the sync.
 *
 * NOTE: the ACTION_MEDIA_MOUNTED file:// broadcast is unreliable on modern
 * Android, so UsbSyncService re-resolves the volume via StorageManager itself
 * and the app also runs a startup scan (see MainActivity) — this receiver is the
 * fast path, not the only path.
 */
class UsbMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("UsbMountReceiver", "action=${intent.action} data=${intent.data}")
        context.startForegroundService(Intent(context, UsbSyncService::class.java))
    }
}
