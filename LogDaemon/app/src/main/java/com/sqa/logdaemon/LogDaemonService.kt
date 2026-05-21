package com.sqa.logdaemon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * One-shot launcher for the native helper daemon.
 *
 * Checks whether liblogdaemon_helper.so is already running by scanning
 * /proc/*/cmdline. If not, launches it. The helper double-forks and setsid()s
 * to detach from this process group, so it outlives this service and any
 * subsequent profile-switch kills. stopSelf() is called immediately after
 * launch — this service has nothing further to do.
 */
class LogDaemonService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LogDaemonService: checking helper")
        if (isHelperAlive()) {
            Log.i(TAG, "Helper already running, nothing to do")
        } else {
            launchHelper()
        }
        stopSelf()
    }

    private fun isHelperAlive(): Boolean {
        val procDir = File("/proc")
        return procDir.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.any { dir ->
                try {
                    File(dir, "cmdline").readBytes()
                        .toString(Charsets.UTF_8)
                        .contains(HELPER_LIB_NAME)
                } catch (_: Exception) { false }
            } ?: false
    }

    private fun launchHelper() {
        val helperFile = File(applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
        if (!helperFile.exists() || !helperFile.canExecute()) {
            Log.e(TAG, "Helper not found or not executable: ${helperFile.absolutePath}")
            return
        }
        try {
            ProcessBuilder(helperFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            Log.i(TAG, "Helper launched from ${helperFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch helper", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG             = "LogDaemon.Service"
        private const val HELPER_LIB_NAME = "liblogdaemon_helper.so"
    }
}
