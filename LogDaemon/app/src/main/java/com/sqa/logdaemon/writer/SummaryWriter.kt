package com.sqa.logdaemon.writer

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes _summary.tsv at the end of a session.
 *
 * Format (multi-table TSV — Excel handles blank rows as table separators):
 *
 *   metric    value
 *   session_start    ...
 *   session_end      ...
 *   duration_seconds ...
 *   total_entries    ...
 *
 *   package          level  count
 *   com.foo          D      8932
 *   com.foo          E      12
 *   ...
 *
 *   package          top_tag           count
 *   com.foo          NetworkManager    2104
 *   ...
 */
class SummaryWriter(private val file: File) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun write(
        startMillis: Long,
        endMillis: Long,
        stats: StatsCollector
    ) {
        BufferedWriter(FileWriter(file)).use { w ->
            // Table 1: overall metrics
            w.write("metric\tvalue\n")
            w.write("session_start\t${dateFormat.format(Date(startMillis))}\n")
            w.write("session_end\t${dateFormat.format(Date(endMillis))}\n")
            w.write("duration_seconds\t${(endMillis - startMillis) / 1000}\n")
            w.write("total_entries\t${stats.totalEntries()}\n")
            w.write("\n")

            // Table 2: per-package per-level
            w.write("package\tlevel\tcount\n")
            stats.perPackagePerLevel().forEach { (pkg, levels) ->
                // Stable order: V, D, I, W, E, A
                "VDIWEA".forEach { lvl ->
                    val count = levels[lvl] ?: 0L
                    if (count > 0L) w.write("$pkg\t$lvl\t$count\n")
                }
            }
            w.write("\n")

            // Table 3: top tags per package
            w.write("package\ttop_tag\tcount\n")
            stats.topTagsPerPackage(limit = 5).forEach { (pkg, top) ->
                top.forEach { (tag, count) ->
                    w.write("$pkg\t$tag\t$count\n")
                }
            }
        }
    }
}
