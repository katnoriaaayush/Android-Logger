package com.sqa.logdaemon.usb

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

/**
 * Resolves the mount point of an attached USB mass-storage device.
 *
 * Android stores external volumes under /mnt/media_rw/<volume-uuid>/
 * (system-app accessible) or /storage/<volume-uuid>/ (user-visible).
 * We prefer /mnt/media_rw because as a system app we have full RW access there.
 *
 * Strategy:
 *   1. Iterate StorageVolume list, filter to removable + mounted volumes
 *   2. For each, get the directory path
 *   3. Return the first one that contains log.sinfo at its root
 */
object UsbMountLocator {

    private const val TAG = "LogDaemon.UsbMount"
    private const val CONFIG_FILENAME = "log.sinfo"

    /**
     * Returns the root of the first removable, mounted USB volume that
     * contains [CONFIG_FILENAME], or null if none found.
     */
    fun findUsbWithConfig(context: Context): File? {
        val volumes = listMountedRemovableVolumes(context)
        Log.i(TAG, "Found ${volumes.size} mounted removable volume(s): " +
            volumes.joinToString { it.absolutePath })

        return volumes.firstOrNull { volume ->
            val cfg = File(volume, CONFIG_FILENAME)
            val exists = cfg.exists() && cfg.canRead()
            Log.i(TAG, "  ${volume.absolutePath}/$CONFIG_FILENAME exists=$exists")
            exists
        }
    }

    /**
     * Lists all currently mounted, removable storage volumes accessible
     * to a system app.
     */
    private fun listMountedRemovableVolumes(context: Context): List<File> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val result = mutableListOf<File>()

        // Primary path: StorageManager API
        try {
            val volumes = storageManager.storageVolumes
            for (volume in volumes) {
                if (!volume.isRemovable) continue
                if (volume.state != Environment.MEDIA_MOUNTED) continue
                val dir = volume.directory
                if (dir != null && dir.exists()) {
                    // Prefer /mnt/media_rw/<uuid>/ over /storage/<uuid>/.
                    // /storage/ is a per-user FUSE overlay that is torn down and
                    // recreated on profile switches, causing write failures in the
                    // native helper. /mnt/media_rw/ is the underlying raw FAT mount
                    // managed by vold and remains accessible regardless of which user
                    // profile is active.
                    val rawDir = File("/mnt/media_rw", dir.name)
                    result.add(if (rawDir.exists() && rawDir.canRead()) rawDir else dir)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "StorageManager enumeration failed, falling back to /mnt/media_rw", e)
        }

        // Fallback: scan /mnt/media_rw directly (system-app readable)
        if (result.isEmpty()) {
            val mediaRw = File("/mnt/media_rw")
            mediaRw.listFiles()?.forEach { child ->
                if (child.isDirectory && child.canRead()) {
                    // Heuristic: any non-empty directory under /mnt/media_rw
                    // that we can list is a mounted volume
                    result.add(child)
                }
            }
        }
        return result
    }
}
