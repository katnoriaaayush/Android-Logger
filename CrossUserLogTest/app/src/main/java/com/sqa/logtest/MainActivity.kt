package com.sqa.logtest

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process as AndroidProcess
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo    = findViewById(R.id.tv_info)
        tvLog     = findViewById(R.id.tv_log)
        btnStart  = findViewById(R.id.btn_start)
        btnStop   = findViewById(R.id.btn_stop)
        btnRefresh = findViewById(R.id.btn_refresh)

        btnStart.setOnClickListener {
            startForegroundService(Intent(this, LogCaptureService::class.java))
            refresh()
        }
        btnStop.setOnClickListener {
            sendBroadcast(Intent(LogCaptureService.ACTION_STOP).apply {
                `package` = packageName
            })
            stopService(Intent(this, LogCaptureService::class.java))
            refresh()
        }
        btnRefresh.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val myUid = AndroidProcess.myUid()
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val files = outDir.listFiles()
            ?.filter { it.name.startsWith(LogCaptureService.TARGET_PKG) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        tvInfo.text = buildString {
            appendLine("Process UID  : $myUid")
            appendLine("Expected     : 1000 (system)  →  " +
                    if (myUid == 1000) "OK ✓ cross-user access enabled"
                    else "NOT 1000 — platform signing required")
            appendLine("Target pkg   : ${LogCaptureService.TARGET_PKG}")
            appendLine("Output dir   : ${outDir.absolutePath}")
            append(    "Log files    : ${files.size} found")
        }

        if (files.isEmpty()) {
            tvLog.text = "No log files yet.\nStart the service and wait for the target package to emit logs."
            return
        }

        val latest = files.first()
        val tail = latest.readLines().takeLast(50)
        tvLog.text = buildString {
            appendLine("── ${latest.name}  (${latest.length() / 1024} KB) ──")
            appendLine("Showing last ${tail.size} lines:")
            appendLine()
            tail.forEach { appendLine(it) }
        }
    }
}
