package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream

/**
 * UsbSyncService — runs in whichever user is FOREGROUND (the only user with the
 * USB mounted). Stateless across runs: all progress lives in a manifest ON THE
 * USB, because this service may run as a different user each invocation.
 *
 * Each run:
 *   1. resolve the USB write surface
 *   2. force-rotate the user-0 logger (cross-user call) so newest logs are syncable
 *   3. read the USB manifest → set of already-synced filenames
 *   4. query LogProvider (content://0@…) for completed segments
 *   5. for each not-yet-synced: openFileDescriptor("r") → stream to USB
 *   6. rewrite the manifest
 */
class UsbSyncService : Service() {

    companion object {
        const val TAG        = "UsbSyncService"
        const val CHANNEL_ID = "usb_sync"
        const val NOTIF_ID   = 5
        const val MANIFEST   = ".logsync_manifest.json"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("USB sync starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({ runSync() }, "usb-sync").start()
        return START_NOT_STICKY
    }

    private fun runSync() {
        val userId = android.os.Process.myUid() / 100_000
        Log.i(TAG, "sync run (foreground user=$userId)")
        try {
            // 1. USB surface
            val surface = UsbWriteSurface.resolve(this)
            if (surface == null) {
                Log.w(TAG, "no writable removable volume — aborting")
                updateNotification("no writable USB found")
                return
            }
            updateNotification("USB: ${surface.label}")

            val listUri = CrossUser.addUserId(Uri.parse("content://${LogProvider.AUTHORITY}/logs"), 0)

            Log.i(TAG, "list URI = $listUri")

            // 2. force-rotate the user-0 logger so the newest data is a completed segment
            try {
                val r = contentResolver.call(listUri, "rotate", null, null)
                Log.i(TAG, "rotate call → rotated=${r?.getBoolean("rotated")}")
            } catch (e: Exception) {
                Log.w(TAG, "rotate call failed: ${e.message}")
            }

            // 3. manifest → already-synced set
            val synced = readManifest(surface)
            Log.i(TAG, "manifest: ${synced.size} already-synced: $synced")

            // 4. query completed segments
            val toSync = ArrayList<Pair<String, Long>>()
            contentResolver.query(listUri, null, null, null, null)?.use { c ->
                val iName = c.getColumnIndexOrThrow(LogProvider.COL_NAME)
                val iMtime = c.getColumnIndexOrThrow(LogProvider.COL_MTIME)
                while (c.moveToNext()) {
                    val name = c.getString(iName)
                    val mtime = c.getLong(iMtime)
                    if (name !in synced) toSync.add(name to mtime)
                }
            }
            toSync.sortBy { it.second }
            Log.i(TAG, "${toSync.size} segment(s) to sync")

            // 5. stream each via cross-user FD
            var ok = 0
            for ((name, _) in toSync) {
                val fileUri = CrossUser.addUserId(
                    Uri.parse("content://${LogProvider.AUTHORITY}/logs/$name"), 0)
                try {
                    var copied = 0L
                    contentResolver.openFileDescriptor(fileUri, "r")!!.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            surface.openOutput(name).use { out -> copied = input.copyTo(out) }
                        }
                    }
                    synced.add(name)
                    ok++
                    updateNotification("synced $ok/${toSync.size}")
                    Log.i(TAG, "synced $name ($copied bytes) → ${surface.label}/$name")
                } catch (e: Exception) {
                    Log.w(TAG, "failed $name: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            // 6. rewrite manifest
            writeManifest(surface, synced)
            Log.i(TAG, "done — $ok/${toSync.size} synced")
            updateNotification("done — $ok/${toSync.size} synced")
        } catch (e: Exception) {
            Log.e(TAG, "sync error: ${e.message}", e)
            updateNotification("error: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    // ─── manifest (single source of truth, lives on the USB) ─────────────────

    private fun readManifest(surface: UsbWriteSurface): MutableSet<String> {
        val set = LinkedHashSet<String>()
        try {
            val text = surface.readText(MANIFEST) ?: return set
            val arr = JSONObject(text).optJSONArray("synced") ?: JSONArray()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
        } catch (e: Exception) {
            Log.w(TAG, "manifest read failed (treating as empty): ${e.message}")
        }
        return set
    }

    private fun writeManifest(surface: UsbWriteSurface, synced: Set<String>) {
        try {
            val obj = JSONObject()
            obj.put("synced", JSONArray(synced.toList()))
            obj.put("updated", System.currentTimeMillis())
            surface.writeText(MANIFEST, obj.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "manifest write failed: ${e.message}")
        }
    }

    // ─── notification ────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB Sync", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Log → USB sync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
}
