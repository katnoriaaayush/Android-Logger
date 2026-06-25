package com.sqa.logtest

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process as AndroidProcess
import android.widget.Button
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
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnKpiStart: Button
    private lateinit var btnKpiStop: Button
    private lateinit var btnFetchUsage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo        = findViewById(R.id.tv_info)
        tvLog         = findViewById(R.id.tv_log)
        btnStart      = findViewById(R.id.btn_start)
        btnStop       = findViewById(R.id.btn_stop)
        btnRefresh    = findViewById(R.id.btn_refresh)
        btnKpiStart   = findViewById(R.id.btn_kpi_start)
        btnKpiStop    = findViewById(R.id.btn_kpi_stop)
        btnFetchUsage = findViewById(R.id.btn_fetch_usage)

        btnStart.setOnClickListener {
            startForegroundService(Intent(this, LogCaptureService::class.java))
            refresh()
        }
        btnStop.setOnClickListener {
            sendBroadcast(Intent(LogCaptureService.ACTION_STOP).apply { `package` = packageName })
            stopService(Intent(this, LogCaptureService::class.java))
            refresh()
        }
        btnRefresh.setOnClickListener { refresh() }

        btnKpiStart.setOnClickListener {
            startForegroundService(Intent(this, KpiEventService::class.java))
            refresh()
        }
        btnKpiStop.setOnClickListener {
            sendBroadcast(Intent(KpiEventService.ACTION_STOP).apply { `package` = packageName })
            stopService(Intent(this, KpiEventService::class.java))
            refresh()
        }

        btnFetchUsage.setOnClickListener {
            btnFetchUsage.isEnabled = false
            btnFetchUsage.text = "Fetching…"
            Thread { fetchUsageStats() }.start()
        }
    }

    // ─── app usage stats ────────────────────────────────────────────────────────

    private fun fetchUsageStats() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        // Aggregate runtime over the last 7 days, one row per app
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7 * day, now)
            ?.filter { it.totalTimeInForeground > 0 }
            ?.sortedByDescending { it.totalTimeInForeground }
            ?: emptyList()

        // Launch count = ACTIVITY_RESUMED events in the last 24 hours
        val launchCounts = mutableMapOf<String, Int>()
        val usageEvents = usm.queryEvents(now - day, now)
        val ev = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                launchCounts[ev.packageName] = (launchCounts[ev.packageName] ?: 0) + 1
            }
        }

        // Write to file
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(outDir, "usage_stats_$ts.txt")

        PrintWriter(file.bufferedWriter()).use { w ->
            w.println("# App Usage Stats — captured $ts")
            w.println("# Runtime  : last 7 days (INTERVAL_DAILY)")
            w.println("# Launches : ACTIVITY_RESUMED events in last 24 hours")
            w.println("# Format   : rank | package | runtime_7d | launches_24h | last_used")
            w.println("# ──────────────────────────────────────────────────────────────────")
            stats.forEachIndexed { i, stat ->
                val runtime  = formatDuration(stat.totalTimeInForeground)
                val launches = launchCounts[stat.packageName] ?: 0
                val lastUsed = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stat.lastTimeUsed))
                w.println("${(i + 1).toString().padStart(3)} | ${stat.packageName} | $runtime | ${launches}x | $lastUsed")
            }
        }

        // Update UI on main thread
        val summary = buildString {
            appendLine("── USAGE STATS: ${file.name} ──")
            appendLine("${stats.size} apps  |  runtime=7d  |  launches=24h")
            appendLine()
            appendLine("${"#".padStart(3)}  ${"Package".padEnd(40)}  ${"Runtime".padEnd(10)}  ${"Launches".padEnd(8)}  Last used")
            appendLine("─".repeat(80))
            stats.take(40).forEachIndexed { i, stat ->
                val runtime  = formatDuration(stat.totalTimeInForeground)
                val launches = launchCounts[stat.packageName] ?: 0
                val lastUsed = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stat.lastTimeUsed))
                val pkg = stat.packageName.let { if (it.length > 40) "…${it.takeLast(39)}" else it }
                appendLine("${(i + 1).toString().padStart(3)}  ${pkg.padEnd(40)}  ${runtime.padEnd(10)}  ${launches.toString().padEnd(8)}  $lastUsed")
            }
            if (stats.size > 40) appendLine("… and ${stats.size - 40} more apps written to file")
        }

        runOnUiThread {
            tvLog.text = summary
            btnFetchUsage.isEnabled = true
            btnFetchUsage.text = "Fetch App Usage Stats (frequency + runtime)"
            refreshInfoPanel()
            Toast.makeText(this, "Usage stats saved: ${file.name}", Toast.LENGTH_LONG).show()
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

    // ─── refresh ────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        refreshInfoPanel()

        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val allFiles = outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val filteredFiles = allFiles.filter { it.name.contains("_filtered.txt") }
        val completeFiles = allFiles.filter { it.name.contains("_complete.txt") }
        val kpiFiles      = allFiles.filter { it.name.startsWith("kpi_events_") }
        val usageFiles    = allFiles.filter { it.name.startsWith("usage_stats_") }

        val latestLog   = filteredFiles.firstOrNull()
        val latestKpi   = kpiFiles.firstOrNull()
        val latestUsage = usageFiles.firstOrNull()

        if (latestLog == null && latestKpi == null && latestUsage == null) {
            tvLog.text = "No log files yet.\nUse the buttons above to start capturing."
            return
        }

        tvLog.text = buildString {
            if (latestLog != null) {
                val matchingComplete = completeFiles.firstOrNull {
                    val t = latestLog.name
                        .removePrefix("${LogCaptureService.TARGET_PKG}_")
                        .removeSuffix("_filtered.txt")
                    it.name.contains(t)
                }
                appendLine("── FILTERED: ${latestLog.name}  (${latestLog.length() / 1024} KB) ──")
                if (matchingComplete != null)
                    appendLine("── COMPLETE: ${matchingComplete.name}  (${matchingComplete.length() / 1024} KB) ──")
                appendLine()
                val tail = latestLog.readLines().takeLast(25)
                appendLine("Last ${tail.size} lines of filtered log:")
                tail.forEach { appendLine(it) }
            }

            if (latestKpi != null) {
                if (latestLog != null) appendLine()
                appendLine("── KPI EVENTS: ${latestKpi.name}  (${latestKpi.length() / 1024} KB) ──")
                appendLine()
                val tail = latestKpi.readLines().takeLast(15)
                appendLine("Last ${tail.size} KPI events:")
                tail.forEach { appendLine(it) }
            }

            if (latestUsage != null) {
                if (latestLog != null || latestKpi != null) appendLine()
                appendLine("── USAGE STATS: ${latestUsage.name}  (${latestUsage.length() / 1024} KB) ──")
                appendLine()
                val tail = latestUsage.readLines().drop(5).take(20)
                appendLine("Top apps (last capture):")
                tail.forEach { appendLine(it) }
            }
        }
    }

    private fun refreshInfoPanel() {
        val myUid = AndroidProcess.myUid()
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        val allFiles = outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        tvInfo.text = buildString {
            appendLine("Process UID  : $myUid  →  " +
                    if (myUid == 1000) "OK ✓ system" else "NOT 1000 — platform signing required")
            appendLine("Target pkg   : ${LogCaptureService.TARGET_PKG}")
            appendLine("Output dir   : ${outDir.absolutePath}")
            appendLine("Filtered     : ${allFiles.count { it.name.contains("_filtered.txt") }}  |  " +
                       "Complete: ${allFiles.count { it.name.contains("_complete.txt") }}")
            appendLine("KPI events   : ${allFiles.count { it.name.startsWith("kpi_events_") }}  |  " +
                       "Usage stats: ${allFiles.count { it.name.startsWith("usage_stats_") }}")
        }
    }
}
