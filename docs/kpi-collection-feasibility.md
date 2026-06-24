# KPI Data Collection — Approach & Feasibility

This document maps each KPI to its Android API approach and native daemon
approach, with a complexity and feasibility rating for each.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Straightforward — well-documented API or single command |
| ⚠️ | Moderate complexity — requires parsing, polling, or specific permissions |
| ❌ | High complexity or blocked — OEM-specific, platform-signing required, or fundamentally inaccessible |

**Complexity scale:** Low · Medium · High · Very High

---

## 1. Hardware

### 1.1 Power On/Off Timestamp & Total Runtime

| | Android App | Native Daemon |
|---|---|---|
| **How** | `BOOT_COMPLETED` broadcast gives boot time. Shutdown via `ACTION_SHUTDOWN` broadcast. Store to SharedPreferences. | Read `/proc/uptime` → `boot_epoch = now - uptime`. Write timestamp to file on `SIGTERM`. Cross-session delta = total runtime. |
| **Permission** | `RECEIVE_BOOT_COMPLETED` | None (root process) |
| **Complexity** | Low | Low ✅ |
| **Notes** | App may not receive `ACTION_SHUTDOWN` reliably if killed first. Boot timestamp is accurate. | Daemon already reads `/proc/uptime` for boot epoch. Shutdown capture is a one-line `SIGTERM` handler. Already ~80% implemented. |

**Verdict:** Native daemon is simpler and more reliable. ✅ Low effort to add.

---

### 1.2 Panel Backlight Cumulative On-Time

| | Android App | Native Daemon |
|---|---|---|
| **How** | Register `BroadcastReceiver` for `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`. Accumulate delta between events. | Parse logcat tag `DisplayPowerController` for `Turning screen on/off` lines with timestamps. OR poll `/sys/class/backlight/*/actual_brightness`. |
| **Permission** | None for screen events | None (logcat already captured) |
| **Complexity** | Low | Low ✅ |
| **Notes** | Very reliable via broadcasts. Cumulative time must persist across reboots. | `DisplayPowerController` events appear in the existing logcat stream. Timestamp delta is trivial. No new data source needed. |

**Verdict:** Both approaches are easy. Daemon can extract this from the existing logcat stream with a tag filter. ✅

---

### 1.3 Physical Port Plugin / Out Events

| | Android App | Native Daemon |
|---|---|---|
| **How** | USB: `UsbManager` + `ACTION_USB_DEVICE_ATTACHED/DETACHED` broadcast. HDMI: `ACTION_HDMI_PLUGGED` broadcast. Audio jack: `ACTION_HEADSET_PLUG`. | Listen on `/dev/uevent` socket (netlink) as root. Events contain `SUBSYSTEM=usb`, `ACTION=add/remove`. HDMI events under `SUBSYSTEM=drm`. Alternatively parse logcat for `UsbDeviceManager`, `HdmiControlService`. |
| **Permission** | None for receiving broadcasts | Root (netlink socket) |
| **Complexity** | Low (Android) · Medium (daemon uevent) | ⚠️ Medium |
| **Notes** | Android broadcasts are the cleanest path. Works without root. | Netlink uevent parsing adds ~50 lines of C. Logcat parse is simpler but depends on OEM logging consistency. |

**Verdict:** Android API is clearly simpler here. For daemon-only: parse logcat tags `UsbDeviceManager` + `HdmiControlService` — less code than netlink. ⚠️

---

### 1.4 Touch Sensor Error Logs

| | Android App | Native Daemon |
|---|---|---|
| **How** | Not accessible from app layer. Touch driver errors are kernel-level. | Filter logcat buffer `kernel` + tags like `atmel_mxt_ts`, `synaptics_dsx`, `goodix_ts` (OEM-specific touch driver tag). Already present in logcat stream. |
| **Permission** | ❌ Not accessible | Captured in existing logcat stream |
| **Complexity** | Very High (not feasible) | Low ✅ |
| **Notes** | Touch driver tag name varies by OEM and driver. Needs device-specific tag list. | Add tag filter to logcat command: `-b kernel` captures kernel ring buffer entries including touch errors. |

