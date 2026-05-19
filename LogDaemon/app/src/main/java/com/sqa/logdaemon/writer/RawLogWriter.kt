package com.sqa.logdaemon.writer

import com.sqa.logdaemon.collector.LogEntry
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Writes log entries to a raw .log file in the same format developers
 * see from `adb logcat -v threadtime`.
 *
 * Thread-safety: not thread-safe; expect one writer per file per thread.
 */
class RawLogWriter(file: File) : LogWriter {

    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)
    private var entryCount: Long = 0

    @Throws(IOException::class)
    override fun write(entry: LogEntry) {
        writer.write(entry.toRawLogLine())
        writer.newLine()
        entryCount++

        // Flush periodically so partial logs survive sudden USB pulls.
        if (entryCount % FLUSH_INTERVAL == 0L) writer.flush()
    }

    @Throws(IOException::class)
    override fun flush() = writer.flush()

    override fun close() {
        try { writer.flush() } catch (_: IOException) {}
        try { writer.close() } catch (_: IOException) {}
    }

    override fun entryCount(): Long = entryCount

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        private const val FLUSH_INTERVAL = 5L     // flush every 5 entries to survive abrupt USB removal
    }
}

interface LogWriter : AutoCloseable {
    fun write(entry: LogEntry)
    fun flush()
    fun entryCount(): Long
}
