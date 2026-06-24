package com.sqa.logtest

import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvUidInfo: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnRun: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvUidInfo = findViewById(R.id.tv_uid_info)
        tvResult  = findViewById(R.id.tv_result)
        btnRun    = findViewById(R.id.btn_run)

        showUidInfo()

        btnRun.setOnClickListener {
            btnRun.isEnabled = false
            tvResult.text = "Running…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runTest() }
                tvResult.text = result
                btnRun.isEnabled = true
            }
        }
    }

    private fun showUidInfo() {
        val myUid    = Process.myUid()
        val myPid    = Process.myPid()
        val myUserId = myUid / 100_000
        tvUidInfo.text = buildString {
            appendLine("Process UID  : $myUid")
            appendLine("User profile : $myUserId  (0 = owner)")
            appendLine("PID          : $myPid")
            append(    "Expected     : ${if (myUid == 1000) "UID 1000 ✓ (system)" else "NOT 1000 — platform signing missing or sharedUserId not honoured"}")
        }
    }

    private fun runTest(): String = buildString {
        val myUid    = Process.myUid()
        val myUserId = myUid / 100_000

        appendLine("════════════════════════════════════")
        appendLine(" Cross-User Log Access Test")
        appendLine("════════════════════════════════════")
        appendLine("My UID     : $myUid")
        appendLine("My user ID : $myUserId")
        appendLine()

        // ── 1. Run logcat with UID column ────────────────────────────────────
        // -b all   : read all buffers (main, system, crash, radio, kernel)
        // -d       : dump and exit (non-blocking)
        // -v uid   : include UID column in each line
        // -t 1000  : last 1000 lines only
        val cmd = arrayOf("logcat", "-b", "all", "-d", "-v", "uid", "-t", "1000")
        appendLine("Command: ${cmd.joinToString(" ")}")
        appendLine()

        val lines: List<String>
        try {
            val proc = Runtime.getRuntime().exec(cmd)
            lines = proc.inputStream.bufferedReader().readLines()
            val err = proc.errorStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (err.isNotEmpty()) {
                appendLine("stderr: $err")
                appendLine()
            }
        } catch (e: Exception) {
            appendLine("EXCEPTION running logcat: ${e.message}")
            appendLine("→ Access denied or logcat not found.")
            return@buildString
        }

        appendLine("Lines read: ${lines.size}")
        appendLine()

        if (lines.isEmpty()) {
            appendLine("⚠ No lines returned — logcat may have been blocked.")
            return@buildString
        }

        // ── 2. Parse UIDs ────────────────────────────────────────────────────
        // logcat -v uid line format:
        //   MM-DD HH:MM:SS.mmm  uid   pid   tid  L tag  : msg
        // The UID field is the 3rd whitespace-separated token (index 2).
        val uidLineRe = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+\d+\s""")

        data class Entry(val uid: Int, val userId: Int, val line: String)

        val parsed = lines.mapNotNull { line ->
            val m = uidLineRe.find(line) ?: return@mapNotNull null
            val uid    = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val userId = uid / 100_000
            Entry(uid, userId, line)
        }

        val totalParsed  = parsed.size
        val uniqueUids   = parsed.map { it.uid }.toSortedSet()
        val crossUser    = parsed.filter { it.userId != myUserId }
        val crossUserIds = crossUser.map { it.userId }.toSortedSet()

        appendLine("Parsed entries : $totalParsed")
        appendLine("Unique UIDs    : ${uniqueUids.size}  → ${uniqueUids.take(20)}")
        appendLine()

        // ── 3. Verdict ───────────────────────────────────────────────────────
        if (crossUser.isEmpty()) {
            appendLine("╔══════════════════════════════════╗")
            appendLine("║  RESULT: FAIL — no cross-user    ║")
            appendLine("║  log entries received            ║")
            appendLine("╚══════════════════════════════════╝")
            appendLine()
            appendLine("All $totalParsed parsed entries belong to user profile $myUserId.")
            appendLine()
            appendLine("Diagnosis:")
            if (myUid != 1000) {
                appendLine("  • This process is running as UID $myUid, NOT 1000.")
                appendLine("  • The APK is likely NOT signed with the platform key.")
                appendLine("  • sharedUserId declaration was ignored by the package manager.")
            } else {
                appendLine("  • Process IS running as UID 1000 but still no cross-user entries.")
                appendLine("  • Secondary users may not be active / logged in.")
                appendLine("  • Or no apps are running in secondary profiles right now.")
            }
        } else {
            appendLine("╔══════════════════════════════════╗")
            appendLine("║  RESULT: PASS — cross-user logs  ║")
            appendLine("║  ARE accessible!                 ║")
            appendLine("╚══════════════════════════════════╝")
            appendLine()
            appendLine("Cross-user entries: ${crossUser.size}")
            appendLine("From user IDs     : $crossUserIds")
            appendLine()
            appendLine("── Sample cross-user entries (first 15) ──")
            crossUser.take(15).forEach { appendLine(it.line) }
        }

        // ── 4. UID breakdown ─────────────────────────────────────────────────
        appendLine()
        appendLine("── UID frequency breakdown ──")
        parsed.groupBy { it.uid }
            .entries
            .sortedByDescending { it.value.size }
            .take(20)
            .forEach { (uid, entries) ->
                val user = uid / 100_000
                appendLine("  UID %-8d  user=%-3d  count=%d".format(uid, user, entries.size))
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