**Verdict:** Daemon-only path. OEM touch driver log tag must be determined per device. ⚠️

---

## 2. Resource

### 2.1 Real-Time CPU Utilisation

| | Android App | Native Daemon |
|---|---|---|
| **How** | Read `/proc/stat` (accessible to all apps). Compute delta between two snapshots: `(total_delta - idle_delta) / total_delta × 100`. | Same: poll `/proc/stat` every N seconds. Per-core breakdown from `cpu0`, `cpu1` … rows. |
| **Permission** | None | None |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | `/proc/stat` is world-readable. Standard two-snapshot delta calculation. | Trivial to add a periodic sampler thread to the daemon. Outputs: overall %, per-core %, timestamp. |

**Verdict:** Identical effort both ways. Daemon sampler thread is the right home for this alongside RAM/storage. ✅

---

### 2.2 Available RAM Capacity

| | Android App | Native Daemon |
|---|---|---|
| **How** | `ActivityManager.getMemoryInfo()` → `availMem`. Or parse `/proc/meminfo` → `MemAvailable`. | Parse `/proc/meminfo` → `MemAvailable` field. One `fopen` + `fscanf`. |
| **Permission** | None | None |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | `/proc/meminfo` is world-readable. `MemAvailable` is the right field (not `MemFree`). | Can also capture `MemTotal`, `SwapFree`, `Cached` in the same read for free. |

**Verdict:** Trivially easy either way. ✅

---

### 2.3 Internal Storage Available

| | Android App | Native Daemon |
|---|---|---|
| **How** | `StatFs("/data")` → `availableBlocksLong * blockSizeLong`. | `statvfs("/data", &st)` → `st.f_bavail * st.f_bsize`. Or shell: `df /data`. |
| **Permission** | None | None |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | Also worth tracking `/data/media/0` (user storage) separately. | One syscall. Can also report total and used in the same call. |

**Verdict:** Trivially easy either way. ✅

---

### 2.4 GPU Utilisation

| | Android App | Native Daemon |
|---|---|---|
| **How** | No public Android API. Requires root or OEM SDK. | Qualcomm: `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`. Mali: `/sys/devices/platform/*/utilization`. Path varies by SoC. |
| **Permission** | ❌ Not accessible without root | Root + device-specific sysfs path |
| **Complexity** | Very High (not feasible from app) | ⚠️ Medium — SoC-specific path detection |
| **Notes** | SoC vendor must be detected at runtime (`ro.board.platform` getprop) to select the right sysfs path. Qualcomm, MediaTek, and Mali paths all differ. | Start with Qualcomm (most common). Probe the sysfs path; gracefully skip if not present. |

**Verdict:** Daemon-only. Needs device-specific path lookup. ⚠️ Medium effort.

---

## 3. OS

### 3.1 System Volume Adjustment History

| | Android App | Native Daemon |
|---|---|---|
| **How** | `ContentObserver` on `Settings.System.VOLUME_*` URIs. Or `AudioManager` with `ACTION_AUDIO_BECOMING_NOISY`. | Parse logcat tag `AudioService` for `setStreamVolume stream=X vol=Y` lines. Present in existing logcat stream. |
| **Permission** | None | Logcat already captured |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | Android `ContentObserver` gives real-time change events with old/new value. | `AudioService` logs every volume change with stream type (music/ring/alarm) and level. Tag filter is sufficient. |

**Verdict:** Both easy. Daemon logcat filter is zero additional code if stream is already captured. ✅

---

### 3.2 Display Brightness & Picture Mode

| | Android App | Native Daemon |
|---|---|---|
| **How** | `Settings.System.SCREEN_BRIGHTNESS` via `ContentResolver`. `ContentObserver` for change events. Picture mode: OEM-specific Settings key (no standard API). | Shell: `settings get system screen_brightness`. Sysfs: `/sys/class/backlight/*/brightness`. Picture mode: query OEM-specific Settings key via `settings get` command. |
| **Permission** | None for read | Root (already have it) |
| **Complexity** | Low (brightness) · High (picture mode) ⚠️ | Low (brightness) · High (picture mode) ⚠️ |
| **Notes** | Picture mode key name is OEM-defined. Must be discovered per device (e.g. Sony uses `picture_mode`, Samsung uses `picture_quality`). There is no AOSP standard. | `settings get system <key>` works as root from daemon. Brightness change events appear in `DisplayManagerService` logcat tag. |

