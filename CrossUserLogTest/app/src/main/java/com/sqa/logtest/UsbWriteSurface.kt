package com.sqa.logtest

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * Resolves a writable surface on the mounted removable (USB) volume.
 *
 * Phase decision: runtime-detect direct path first (a platform-signed system app
 * with WRITE_MEDIA_STORAGE can write /storage/<uuid> directly). SAF would be the
 * fallback but needs a persisted user grant, which an unattended logger can't
 * obtain on its own — so if no direct-writable removable volume is found we
 * report failure rather than silently doing nothing.
 */
class UsbWriteSurface private constructor(private val dir: File) {

    val label: String get() = dir.absolutePath

    fun openOutput(name: String): OutputStream = File(dir, name).outputStream()

    fun readText(name: String): String? =
        File(dir, name).let { if (it.exists()) it.readText() else null }

    fun writeText(name: String, text: String) = File(dir, name).writeText(text)

    companion object {
        const val TAG = "UsbWriteSurface"

        /** Lightweight startup-scan check: is any removable volume mounted right now?
         *  No write probe — use this to decide whether to kick UsbSyncService. */
        fun isRemovableMounted(ctx: Context): Boolean {
            val sm = ctx.getSystemService(StorageManager::class.java)
            return sm.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        }

        fun resolve(ctx: Context): UsbWriteSurface? {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (!v.isRemovable) continue
                if (v.state != Environment.MEDIA_MOUNTED) continue
                val d = volumeDir(v) ?: continue
                if (d.canWrite() || probeWritable(d)) {
                    Log.i(TAG, "removable volume writable at ${d.absolutePath}")
                    return UsbWriteSurface(d)
                }
                Log.w(TAG, "removable volume ${d.absolutePath} not writable")
            }
            return null
        }

        private fun volumeDir(v: StorageVolume): File? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                v.directory?.let { return it }
            }
            // API 29 fallback: hidden getPathFile()/getPath()
            return try {
                StorageVolume::class.java.getMethod("getPathFile").invoke(v) as? File
            } catch (_: Exception) {
                try {
                    File(StorageVolume::class.java.getMethod("getPath").invoke(v) as String)
                } catch (_: Exception) { null }
            }
        }

        private fun probeWritable(d: File): Boolean = try {
            val t = File(d, ".logsync_probe")
            t.writeText("x"); t.delete(); true
        } catch (_: Exception) { false }
    }
}
