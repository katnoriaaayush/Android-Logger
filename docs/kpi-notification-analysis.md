# KPI Notification Analysis: Daemon vs Android Service

## Summary

| Approach | True Push (no polling) | Requires Processing | Polling / Probe Only |
|---|---|---|---|
| **Native Daemon** | 8 | 6 (logcat stream) | 12 |
| **Android Service** | 16 | 2 (logcat stream) | 8 |

Switching from a native daemon to an Android service converts **8 additional KPIs from polling to true push** by unlocking framework callbacks unavailable to native code.

---

## What "True Push" Means

A KPI is **true push** when the OS delivers a notification to your module without any periodic probing:
- Kernel: netlink socket, uevent, inotify
- Android: `BroadcastReceiver`, `ContentObserver`, `ConnectivityManager.NetworkCallback`, `DisplayManager.DisplayListener`

A KPI is **polling** when the module must run a command or read a file on a timer to detect change.

A KPI is **direct** (zero-processing) when the notification payload already contains the final value — no secondary query or computation needed.

---

## Daemon Approach

### True Push — Kernel/Netlink (8 KPIs)

| # | KPI | Mechanism | Direct? |
|---|---|---|---|
| 1 | Power on | `/proc` start — process is itself the signal | ✅ Direct |
| 2 | Power off | `SIGTERM` / `/sys/power/wakeup_count` uevent or logcat | ✅ Direct |
| 3 | Screen on/off (backlight) | `/sys/class/backlight/*/brightness` inotify OR `NETLINK_KOBJECT_UEVENT` | ✅ Direct |
| 4 | USB attach/detach | `NETLINK_KOBJECT_UEVENT` `add`/`remove` on `/sys/bus/usb/` | ✅ Direct |
| 5 | HDMI plug/unplug | `NETLINK_KOBJECT_UEVENT` on `/sys/devices/.../hdmi` | ✅ Direct |
| 6 | Network interface up/down | `RTMGRP_LINK` netlink | ✅ Direct |
| 7 | WiFi scan results | `RTMGRP_LINK` + `NL80211` netlink socket | ⚠️ Parse nl80211 events |
| 8 | App install/uninstall | inotify on `/data/app/` directory | ⚠️ Parse directory name for pkg |

### Logcat Stream — Push-Like, Minimal Processing (6 KPIs)

These KPIs can be captured by tailing `logcat` in streaming mode — not periodic probing, but requires pattern matching on log lines.

| # | KPI | logcat tag / pattern |
|---|---|---|
| 9 | Multi-window mode | `WindowManager` / `ActivityTaskManager` — `enterMultiWindowMode` |
| 10 | OTA update start | `update_engine` — `Downloading`, `Applying` |
| 11 | OTA update complete | `update_engine` — `Update successfully applied` |
| 12 | Touch sensor error | kernel buffer (`-b kernel`) — `i2c` / `input` error lines |
| 13 | AMS login (user switch) | `ActivityManager` — `START u10` or `am_switch_user` |
| 14 | Physical port errors | kernel buffer — USB / serial error patterns |

### Polling Required (12 KPIs)

These cannot be obtained via push from a native daemon; the daemon must read a value on a timer.

| # | KPI | Probe command / file | Interval |
|---|---|---|---|
| 15 | Volume level | `dumpsys audio` → parse stream volumes | ~1 s |
| 16 | Display brightness value | `settings get system screen_brightness` | ~1 s |
| 17 | Screen timeout config | `settings get system screen_off_timeout` | On-demand |
| 18 | Network type (WiFi/LTE) | `dumpsys connectivity` or parse netlink route | ~5 s |
| 19 | Screen sharing active | `dumpsys media_projection` | ~5 s |
| 20 | System locale | `getprop persist.sys.locale` | On-demand |
| 21 | Foreground app | `dumpsys activity` → parse `mFocusedActivity` | ~1 s |
| 22 | Time per app (active) | delta of foreground app polls | Derived |
| 23 | CPU usage | `/proc/stat` delta | ~1 s |
| 24 | Memory usage | `/proc/meminfo` | ~1 s |
| 25 | Battery level | `dumpsys battery` | ~30 s |
| 26 | Storage usage | `df /data` | ~60 s |

---

## Android Service Approach

The Android service runs with `sharedUserId="android.uid.system"` (UID 1000), gaining access to protected framework APIs.

### True Push — Framework Callbacks (16 KPIs)

