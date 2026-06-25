package com.sqa.logtest

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnKpiStart: Button
    private lateinit var btnKpiStop: Button
    private lateinit var btnFetchUsage: Button

    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveRunnable: Runnable? = null
    private var kpiActive = false
    private var lastLogLength = -1   // detect new content to trigger auto-scroll

    companion object {
        const val LIVE_INTERVAL_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo        = findViewById(R.id.tv_info)
        tvLiveStatus  = findViewById(R.id.tv_live_status)
        tvLog         = findViewById(R.id.tv_log)
        scrollLog     = findViewById(R.id.scroll_log)
        btnStart      = findViewById(R.id.btn_start)
        btnStop       = findViewById(R.id.btn_stop)
        btnRefresh    = findViewById(R.id.btn_refresh)
        btnKpiStart   = findViewById(R.id.btn_kpi_start)
        btnKpiStop    = findViewById(R.id.btn_kpi_stop)
        btnFetchUsage = findViewById(R.id.btn_fetch_usage)

        btnStart.setOnClickListener {
            startForegroundService(Intent(this, LogCaptureService::class.java))
            refreshInfoPanel()
        }
        btnStop.setOnClickListener {
            sendBroadcast(Intent(LogCaptureService.ACTION_STOP).apply { `package` = packageName })
            stopService(Intent(this, LogCaptureService::class.java))
            refreshInfoPanel()
        }
        btnRefresh.setOnClickListener { refreshInfoPanel() }

        btnKpiStart.setOnClickListener {
            startForegroundService(Intent(this, KpiEventService::class.java))
            kpiActive = true
            refreshInfoPanel()
        }
        btnKpiStop.setOnClickListener {
            sendBroadcast(Intent(KpiEventService.ACTION_STOP).apply { `package` = packageName })
            stopService(Intent(this, KpiEventService::class.java))
            kpiActive = false
            refreshInfoPanel()
        }

        btnFetchUsage.setOnClickListener {
            btnFetchUsage.isEnabled = false
            btnFetchUsage.text = "Fetching…"
            Thread { fetchUsageStats() }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInfoPanel()
        startLivePreview()
    }

    override fun onPause() {
        super.onPause()
        stopLivePreview()
    }

    // ─── live preview ────────────────────────────────────────────────────────────

    private fun startLivePreview() {
        stopLivePreview()
        val r = object : Runnable {
            override fun run() {
                updateLiveView()
                liveHandler.postDelayed(this, LIVE_INTERVAL_MS)
            }
        }
        liveRunnable = r
        liveHandler.post(r)
    }

    private fun stopLivePreview() {
        liveRunnable?.let { liveHandler.removeCallbacks(it) }
        liveRunnable = null
    }

    private fun updateLiveView() {
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val allFiles = outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val latestKpi   = allFiles.firstOrNull { it.name.startsWith("kpi_events_") }
        val latestUsage = allFiles.firstOrNull { it.name.startsWith("usage_stats_") }

        // Status badge
        if (kpiActive && latestKpi != null) {
            val eventCount = latestKpi.readLines().count { !it.startsWith("#") && it.isNotBlank() }
            val age = ((System.currentTimeMillis() - latestKpi.lastModified()) / 1000).coerceAtLeast(0)
            tvLiveStatus.text = "●  LIVE  —  $eventCount events  |  file updated ${age}s ago"
            tvLiveStatus.setTextColor(0xFF4CAF50.toInt())
        } else if (kpiActive) {
            tvLiveStatus.text = "●  LIVE  —  waiting for first event…"
            tvLiveStatus.setTextColor(0xFF4CAF50.toInt())
        } else if (latestKpi != null) {
            val eventCount = latestKpi.readLines().count { !it.startsWith("#") && it.isNotBlank() }
            tvLiveStatus.text = "◌  stopped  —  last session: $eventCount events  (${latestKpi.name})"
            tvLiveStatus.setTextColor(0xFF9E9E9E.toInt())
        } else {
            tvLiveStatus.text = "◌  KPI service not running"
            tvLiveStatus.setTextColor(0xFF9E9E9E.toInt())
        }

        // Log content
        if (latestKpi == null && latestUsage == null) {
            tvLog.text = "No data yet.\nStart KPI Logging or tap Fetch App Usage Stats."
            lastLogLength = -1
            return
        }

        val newText = buildString {
            if (latestKpi != null) {
                val dataLines = latestKpi.readLines().filter { !it.startsWith("#") && it.isNotBlank() }
                appendLine("KPI EVENTS  ·  ${latestKpi.name}  ·  ${dataLines.size} events")
                appendLine("─".repeat(70))
                dataLines.takeLast(80).forEach { appendLine(it) }
            }

            if (latestUsage != null) {
                if (latestKpi != null) appendLine()
                val dataLines = latestUsage.readLines().filter { !it.startsWith("#") && it.isNotBlank() }
                appendLine("APP USAGE  ·  ${latestUsage.name}  ·  ${dataLines.size} apps")
                appendLine("─".repeat(70))
                dataLines.take(30).forEach { appendLine(it) }
                if (dataLines.size > 30) appendLine("  … ${dataLines.size - 30} more apps in file")
            }
        }

        val newLength = newText.length
        tvLog.text = newText

        // Auto-scroll to bottom when KPI is live and new events have arrived
        if (kpiActive && newLength != lastLogLength) {
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        lastLogLength = newLength
    }

    // ─── app usage stats ─────────────────────────────────────────────────────────

    private fun fetchUsageStats() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7 * day, now)
            ?.filter { it.totalTimeInForeground > 0 }
            ?.sortedByDescending { it.totalTimeInForeground }
            ?: emptyList()

        val launchCounts = mutableMapOf<String, Int>()
        val usageEvents = usm.queryEvents(now - day, now)
        val ev = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                launchCounts[ev.packageName] = (launchCounts[ev.packageName] ?: 0) + 1
            }
        }

        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(outDir, "usage_stats_$ts.txt")

        PrintWriter(file.bufferedWriter()).use { w ->
            w.println("# App Usage Stats — captured $ts")
            w.println("# Runtime  : last 7 days (INTERVAL_DAILY)")
            w.println("# Launches : ACTIVITY_RESUMED events in last 24 hours")
            w.println("# Format   : rank | package | runtime_7d | launches_24h | last_used")
            w.println("# ─────────────────────────────────────────────────────────────────")
            stats.forEachIndexed { i, stat ->
                val runtime  = formatDuration(stat.totalTimeInForeground)
                val launches = launchCounts[stat.packageName] ?: 0
                val lastUsed = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stat.lastTimeUsed))
                w.println("${(i + 1).toString().padStart(3)} | ${stat.packageName} | $runtime | ${launches}x | $lastUsed")
            }
        }

        // File written — live loop picks it up on next tick; just reset scroll + toast
        runOnUiThread {
            lastLogLength = -1   // force scroll-to-top on next live tick
            btnFetchUsage.isEnabled = true
            btnFetchUsage.text = "Fetch App Usage Stats (frequency + runtime)"
            refreshInfoPanel()
            Toast.makeText(this, "Saved ${stats.size} apps → ${file.name}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    // ─── info panel ──────────────────────────────────────────────────────────────

    private fun refreshInfoPanel() {
        val myUid = AndroidProcess.myUid()
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val allFiles = outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        tvInfo.text = buildString {
            appendLine("Process UID  : $myUid  →  " +
                    if (myUid == 1000) "OK ✓ system" else "NOT 1000 — platform signing required")
            appendLine("Target pkg   : ${LogCaptureService.TARGET_PKG}")
            appendLine("Output dir   : ${outDir.absolutePath}")
            appendLine("Filtered : ${allFiles.count { it.name.contains("_filtered.txt") }}  " +
                       "Complete : ${allFiles.count { it.name.contains("_complete.txt") }}")
            append(    "KPI files: ${allFiles.count { it.name.startsWith("kpi_events_") }}  " +
                       "Usage files: ${allFiles.count { it.name.startsWith("usage_stats_") }}")
        }
    }
}
