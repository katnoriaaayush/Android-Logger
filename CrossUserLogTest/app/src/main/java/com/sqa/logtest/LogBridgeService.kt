package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase-1 LogBridge service — the Android-side peer of the native logbridged.
 *
 * Lifecycle:
 *   • Declared singleUser + persistent + directBootAware (see manifest), so it
 *     always runs in user 0 and survives kills.
 *   • Started by the native daemon via `am start-foreground-service` once a USB
 *     volume with log.sinfo is detected.
 *   • On start it connects to the daemon's Unix domain socket and runs the
 *     two-way handshake, then answers keepalive PINGs.
 *
 * This phase only proves bidirectional comms; later phases replace the
 * handshake body with real log capture + encrypted streaming back to the daemon.
 */
class LogBridgeService : Service() {

    companion object {
        const val TAG          = "LogBridgeService"
        const val SOCKET_NAME  = "logbridge"   // RESERVED namespace → /dev/socket/logbridge
        const val CHANNEL_ID   = "log_bridge"
        const val NOTIF_ID     = 3
        const val ACTION_STOP  = "com.sqa.logtest.BRIDGE_STOP"

        // frame types — must match logbridged.c
        const val T_SESSION_CONFIG = 0x01
        const val T_ACK            = 0x02
        const val T_PING           = 0x03
        const val T_PONG           = 0x04
        const val T_BYE            = 0x05

        const val CONNECT_RETRIES  = 12
        const val CONNECT_DELAY_MS = 500L
        const val MAX_FRAME        = 16 * 1024 * 1024
    }

    @Volatile private var running = false
    private var ioThread: Thread? = null
    private var logFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("LogBridge starting…"))
        openLogFile()
        fileLog("onCreate uid=${android.os.Process.myUid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val session = intent?.getStringExtra("session") ?: "(none)"
        fileLog("onStartCommand session=$session")
        if (!running) {
            running = true
            ioThread = Thread({ connectAndServe() }, "logbridge-io").also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        ioThread?.interrupt()
        fileLog("onDestroy")
    }

    // ─── socket client + handshake ───────────────────────────────────────────

    private fun connectAndServe() {
        val socket = connectWithRetry()
        if (socket == null) {
            fileLog("FAILED to connect to daemon socket '$SOCKET_NAME' after $CONNECT_RETRIES tries")
            updateNotification("daemon socket unreachable")
            return
        }
        fileLog("connected to daemon socket '$SOCKET_NAME'")
        updateNotification("connected to daemon")

        try {
            val inp = socket.inputStream
            val out = socket.outputStream
            while (running && !Thread.currentThread().isInterrupted) {
                val frame = readFrame(inp) ?: break
                val (type, payload) = frame
                when (type) {
                    T_SESSION_CONFIG -> {
                        val cfg = String(payload, Charsets.UTF_8)
                        fileLog("← SESSION_CONFIG (${payload.size} bytes):\n$cfg")
                        val ack = ("LogBridgeService up; uid=${android.os.Process.myUid()}; " +
                                "received ${payload.size} bytes of sinfo").toByteArray()
                        writeFrame(out, T_ACK, ack)
                        fileLog("→ ACK sent")
                        updateNotification("session configured (${payload.size}B)")
                    }
                    T_PING -> {
                        val txt = String(payload, Charsets.UTF_8)
                        fileLog("← PING: $txt")
                        writeFrame(out, T_PONG, "pong:$txt".toByteArray())
                        fileLog("→ PONG sent")
                    }
                    T_BYE -> { fileLog("← BYE — closing"); break }
                    else  -> fileLog("← unknown frame type=$type len=${payload.size}")
                }
            }
        } catch (e: Exception) {
            fileLog("io error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            fileLog("connection closed")
            updateNotification("disconnected")
        }
    }

    private fun connectWithRetry(): LocalSocket? {
        repeat(CONNECT_RETRIES) { attempt ->
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED))
                return s
            } catch (e: Exception) {
                fileLog("connect attempt ${attempt + 1}/$CONNECT_RETRIES failed: ${e.message}")
                try { Thread.sleep(CONNECT_DELAY_MS) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    // ─── framing: [4-byte BE type][4-byte BE length][payload] ────────────────

    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        val hdr = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        hdr.putInt(type)
        hdr.putInt(payload.size)
        out.write(hdr.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readFrame(inp: InputStream): Pair<Int, ByteArray>? {
        val hdr = readN(inp, 8) ?: return null
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
        val type = bb.int
        val len  = bb.int
        if (len < 0 || len > MAX_FRAME) { fileLog("bad frame length=$len"); return null }
        val payload = if (len > 0) readN(inp, len) ?: return null else ByteArray(0)
        return type to payload
    }

    private fun readN(inp: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    // ─── file logging for visibility ─────────────────────────────────────────

    private fun openLogFile() {
        val dir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        logFile = File(dir, "logbridge_$ts.txt")
        fileLog("=== LogBridgeService log ${Date()} ===")
    }

    @Synchronized
    private fun fileLog(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, msg)
        try { logFile?.appendText("$ts  $msg\n") } catch (_: Exception) {}
    }

    // ─── notification ────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Log Bridge", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LogBridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
}