**Verdict:** Brightness is easy. Picture mode is OEM-specific and requires per-device investigation. ⚠️

---

### 3.3 Screen Sleep / Timeout Configuration

| | Android App | Native Daemon |
|---|---|---|
| **How** | `Settings.System.SCREEN_OFF_TIMEOUT` via `ContentResolver`. Returns value in ms. | Shell: `settings get system screen_off_timeout`. Returns ms. |
| **Permission** | None (read-only) | Root |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | This is a configuration snapshot, not an event stream. Polling every few minutes is sufficient. | Single command. Can detect changes by polling or by watching `SettingsProvider` logcat tag. |

**Verdict:** Trivially easy both ways. ✅

---

## 4. Apps

### 4.1 Native App Launch Frequency

| | Android App | Native Daemon |
|---|---|---|
| **How** | `UsageStatsManager.queryUsageStats()` — gives per-app foreground time and launch count. Requires user to grant `PACKAGE_USAGE_STATS` in Settings manually (cannot be auto-granted). | Parse logcat tag `ActivityTaskManager` for `START u0 {…} cmp=<package>/<activity>` lines. Every app launch is logged here. |
| **Permission** | `PACKAGE_USAGE_STATS` (user must approve in Settings) | Logcat already captured |
| **Complexity** | ⚠️ Medium (permission UX friction) | Low ✅ |
| **Notes** | `UsageStatsManager` is the correct API but requires a user-visible permission grant. On managed/kiosk devices this can be pre-granted via MDM. | Logcat approach requires no special permission. Package name and activity are in the log line. Count launches per package per session. |

**Verdict:** Daemon logcat parse is strictly better — no user permission required. ✅

---

### 4.2 Time Per App (Foreground Duration)

| | Android App | Native Daemon |
|---|---|---|
| **How** | `UsageStatsManager` → `UsageStats.getTotalTimeInForeground()`. Same permission friction as above. | Timestamp delta between `ActivityTaskManager: START` for package A and the next focus-change line (`ActivityManager: ActivityResumed` for a different package). |
| **Permission** | `PACKAGE_USAGE_STATS` | Logcat already captured |
| **Complexity** | ⚠️ Medium | ⚠️ Medium (state machine parsing) |
| **Notes** | `UsageStatsManager` is accurate. Logcat approach requires maintaining a per-package foreground start time and updating it on each focus change event. | Less accurate if logs are lost, but sufficient for usage pattern analysis. |

**Verdict:** Both medium. `UsageStatsManager` is more accurate but has permission UX cost. Logcat parse is good enough for KPI purposes. ⚠️

---

### 4.3 Third-Party App Install & Uninstall

| | Android App | Native Daemon |
|---|---|---|
| **How** | `BroadcastReceiver` for `ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REMOVED`, `ACTION_PACKAGE_REPLACED`. Includes package name and version. | Parse logcat tag `PackageManager` for `+` (install) and `-` (uninstall) events, or `installd` tag. Lines contain package name, version code, and UID. |
| **Permission** | None | Logcat already captured |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | Android broadcasts are the canonical path and include whether it was a first install or update. | `PackageManager` log lines are verbose and reliable. Can distinguish install/update/uninstall from the action string. |

**Verdict:** Both easy. Either works well. ✅

---

### 4.4 Multi-Window Activation History

