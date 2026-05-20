package com.sqa.logdaemon.config

import android.util.Log
import java.io.File

/**
 * Parses log.sinfo files.
 *
 * Returns ParseResult with either a valid config or a list of errors.
 * Designed to be permissive: unknown keys are ignored, comments are
 * allowed (#, ;), blank lines are skipped, leading/trailing whitespace
 * is trimmed.
 */
class ConfigParser {

    sealed class ParseResult {
        data class Success(val config: DaemonConfig, val warnings: List<String>) : ParseResult()
        data class Failure(val errors: List<String>) : ParseResult()
    }

    fun parse(file: File): ParseResult {
        if (!file.exists()) {
            return ParseResult.Failure(listOf("Config file not found: ${file.absolutePath}"))
        }
        return try {
            parseLines(file.readLines())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config", e)
            ParseResult.Failure(listOf("Failed to read config: ${e.message}"))
        }
    }

    fun parseLines(lines: List<String>): ParseResult {
        val packages = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val options = mutableMapOf<String, String>()
        var currentSection = ""

        lines.forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            // Skip blanks and comments
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed

            // Section header
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                return@forEachIndexed
            }

            when (currentSection) {
                "packages" -> {
                    // One package per line, optionally with "= true/false" to disable
                    val pkg = line.substringBefore("=").trim()
                    if (isValidPackageName(pkg)) {
                        packages.add(pkg)
                    } else {
                        warnings.add("Line ${idx + 1}: invalid package name '$pkg'")
                    }
                }
                "options" -> {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx <= 0) {
                        warnings.add("Line ${idx + 1}: malformed option '$line' (expected key=value)")
                    } else {
                        val key = line.substring(0, eqIdx).trim().lowercase()
                        val value = line.substring(eqIdx + 1).trim()
                        options[key] = value
                    }
                }
                "" -> warnings.add("Line ${idx + 1}: content before any [section] header — ignored")
                else -> { /* unknown section, ignore for forward compatibility */ }
            }
        }

        if (packages.isEmpty()) {
            return ParseResult.Failure(listOf("No packages found in [packages] section"))
        }

        val config = DaemonConfig(
            packages = packages.distinct(),
            minLevel = parseLevel(options["min_level"]) ?: 'D',
            includeSystemLogs = options["include_system_logs"]?.equals("true", ignoreCase = true) ?: false,
            bufferSizeKb = options["buffer_size_kb"]?.toIntOrNull() ?: 4096
        )
        return ParseResult.Success(config, warnings)
    }

    private fun isValidPackageName(s: String): Boolean =
        s.isNotEmpty() && s.matches(PACKAGE_NAME_REGEX)

    private fun parseLevel(s: String?): Char? {
        if (s.isNullOrBlank()) return null
        return when (s.trim().uppercase()) {
            "V", "VERBOSE" -> 'V'
            "D", "DEBUG"   -> 'D'
            "I", "INFO"    -> 'I'
            "W", "WARN", "WARNING" -> 'W'
            "E", "ERROR"   -> 'E'
            else -> null
        }
    }

    companion object {
        private const val TAG = "LogDaemon.Config"
        // Standard Android package name: dot-separated identifiers
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
