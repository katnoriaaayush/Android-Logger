package com.sqa.logdaemon.collector

/**
 * Parses logcat -v threadtime lines into LogEntry objects.
 *
 * threadtime format:
 *   MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG: message
 *
 * Example:
 *   05-19 14:32:15.123  1234  5678 D MyTag: Hello
 *
 * Note: multi-line continuation lines (stack traces) come through as
 * separate log lines from logcat — each one has its own header. So
 * "one row per parsed line" matches what logcat itself emits.
 */
object LogcatLineParser {

    private val PATTERN = Regex(
        """^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s(.*)$"""
    )

    fun parse(line: String): LogEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val match = PATTERN.find(trimmed) ?: return null
        val (rawTs, pid, tid, level, tag, message) = match.destructured
        return try {
            LogEntry(
                rawTimestamp = rawTs,
                wallClockMillis = System.currentTimeMillis(),
                pid = pid.toInt(),
                tid = tid.toInt(),
                level = level[0],
                tag = tag.trim(),
                message = message
            )
        } catch (_: Exception) {
            null
        }
    }
}
