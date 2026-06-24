package com.sqa.logtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KpiEventService : Service() {

    companion object {
        const val TAG = "KpiEventService"
        const val CHANNEL_ID = "kpi_events"
        const val NOTIF_ID = 2
        const val ACTION_STOP = "com.sqa.logtest.KPI_STOP"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var writer: PrintWriter? = null
    private var eventCount = 0

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON              -> log("SCREEN_ON", "Screen turned on")
                Intent.ACTION_SCREEN_OFF             -> log("SCREEN_OFF", "Screen turned off")
                Intent.ACTION_SHUTDOWN               -> log("POWER_OFF", "Device shutting down")
                Intent.ACTION_PACKAGE_ADDED          -> log("PKG_INSTALL", "Package installed: ${intent.data?.schemeSpecificPart}")
                Intent.ACTION_PACKAGE_REMOVED        -> log("PKG_REMOVE", "Package removed: ${intent.data?.schemeSpecificPart}")
                Intent.ACTION_LOCALE_CHANGED         -> {
                    val locale = resources.configuration.locales[0]
                    log("LOCALE_CHANGE", "System locale changed to: $locale")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    log("USB_ATTACH", "USB device attached: ${device?.deviceName ?: "unknown"} (vid=${device?.vendorId} pid=${device?.productId})")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    log("USB_DETACH", "USB device detached: ${device?.deviceName ?: "unknown"}")
                }
                android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG -> {
                    val plugged = intent.getIntExtra("state", 0) == 1
                    log("HDMI_PLUG", if (plugged) "HDMI plugged in" else "HDMI unplugged")
                }
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
                    val results = wifiManager?.scanResults
                    log("WIFI_SCAN", "WiFi scan complete — ${results?.size ?: 0} networks found")
                }
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val v = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            log("BRIGHTNESS", "Display brightness changed to: $v")
        }
    }

    private val timeoutObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val v = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            log("SCREEN_TIMEOUT", "Screen timeout changed to: ${v}ms (${v / 1000}s)")
        }
    }

    private val volumeMusicObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val v = Settings.System.getInt(contentResolver, Settings.System.VOLUME_MUSIC, -1)
            log("VOLUME_MUSIC", "Music volume changed to: $v")
        }
    }

    private val volumeRingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val v = Settings.System.getInt(contentResolver, Settings.System.VOLUME_RING, -1)
            log("VOLUME_RING", "Ring volume changed to: $v")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
            log("NETWORK_TYPE", "Active network type: $type")
        }
        override fun onLost(network: Network) {
            log("NETWORK_LOST", "Network connection lost")
        }
        override fun onAvailable(network: Network) {
            log("NETWORK_AVAIL", "Network became available")
        }
    }

    // ─── lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification("KPI event listener active"))
        openEventFile()
        registerListeners()
        log("SERVICE_START", "KPI event logging started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        log("SERVICE_STOP", "KPI event logging stopped")
        unregisterListeners()
        writer?.flush()
        writer?.close()
        Log.i(TAG, "onDestroy — $eventCount events logged")
    }

    // ─── registration ────────────────────────────────────────────────────────────

    private fun registerListeners() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addDataScheme("package")   // required for PACKAGE_ADDED/REMOVED
        }
        // Screen and locale intents don't use data URIs — need separate receiver
        val filterNoData = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        }
        val filterPkg = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(systemReceiver, filterNoData)
        registerReceiver(systemReceiver, filterPkg)

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, timeoutObserver)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.VOLUME_MUSIC), false, volumeMusicObserver)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.VOLUME_RING), false, volumeRingObserver)

        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
    }

    private fun unregisterListeners() {
        try { unregisterReceiver(systemReceiver) } catch (_: Exception) {}
        contentResolver.unregisterContentObserver(brightnessObserver)
        contentResolver.unregisterContentObserver(timeoutObserver)
        contentResolver.unregisterContentObserver(volumeMusicObserver)
        contentResolver.unregisterContentObserver(volumeRingObserver)
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    // ─── output ──────────────────────────────────────────────────────────────────

    private fun openEventFile() {
        val outDir = File(Environment.getExternalStorageDirectory(), "CrossUserLogTest")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(outDir, "kpi_events_${ts}.txt")
        writer = PrintWriter(file.bufferedWriter())
        writer?.println("# KpiEventService — event log")
        writer?.println("# Started: ${Date()}")
        writer?.println("# Format: timestamp | KPI_KEY | detail")
        writer?.println("# ─────────────────────────────────────────────────────────")
        writer?.flush()
        Log.i(TAG, "Event file: ${file.absolutePath}")
    }

    @Synchronized
    private fun log(key: String, detail: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts | $key | $detail"
        Log.i(TAG, line)
        writer?.println(line)
        eventCount++
        if (eventCount % 5 == 0) writer?.flush()
        mainHandler.post { Toast.makeText(this, "[$key] $detail", Toast.LENGTH_SHORT).show() }
        updateNotification("Event #$eventCount: $key")
    }

    // ─── notification ─────────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KPI Events", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KPI Event Logger")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
}