| # | KPI | Callback / API | Direct? |
|---|---|---|---|
| 1 | Power on | `BootReceiver` — `ACTION_BOOT_COMPLETED` | ✅ Direct |
| 2 | Power off | `BroadcastReceiver` — `ACTION_SHUTDOWN` | ✅ Direct |
| 3 | Screen on | `BroadcastReceiver` — `Intent.ACTION_SCREEN_ON` | ✅ Direct |
| 4 | Screen off | `BroadcastReceiver` — `Intent.ACTION_SCREEN_OFF` | ✅ Direct |
| 5 | USB device attach | `BroadcastReceiver` — `UsbManager.ACTION_USB_DEVICE_ATTACHED` | ✅ Direct (extras carry device) |
| 6 | USB device detach | `BroadcastReceiver` — `UsbManager.ACTION_USB_DEVICE_DETACHED` | ✅ Direct |
| 7 | HDMI plug/unplug | `BroadcastReceiver` — `AudioManager.ACTION_HDMI_AUDIO_PLUG` | ✅ Direct (plugged extra) |
| 8 | Volume change | `ContentObserver` on `Settings.System.VOLUME_*` | ✅ Direct (query new value) |
| 9 | Display brightness change | `ContentObserver` on `Settings.System.SCREEN_BRIGHTNESS` | ✅ Direct |
| 10 | Screen timeout config change | `ContentObserver` on `Settings.System.SCREEN_OFF_TIMEOUT` | ✅ Direct |
| 11 | App install | `BroadcastReceiver` — `Intent.ACTION_PACKAGE_ADDED` | ✅ Direct (pkg in data URI) |
| 12 | App uninstall | `BroadcastReceiver` — `Intent.ACTION_PACKAGE_REMOVED` | ✅ Direct |
| 13 | Network type change | `ConnectivityManager.registerNetworkCallback()` | ✅ Direct (NetworkCapabilities) |
| 14 | Screen sharing start/stop | `MediaProjectionManager` callback OR `DisplayManager.DisplayListener` | ✅ Direct |
| 15 | WiFi scan results | `BroadcastReceiver` — `WifiManager.SCAN_RESULTS_AVAILABLE_ACTION` | ✅ Direct (call getScanResults) |
| 16 | System locale change | `BroadcastReceiver` — `Intent.ACTION_LOCALE_CHANGED` | ✅ Direct |

### Logcat Stream — 2 KPIs (no pure framework push)

| # | KPI | Reason |
|---|---|---|
| 17 | Multi-window mode | `Activity.onMultiWindowModeChanged` only available to the activity being changed; cross-app → logcat (or API 35+ `ACTION_MULTI_WINDOW_MODE_CHANGED` system broadcast) |
| 18 | OTA update progress | No public broadcast; `update_engine` writes to logcat |

### Polling Required (8 KPIs)

| # | KPI | API | Interval |
|---|---|---|---|
| 19 | Foreground app | `UsageStatsManager.queryEvents()` | ~1 s |
| 20 | Time per app | Derived from `UsageStatsManager` events | Derived |
| 21 | CPU usage | `/proc/stat` | ~1 s |
| 22 | Memory usage | `ActivityManager.getMemoryInfo()` | ~1 s |
| 23 | Battery level | `BatteryManager.getIntProperty()` (or sticky broadcast) | ~30 s |
| 24 | Storage usage | `StatFs` on `/data` | ~60 s |
| 25 | Touch sensor error | logcat `-b kernel` stream (passive) or poll `/proc/kmsg` | passive stream |
| 26 | AMS login / user switch | `UserManager` has no public listener; logcat `am_switch_user` | passive stream |

---

## KPI Count Summary by Category

### Daemon
```
True kernel push (netlink / inotify / uevent):  8
Logcat stream (push-like, pattern match):        6
Polling required:                               12
─────────────────────────────────────────────────
Total KPIs:                                     26
```

### Android Service
```
True framework push (broadcast / ContentObserver / callback):  16
Logcat stream (push-like, pattern match):                       2
Polling required:                                               8
──────────────────────────────────────────────────────────────────
Total KPIs:                                                     26
```

### Net Gain of Android Service Over Daemon

| Category | Daemon | Android Service | Delta |
|---|---|---|---|
| True push | 8 | 16 | **+8** |
| Logcat stream | 6 | 2 | -4 |
| Polling | 12 | 8 | **-4** |

The 4 Settings-based KPIs (volume, brightness, screen timeout, and one more) move from **polling** (daemon must `settings get` on a timer) to **true push** (`ContentObserver` fires synchronously on any write to the Settings database).

The 4 package/locale/network/screen KPIs move from **logcat stream or polling** to **clean framework broadcasts** with typed extras.

---

## Implementation Complexity by Approach

### Daemon (C/C++ native binary)

```
Simple    (< 1 day):  power on/off, USB/HDMI uevent, network link
Moderate  (1–3 days): WiFi NL80211 parse, app install via inotify
Complex   (3–5 days): logcat stream parser for 6 pattern-match KPIs
Hard      (5+ days):  all 12 polling KPIs + aggregation logic
```

### Android Service (Kotlin, system-signed)

```
Simple    (< 1 day):  all 16 BroadcastReceiver + ContentObserver + NetworkCallback KPIs
Moderate  (1–2 days): logcat stream (2 remaining KPIs)
Complex   (2–4 days): polling KPIs (UsageStats, StatFs, ActivityManager)
```

### Recommendation

Use an **Android service** as the primary collector:
- 16 KPIs arrive as true framework push with zero polling overhead
- Only 8 KPIs require polling, half of what the daemon needs
- ContentObserver provides synchronous Settings-change delivery unavailable to any native code
- `ConnectivityManager.NetworkCallback` delivers richer network metadata than raw netlink parsing

A native daemon remains useful as a **fallback** for before-unlock (before Android framework is ready) or for KPIs requiring low-level kernel access (raw CPU `/proc/stat`, kernel error events).
