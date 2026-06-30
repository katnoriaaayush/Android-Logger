package com.sqa.logtest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * LogProvider — singleUser, user-0 window onto COMPLETED log segments.
 *
 * Only ever lists/opens `log-*.log`. `current.log` (the active writer file) is
 * never exposed. Guarded by the signature permission com.sqa.logtest.permission.LOGS
 * so only this app's UsbSyncService (running in any user) can reach it.
 *
 * Passes file descriptors, never paths — the PFD opened here in user-0 context
 * survives the cross-user Binder hop to the foreground-user sync worker.
 */
class LogProvider : ContentProvider() {

    companion object {
        const val TAG       = "LogProvider"
        const val AUTHORITY = "com.sqa.logtest.logs"

        const val COL_NAME = "name"
        const val COL_SIZE = "size"
        const val COL_MTIME = "last_modified"

        private fun isCompleted(name: String) =
            name.startsWith("log-") && name.endsWith(".log")
    }

    override fun onCreate(): Boolean = true

    /** Lists completed segments (oldest first) with name/size/mtime. */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val dir = LoggerService.logsDir(context!!)
        val files = dir.listFiles { f -> isCompleted(f.name) }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        val cursor = MatrixCursor(arrayOf(COL_NAME, COL_SIZE, COL_MTIME))
        for (f in files) cursor.addRow(arrayOf(f.name, f.length(), f.lastModified()))
        return cursor
    }

    /** Opens a completed segment read-only as a user-0 ParcelFileDescriptor. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "read-only provider" }
        val name = (uri.lastPathSegment ?: throw FileNotFoundException("no segment"))
            .substringAfterLast('/')
        require(isCompleted(name)) { "refused (not a completed segment): $name" }  // never current.log
        val f = File(LoggerService.logsDir(context!!), name)
        if (!f.exists()) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** call("rotate") — forces the user-0 logger to roll current.log so the
     *  newest data becomes a completed, syncable segment before a sync run. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "rotate") {
            val inst = LoggerService.instance
            inst?.forceRotate()
            Log.i(TAG, "rotate requested — logger ${if (inst != null) "rotated" else "not running"}")
            return Bundle().apply { putBoolean("rotated", inst != null) }
        }
        return null
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    // read-only provider — mutations unsupported
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