| | Android App | Native Daemon |
|---|---|---|
| **How** | `ActivityManager.isInMultiWindowMode()` polling, or `Activity.onMultiWindowModeChanged()` callback (requires Activity context). | Parse logcat tags `ActivityTaskManager`, `WindowManager` for `enterSplitScreenMode`, `exitSplitScreenMode`, `moveFocusableActivityToOrganizedTask` lines. |
| **Permission** | None (if in own Activity) / Restricted (other apps) | Logcat already captured |
| **Complexity** | ⚠️ Medium (app-scoped only) | Low ✅ |
| **Notes** | Android API only reports multi-window state for the app itself, not system-wide. Cannot observe other apps entering split screen. | Logcat `ActivityTaskManager` tag logs system-wide multi-window transitions. No additional data source needed. |

**Verdict:** Daemon logcat parse is the only way to get system-wide multi-window history. ✅

---

## 5. User Behaviour

### 5.1 Touch Input Tool Identification

| | Android App | Native Daemon |
|---|---|---|
| **How** | `MotionEvent.getToolType()` in `onTouchEvent()` — returns `TOOL_TYPE_FINGER`, `TOOL_TYPE_STYLUS`, `TOOL_TYPE_MOUSE`. But only for touches within the app's own window. | Read raw events from `/dev/input/eventX` as root. `EV_ABS / ABS_MT_TOOL_TYPE` field: `0` = finger, `1` = stylus/pen. Use `getevent -l /dev/input/eventX`. |
| **Permission** | None (own window only) | Root + `/dev/input/` access |
| **Complexity** | ⚠️ Medium (window-scoped only) | ⚠️ Medium |
| **Notes** | App approach cannot identify touch type outside its own Activity. System-wide identification requires root. | Must identify the correct `/dev/input/eventX` node (the touch screen, not keyboard/buttons). Probe `EV_ABS` + `ABS_MT_SLOT` capability to find the right device. |

**Verdict:** Daemon is the only path for system-wide tool identification. Medium effort for input device enumeration. ⚠️

---

### 5.2 Number of Touch Inputs by Time

| | Android App | Native Daemon |
|---|---|---|
| **How** | Count `ACTION_DOWN` events in `onTouchEvent()` per time window. Own window only. | Count `EV_ABS ABS_MT_TRACKING_ID` events from `/dev/input/eventX`. Each new tracking ID (not `-1`) is one new contact. Bucket by minute/hour. |
| **Permission** | None (own window only) | Root + `/dev/input/` access |
| **Complexity** | Low (own window) · High (system-wide via app) | ⚠️ Medium |
| **Notes** | For system-wide touch counting, the app approach is not feasible. | Same input device as 5.1 above. Once the device node is identified, counting tracking IDs is straightforward. |

**Verdict:** Daemon-only for system-wide counts. Same input reader as 5.1 — both KPIs share one implementation. ⚠️

---

## 6. AMS Integration

### 6.1 AMS Login Method

| | Android App | Native Daemon |
|---|---|---|
| **How** | If the AMS app is under our control: log the login method explicitly with a known tag. If third-party: `UsageStatsManager` can show the app was opened, but not the method used. | Parse logcat for the AMS app's package tag. Login method only appears if the AMS app logs it. Filter by AMS package name and known log strings. |
| **Permission** | Depends on AMS app | Logcat access (already have it) |
| **Complexity** | Low (own app) · ❌ Very High (third-party black box) | ⚠️ Medium — requires knowing AMS log tag and format |
| **Notes** | This KPI is only feasible if we control the AMS app or have documentation of its log output. Without that, login method is not observable from outside. | If AMS is a known in-house or partner app, its log tag and format can be documented and filtered. Otherwise this KPI is blocked. |

**Verdict:** Feasibility depends entirely on whether the AMS app is under your control or provides documented log output. ❌ Blocked without AMS cooperation.

---

## 7. Integration

### 7.1 External Cloud Drive Connections

