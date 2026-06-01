package com.sqa.logdaemon

import android.app.Application
import android.util.Log

class LogDaemonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LogDaemonApp.onCreate (system app)")
    }

    companion object {
        const val TAG = "LogDaemon"
    }
}
