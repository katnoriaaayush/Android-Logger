/**
 * Streams the whole system logcat and writes every line encrypted.
 *
 * IMPORTANT: reading OTHER apps' logs ("logcat all") requires the READ_LOGS
 * permission, which is signature|privileged and only granted to system/
 * platform-signed apps. A normal app only sees its own process's logs.
 * (This is fine for a platform app like the teaching board.)
 */
class LogcatCollector(private val writer: EncryptedLogWriter) {

    @Volatile private var process: Process? = null

    fun start() {
        // -v threadtime = readable format; no "-d" so it keeps streaming.
        val p = ProcessBuilder("logcat", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        process = p
        Thread({
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { writer.writeLine(it) }
            }
        }, "logcat-encrypt").apply { isDaemon = true; start() }
    }

    fun stop() {
        process?.destroy()
        writer.close()
    }
}

/*
 * Wiring it up (e.g. in a foreground Service or on app start):
 *
 *   val pem  = context.assets.open("public_key.pem").bufferedReader().readText()
 *   val pub  = EncryptedLogWriter.loadPublicKey(pem)
 *   val file = File(context.filesDir, "logs/session-${System.currentTimeMillis()}.elog")
 *   val writer    = EncryptedLogWriter(pub, file)
 *   val collector = LogcatCollector(writer)
 *   collector.start()
 *   // ... later: collector.stop()
 *
 * context.filesDir is internal storage: /data/data/<your.package>/files/
 */
