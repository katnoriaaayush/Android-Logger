package com.sqa.logdaemon.collector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single parsed log line.
 *
 * Note: logcat's default timestamp doesn't include the year. We capture
 * `wallClockMillis` at parse time so we can emit a full YYYY-MM-DD HH:MM:SS.mmm
 * timestamp for the TSV (Excel-friendly).
 */
data class LogEntry(
    val rawTimestamp: String,   // logcat's "MM-DD HH:MM:SS.mmm"
    val wallClockMillis: Long,  // System.currentTimeMillis() at parse time
    val pid: Int,
    val tid: Int,
    val level: Char,            // V / D / I / W / E / A
    val tag: String,
    val message: String
) {
    /**
     * Reconstructs the raw logcat threadtime line.
     * Used by RawLogWriter to keep the .log file format identical to what
     * developers see from `adb logcat`.
     */
    fun toRawLogLine(): String =
        "$rawTimestamp $pid $tid $level $tag: $message"

    /**
     * Produces a TSV-safe row. Tabs, newlines, CR, and nulls in the message
     * are replaced so we always get exactly one row per log entry.
     */
    fun toTsvRow(): String {
        val isoTs = ISO_DATE_FORMAT.get()!!.format(Date(wallClockMillis))
        val safeTag = sanitizeTsv(tag)
        val safeMessage = sanitizeTsv(message)
        return "$isoTs\t$pid\t$tid\t$level\t$safeTag\t$safeMessage"
    }

    companion object {
        const val TSV_HEADER = "timestamp\tpid\ttid\tlevel\ttag\tmessage"

        private val ISO_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

        private fun sanitizeTsv(s: String): String =
            s.replace("\u0000", "")     // drop nulls (Excel chokes on these)
                .replace("\t", "    ")  // tabs → 4 spaces (column safety)
                .replace("\r\n", "\\n") // CRLF → literal \n
                .replace("\n", "\\n")   // LF → literal \n
                .replace("\r", "")      // stray CR → drop
    }
}
