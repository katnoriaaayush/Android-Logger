package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * UsbWatchService — reliable mount/unmount detection for the FOREGROUND user.
 *
 * The classic ACTION_MEDIA_MOUNTED file:// broadcast (UsbMountReceiver) does not
 * fire for app manifest receivers on modern Android, so this service registers a
 * StorageManager.StorageVolumeCallback (API 30+) — the supported signal — and
 * also does an immediate scan to pick up a stick that's already mounted.
 *
 * Must run in the foreground user (the only one with the USB mounted), so it is
 * started from MainActivity.onResume and BootReceiver. On mount of a removable
 * volume it kicks UsbSyncService.
 */
class UsbWatchService : Service() {

    companion object {
        const val TAG        = "UsbWatchService"
        const val CHANNEL_ID = "usb_watch"
        const val NOTIF_ID   = 6
    }

    private var callback: StorageManager.StorageVolumeCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Watching for USB…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val userId = android.os.Process.myUid() / 100_000
        Log.i(TAG, "onStartCommand (foreground user=$userId)")
        if (callback == null) register()
        scanNow()
        return START_STICKY
    }

    private fun register() {
        val sm = getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cb = object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    Log.i(TAG, "onStateChanged uuid=${volume.uuid} removable=${volume.isRemovable} state=${volume.state}")
                    if (volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED) {
                        Log.i(TAG, "removable MOUNTED → starting UsbSyncService")
                        startForegroundService(Intent(this@UsbWatchService, UsbSyncService::class.java))
                    } else if (volume.isRemovable) {
                        Log.i(TAG, "removable volume now ${volume.state} (mount/unmount event)")
                    }
                }
            }
            sm.registerStorageVolumeCallback(mainExecutor, cb)
            callback = cb
            Log.i(TAG, "StorageVolumeCallback registered")
        } else {
            Log.w(TAG, "API<30 — StorageVolumeCallback unavailable; relying on MEDIA_MOUNTED + scan")
        }
    }

    /** Always kick a sync attempt so the volume enumeration is logged and an
     *  already-mounted stick is picked up immediately. UsbSyncService aborts
     *  cleanly (with logs) if there's no writable removable volume. */
    private fun scanNow() {
        Log.i(TAG, "scanNow → starting UsbSyncService (aborts if no writable volume)")
        startForegroundService(Intent(this, UsbSyncService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let {
            try { getSystemService(StorageManager::class.java).unregisterStorageVolumeCallback(it) }
            catch (_: Exception) {}
        }
        callback = null
        Log.i(TAG, "onDestroy")
    }

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB Watch", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB watcher")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