| | Android App | Native Daemon |
|---|---|---|
| **How** | `ConnectivityManager.NetworkCallback` detects network changes. Cannot inspect which app is using the network or which hostname without VPN/packet inspection. | Monitor `/proc/net/tcp6` for connections to known cloud IP ranges. Or parse logcat for cloud app package tags (`com.google.android.apps.docs`, `com.dropbox.android`). |
| **Permission** | `ACCESS_NETWORK_STATE` | Root (procfs access) |
| **Complexity** | ❌ High (can't see other apps' connections from app) | ⚠️ Medium |
| **Notes** | An app cannot inspect other apps' network connections without VPN service. IP-range matching in `/proc/net/tcp6` is feasible but requires maintaining IP ranges for each cloud provider (they change). | Logcat package tag approach is simpler: if a cloud sync app is active, it logs to logcat. Combine with app launch frequency (4.1) for session-level detection. |

**Verdict:** Daemon logcat approach (detect cloud app activity) is more practical than IP range matching. ⚠️

---

## 8. Connectivity

### 8.1 Network Interface Connection Type

| | Android App | Native Daemon |
|---|---|---|
| **How** | `ConnectivityManager.getActiveNetworkInfo().getType()` (deprecated) or `NetworkCapabilities.hasTransport()` → `TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`. | Shell: `ip link show` + parse state. Or read `/sys/class/net/<iface>/operstate`. Interface names indicate type: `wlan*` = WiFi, `eth*` = Ethernet, `rmnet*` = mobile data. |
| **Permission** | `ACCESS_NETWORK_STATE` | Root |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | `NetworkCapabilities` is the modern API (API 21+) and handles multiple simultaneous interfaces. | `ip -j link show` (JSON output) makes parsing simpler from daemon. Detect on change via netlink or by polling every 30 seconds. |

**Verdict:** Both easy. Android API is cleaner; daemon shell command is sufficient. ✅

---

### 8.2 Ping Latency & Packet Loss

| | Android App | Native Daemon |
|---|---|---|
| **How** | No `ping` API in Android SDK. Can use `Runtime.exec("ping")` but requires careful permission handling. `InetAddress.isReachable()` exists but is ICMP-unreliable. | Fork `ping -c 5 -W 2 8.8.8.8` as root. Parse output: `rtt min/avg/max` and `X% packet loss`. Schedule every N minutes. |
| **Permission** | `INTERNET` (exec ping works on rooted devices) | Root (ICMP raw socket) |
| **Complexity** | ⚠️ Medium (Android exec) | Low ✅ |
| **Notes** | On non-rooted devices, `ping` via `exec` may be restricted in Android 10+. Daemon has no such restriction. | Target IPs: `8.8.8.8` (public) + gateway IP from `/proc/net/route` (local). Report both. |

**Verdict:** Native daemon is more reliable. ✅ Low effort.

---

### 8.3 Wireless Screen Sharing Protocol

| | Android App | Native Daemon |
|---|---|---|
| **How** | `DisplayManager.registerDisplayListener()` detects presentation/virtual displays. Cannot identify the protocol (Miracast vs AirPlay vs proprietary). | Parse logcat tags: `WifiDisplayController` (Miracast/WFD), `WifiP2pService`, `wpa_supplicant`. AirPlay appears under third-party app tags. |
| **Permission** | None | Logcat already captured |
| **Complexity** | ⚠️ Medium (limited protocol identification) | ⚠️ Medium |
| **Notes** | Android API can detect that a secondary display appeared but not the underlying protocol. | Logcat reliably identifies Miracast/WFD. AirPlay and other proprietary protocols appear only if the app logs them. |

**Verdict:** Daemon logcat parse is the only way to identify the protocol. ⚠️

---

### 8.4 Screen Sharing Session Duration & Disconnection

| | Android App | Native Daemon |
|---|---|---|
| **How** | `DisplayManager.DisplayListener.onDisplayAdded/Removed()` gives connect/disconnect timestamps. Protocol-agnostic. | Timestamp delta between `WifiDisplayController: Connecting` and `Disconnected` log lines in existing logcat stream. |
| **Permission** | None | Logcat already captured |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | `DisplayManager` approach captures all screen sharing protocols generically. Daemon approach is protocol-specific (Miracast only unless other apps log their state). | Combine with 8.3 to get protocol + duration together. |

**Verdict:** Android API is protocol-agnostic and cleaner. Daemon works for Miracast. ✅

---

