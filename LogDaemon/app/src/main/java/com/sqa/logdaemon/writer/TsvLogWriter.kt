package com.sqa.logdaemon.writer

import com.sqa.logdaemon.collector.LogEntry
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Writes log entries to an Excel-importable TSV file.
 * The first line is always the header row.
 */
class TsvLogWriter(file: File) : LogWriter {

    private val writer: BufferedWriter
    private var entryCount: Long = 0

    init {
        val isNewFile = !file.exists() || file.length() == 0L
        writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)
        if (isNewFile) {
            writer.write(LogEntry.TSV_HEADER)
            writer.newLine()
            writer.flush()
        }
    }

    @Throws(IOException::class)
    override fun write(entry: LogEntry) {
        writer.write(entry.toTsvRow())
        writer.newLine()
        entryCount++
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
