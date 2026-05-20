package com.sqa.logdaemon.config

/**
 * Parsed contents of log.sinfo.
 *
 * The config file is INI-style. Example:
 *
 *   # Log Collector Config
 *   [packages]
 *   com.company.mainapp
 *   com.company.launcher
 *
 *   [options]
 *   min_level=D
 *   include_system_logs=false
 *
 * Unknown keys/sections are ignored (forward-compatible).
 */
data class DaemonConfig(
    val packages: List<String>,
    val minLevel: Char = 'D',
    val includeSystemLogs: Boolean = false,
    val bufferSizeKb: Int = 4096
) {
    fun isValid(): Boolean = packages.isNotEmpty()
}