### 8.5 OTA Firmware Update Success / Failure

| | Android App | Native Daemon |
|---|---|---|
| **How** | No public API for OTA status. `SystemUpdateManager` (API 28+) gives basic state but requires `READ_PRIVILEGED_PHONE_STATE` — signature/privileged permission. | Parse logcat tag `update_engine` (A/B OTA) or `RecoverySystem`. Final log lines contain `ErrorCode::kSuccess` or specific error enum. Also check `/data/ota_package/` for leftover packages. |
| **Permission** | ❌ Privileged permission required | Logcat already captured |
| **Complexity** | ❌ Very High (permission blocked) | Low ✅ |
| **Notes** | `update_engine` is the standard AOSP A/B update daemon. Its logs are comprehensive and always in logcat. Non-A/B (legacy recovery) updates appear in `RecoverySystem` tag. | Tag filter: `update_engine` + `RecoverySystem`. Both are present in logcat stream during OTA. |

**Verdict:** Daemon-only. OTA logs are well-structured in `update_engine` tag. ✅

---

### 8.6 Public IP Address

| | Android App | Native Daemon |
|---|---|---|
| **How** | HTTP request to a public IP echo service (`https://api.ipify.org`, `https://ifconfig.me/ip`). Returns plain text IP. | Same: fork `curl -s https://api.ipify.org` or implement a minimal HTTP GET in C using POSIX sockets. |
| **Permission** | `INTERNET` | Root + network access |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | Run once at session start and re-query on network interface change events. Cache the result. | `curl` is available on most Android devices. Alternatively implement a ~30-line POSIX HTTP client to avoid the `fork` overhead. |

**Verdict:** Both easy. ✅

---

### 8.7 Ambient WiFi AP Info

| | Android App | Native Daemon |
|---|---|---|
| **How** | `WifiManager.getScanResults()` → list of `ScanResult` objects: SSID, BSSID, signal level (dBm), frequency, capabilities (security). `WifiManager.startScan()` to trigger. | Shell: `iw dev wlan0 scan` (root) — full AP list with SSID, BSSID, signal, channel, encryption. Or parse `/proc/net/wireless` for connected AP stats only. |
| **Permission** | `ACCESS_WIFI_STATE` + `ACCESS_FINE_LOCATION` (required for scan results on API 29+) | Root |
| **Complexity** | ⚠️ Medium (location permission required) | Low ✅ |
| **Notes** | Android requires `ACCESS_FINE_LOCATION` to read scan results — this is a user-visible runtime permission that users may deny. On managed devices it can be pre-granted. | `iw dev wlan0 scan` returns full AP list without location permission. Most practical on daemon. |

**Verdict:** Daemon is simpler — no location permission UX friction. ✅

---

### 8.8 Android System Locale

| | Android App | Native Daemon |
|---|---|---|
| **How** | `Locale.getDefault()` or `Resources.getConfiguration().getLocales()`. | `getprop persist.sys.locale` or `getprop ro.product.locale`. Single one-line read. |
| **Permission** | None | None |
| **Complexity** | Low ✅ | Low ✅ |
| **Notes** | Locale can change at runtime. Android app can observe changes via `Configuration` callbacks. Daemon can re-query `getprop` periodically or on `LOCALE_CHANGED` logcat entries. | |

**Verdict:** Trivially easy either way. ✅

---

## Overall Feasibility Summary

