package com.sqa.logdaemon.writer

import com.sqa.logdaemon.collector.LogEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-package statistics during a session for _summary.tsv.
 *
 * Thread-safe: backed by ConcurrentHashMap so collectors for different
 * packages can update concurrently.
 */
class StatsCollector {

    /** packageName -> (level -> count) */
    private val levelCounts = ConcurrentHashMap<String, ConcurrentHashMap<Char, Long>>()

    /** packageName -> (tag -> count) */
    private val tagCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** packageName -> total entries */
    private val totalCounts = ConcurrentHashMap<String, Long>()

    fun record(packageName: String, entry: LogEntry) {
        levelCounts
            .computeIfAbsent(packageName) { ConcurrentHashMap() }
            .merge(entry.level, 1L) { old, _ -> old + 1 }

        tagCounts
            .computeIfAbsent(packageName) { ConcurrentHashMap() }
            .merge(entry.tag, 1L) { old, _ -> old + 1 }

        totalCounts.merge(packageName, 1L) { old, _ -> old + 1 }
    }

    fun totalEntries(): Long = totalCounts.values.sum()

    fun perPackageTotal(): Map<String, Long> = totalCounts.toMap()

    fun perPackagePerLevel(): Map<String, Map<Char, Long>> =
        levelCounts.mapValues { it.value.toMap() }

    /** Top N tags per package, sorted by count desc. */
    fun topTagsPerPackage(limit: Int = 5): Map<String, List<Pair<String, Long>>> =
        tagCounts.mapValues { (_, tagMap) ->
            tagMap.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key to it.value }
        }
}
