package com.sqa.logdaemon.writer

import android.os.Build
import com.sqa.logdaemon.config.DaemonConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes _session.meta — a human + machine-readable record of what
 * was captured, on what device, and the state of each requested package.
 */
class SessionMetaWriter(private val file: File) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun write(
        startMillis: Long,
        configPath: String,
        config: DaemonConfig,
        configWarnings: List<String>,
        packageStatus: Map<String, PackageStatus>
    ) {
        BufferedWriter(FileWriter(file)).use { w ->
            w.write("[session]\n")
            w.write("start_time=${dateFormat.format(Date(startMillis))}\n")
            w.write("device_model=${Build.MODEL}\n")
            w.write("manufacturer=${Build.MANUFACTURER}\n")
            w.write("android_version=${Build.VERSION.RELEASE}\n")
            w.write("sdk_int=${Build.VERSION.SDK_INT}\n")
            w.write("build_fingerprint=${Build.FINGERPRINT}\n")
            w.write("serial=${getSerial()}\n")
            w.write("\n")

            w.write("[config]\n")
            w.write("source=$configPath\n")
            w.write("min_level=${config.minLevel}\n")
            w.write("include_system_logs=${config.includeSystemLogs}\n")
            w.write("buffer_size_kb=${config.bufferSizeKb}\n")
            w.write("\n")

            if (configWarnings.isNotEmpty()) {
                w.write("[warnings]\n")
                configWarnings.forEachIndexed { i, msg ->
                    w.write("warning_${i + 1}=$msg\n")
                }
                w.write("\n")
            }

            w.write("[packages]\n")
            packageStatus.forEach { (pkg, status) ->
                val value = when (status) {
                    is PackageStatus.Found -> "found (uid=${status.uid})"
                    PackageStatus.NotInstalled -> "NOT_INSTALLED"
                    is PackageStatus.Error -> "ERROR: ${status.reason}"
                }
                w.write("$pkg=$value\n")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getSerial(): String = try {
        Build.getSerial()
    } catch (_: SecurityException) {
        Build.SERIAL ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

sealed class PackageStatus {
    data class Found(val uid: Int) : PackageStatus()
    object NotInstalled : PackageStatus()
    data class Error(val reason: String) : PackageStatus()
}