| # | KPI | Best Path | Complexity | Feasibility |
|---|-----|-----------|-----------|-------------|
| 1.1 | Power on/off timestamp & runtime | Daemon (procfs) | Low | ✅ Already ~80% done |
| 1.2 | Backlight cumulative on-time | Daemon (logcat parse) | Low | ✅ Zero new data source |
| 1.3 | Physical port plugin/out events | Android broadcast | Low | ✅ Easy either way |
| 1.4 | Touch sensor error logs | Daemon (logcat kernel buffer) | Low | ✅ Add `-b kernel` filter |
| 2.1 | CPU utilisation | Daemon (procfs sampler) | Low | ✅ Sampler thread |
| 2.2 | Available RAM | Daemon (procfs sampler) | Low | ✅ Same sampler thread |
| 2.3 | Internal storage available | Daemon (statvfs) | Low | ✅ Same sampler thread |
| 2.4 | GPU utilisation | Daemon (sysfs, SoC-specific) | Medium | ⚠️ SoC path detection |
| 3.1 | Volume adjustment history | Daemon (logcat parse) | Low | ✅ Tag filter only |
| 3.2 | Display brightness | Daemon (sysfs / logcat) | Low | ✅ Easy |
| 3.2 | Picture mode | Either (OEM-specific key) | High | ⚠️ Per-device investigation |
| 3.3 | Screen sleep/timeout config | Daemon (settings get) | Low | ✅ Single shell command |
| 4.1 | App launch frequency | Daemon (logcat parse) | Low | ✅ Tag filter only |
| 4.2 | Time per app | Daemon (logcat state machine) | Medium | ⚠️ State machine parser |
| 4.3 | App install / uninstall | Daemon (logcat parse) | Low | ✅ Tag filter only |
| 4.4 | Multi-window history | Daemon (logcat parse) | Low | ✅ Tag filter only |
| 5.1 | Touch tool identification | Daemon (/dev/input/) | Medium | ⚠️ Input device enumeration |
| 5.2 | Touch count by time | Daemon (/dev/input/) | Medium | ⚠️ Same as 5.1 |
| 6.1 | AMS login method | Logcat (if AMS logs it) | High | ❌ Blocked without AMS cooperation |
| 7.1 | Cloud drive connections | Daemon (logcat app tags) | Medium | ⚠️ App tag list needed |
| 8.1 | Network interface type | Daemon (ip link) | Low | ✅ Easy |
| 8.2 | Ping latency & packet loss | Daemon (fork ping) | Low | ✅ Easy |
| 8.3 | Screen sharing protocol | Daemon (logcat parse) | Medium | ⚠️ Protocol-specific tags |
| 8.4 | Screen sharing duration | Daemon (logcat parse) | Low | ✅ Timestamp delta |
| 8.5 | OTA update success/failure | Daemon (logcat parse) | Low | ✅ `update_engine` tag |
| 8.6 | Public IP address | Daemon (curl / HTTP) | Low | ✅ Easy |
| 8.7 | Ambient WiFi AP info | Daemon (iw scan) | Low | ✅ No location permission |
| 8.8 | Android system locale | Daemon (getprop) | Low | ✅ Single command |

---

## Implementation Grouping

Rather than implementing KPIs one by one, they naturally group into
four implementation units:

### Unit A — Extend existing logcat stream (lowest effort)
Backlight on-time, touch errors, volume history, brightness changes,
app launches, app install/uninstall, multi-window, cloud app activity,
screen sharing protocol + duration, OTA status, locale changes.

**Approach:** Add a logcat tag filter / post-processor to the existing
daemon capture loop. All data is already flowing through the pipe.

### Unit B — Periodic sampler thread (new thread, ~100 lines C)
CPU %, RAM, storage, GPU, network interface type, public IP, locale.

**Approach:** A second daemon thread that wakes every 60 seconds,
reads procfs/sysfs, and appends a TSV row to a `_metrics.tsv` file.

### Unit C — Command executors (fork + exec, ~50 lines each)
Ping latency/loss, WiFi AP scan, screen sleep config, brightness value.

**Approach:** Periodic `fork()`/`exec()` calls with output parsed and
appended to the metrics file. Can be part of Unit B's sampler thread.

### Unit D — Input event reader (new thread, ~150 lines C)
Touch tool identification, touch count by time.

**Approach:** A third daemon thread that opens `/dev/input/eventX`
(auto-detected by capability probe), reads `EV_ABS ABS_MT_*` events,
and writes per-minute touch counts + tool type histogram to
`_input.tsv`.

### Not in current scope
- Picture mode (OEM-specific, needs per-device key)
- AMS login method (blocked without AMS app cooperation)
- GPU utilisation (SoC-specific sysfs path, needs device matrix)
