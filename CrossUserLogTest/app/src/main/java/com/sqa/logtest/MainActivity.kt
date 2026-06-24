package com.sqa.logtest

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process as AndroidProcess
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnKpiStart: Button
    private lateinit var btnKpiStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo     = findViewById(R.id.tv_info)
        tvLog      = findViewById(R.id.tv_log)
        btnStart   = findViewById(R.id.btn_start)
        btnStop    = findViewById(R.id.btn_stop)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnKpiStart = findViewById(R.id.btn_kpi_start)
        btnKpiStop  = findViewById(R.id.btn_kpi_stop)

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

        btnKpiStart.setOnClickListener {
            startForegroundService(Intent(this, KpiEventService::class.java))
            refresh()
        }
        btnKpiStop.setOnClickListener {
            sendBroadcast(Intent(KpiEventService.ACTION_STOP).apply {
                `package` = packageName
            })
            stopService(Intent(this, KpiEventService::class.java))
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val myUid = AndroidProcess.myUid()
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val allFiles = outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val filteredFiles = allFiles.filter { it.name.contains("_filtered.txt") }
        val completeFiles = allFiles.filter { it.name.contains("_complete.txt") }
        val kpiFiles     = allFiles.filter { it.name.startsWith("kpi_events_") }

        tvInfo.text = buildString {
            appendLine("Process UID  : $myUid")
            appendLine("Expected     : 1000 (system)  →  " +
                    if (myUid == 1000) "OK ✓ cross-user access enabled"
                    else "NOT 1000 — platform signing required")
            appendLine("Target pkg   : ${LogCaptureService.TARGET_PKG}")
            appendLine("Output dir   : ${outDir.absolutePath}")
            appendLine("Filtered files : ${filteredFiles.size}  (${LogCaptureService.TARGET_PKG} logs only)")
            appendLine("Complete files : ${completeFiles.size}  (full logcat dump)")
            append(    "KPI event files: ${kpiFiles.size}")
        }

        val latestLog = filteredFiles.firstOrNull()
        val latestKpi = kpiFiles.firstOrNull()

        if (latestLog == null && latestKpi == null) {
            tvLog.text = "No log files yet.\nStart Log Service or KPI Logging above."
            return
        }

        tvLog.text = buildString {
            if (latestLog != null) {
                val matchingComplete = completeFiles.firstOrNull {
                    val ts = latestLog.name
                        .removePrefix("${LogCaptureService.TARGET_PKG}_")
                        .removeSuffix("_filtered.txt")
                    it.name.contains(ts)
                }
                appendLine("── FILTERED: ${latestLog.name}  (${latestLog.length() / 1024} KB) ──")
                if (matchingComplete != null)
                    appendLine("── COMPLETE: ${matchingComplete.name}  (${matchingComplete.length() / 1024} KB) ──")
                appendLine()
                val tail = latestLog.readLines().takeLast(30)
                appendLine("Last ${tail.size} lines of filtered log:")
                tail.forEach { appendLine(it) }
            }

            if (latestKpi != null) {
                if (latestLog != null) appendLine()
                appendLine("── KPI EVENTS: ${latestKpi.name}  (${latestKpi.length() / 1024} KB) ──")
                appendLine()
                val tail = latestKpi.readLines().takeLast(20)
                appendLine("Last ${tail.size} KPI events:")
                tail.forEach { appendLine(it) }
            }
        }
    }
}
