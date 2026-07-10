# Single-User Persistent Service — Architecture Report

## Summary

Our cross-user logging approach hinges on two Android manifest attributes:
`android:singleUser="true"` and `android:persistent="true"`. Together they let a
single platform-signed APK — installed for all users — run **one** always-on log
producer and provider **in user 0 (owner)**, while foreground-user components read
across the user boundary. This report explains what each tag does, why we use it,
which components carry it, and the constraints and limitations that follow.

---

## 1. Problem context

- Logs must be captured for a target package **across all user profiles**.
- USB volumes are mounted **only in the foreground user**, which is unpredictable.
- Cross-user filesystem paths do not work (different effective UID + SELinux
  context per user); only ParcelFileDescriptors crossing Binder survive the hop.

The design therefore splits responsibilities:

| Concern | Where it runs | Manifest tags |
|---------|---------------|---------------|
| Produce + store logs | **user 0 only** | `singleUser` + `persistent` |
| Expose logs across users | **user 0 only** | `singleUser` (provider) |
| Write to the mounted USB | **foreground user** | *neither* (per-user) |

The `singleUser` + `persistent` combination is what makes the first two rows work.

---

## 2. `android:singleUser="true"`

### What it does
The system instantiates **exactly one** instance of the component, in the primary
user (**user 0 / owner**), regardless of which user triggers it. Applies to
`<service>`, `<provider>`, `<receiver>`, and `<activity>`. When a secondary-user
component invokes a `singleUser` component, the call is routed to the single
user-0 instance.

### Requirements
- The app must be **platform-signed** (system app) and hold
  `INTERACT_ACROSS_USERS` / `INTERACT_ACROSS_USERS_FULL`. `singleUser` is ignored
  for ordinary apps.

### Why we use it
1. **One authoritative log store.** The producer and its files live only in user
   0 — no per-profile duplication, no divergent state.
2. **One writer.** A single `LoggerService` instance owns log rotation; there is
   never a second producer racing it from another profile.
3. **A stable cross-user surface.** `LogProvider` (also `singleUser`) is the one
   place other users reach into, and it only ever hands out file descriptors
   opened in user-0 context.

### Without it
A per-user install would spawn a logger **in every profile**, each capturing a
partial view and writing its own files — no single source of truth, and the
foreground-user sync worker wouldn't know which instance to read.

---

## 3. `android:persistent="true"`

### What it does
Set on `<application>`, it marks the app as a **persistent app**: its process is
started at boot and **restarted by the system if it is killed**. The process is
effectively always resident.

### Requirements
- Honored **only for system apps** (platform-signed / on the system image).
  Ordinary apps that declare it are ignored.

### Why we use it
1. **Always-on capture.** The producer must run continuously so no logs are
   missed between reboots or after low-memory kills — it comes back automatically.
2. **No manual start dependency.** Combined with `directBootAware` + `BootReceiver`,
   the logger is up early in boot without a user opening the app.
3. **Durability under pressure.** Paired with foreground services returning
   `START_STICKY`, the capture survives memory pressure and process death.

### Relationship to foreground services
`persistent` keeps the **process** alive; `startForeground()` + `START_STICKY`
keep each **service** alive and re-delivered. We use both: `persistent` at the
application level, foreground `START_STICKY` services for the workers.

---

## 4. Component map

| Component | `singleUser` | `persistent`¹ | `directBootAware` | Rationale |
|-----------|:---:|:---:|:---:|-----------|
| `LoggerService` (producer) | ✅ | ✅ | ✅ | one always-on user-0 producer; up early at boot |
| `LogProvider` (cross-user FD source) | ✅ | ✅ | — | single user-0 read surface; FDs cross the boundary |
| `LogCaptureService` (legacy capture) | ✅ | ✅ | ✅ | same reasons; user-0 only |
| `UsbSyncService` | ❌ | ✅ | — | must run in the **foreground** user (USB is there) |
| `UsbWatchService` | ❌ | ✅ | — | registers the mount watcher in the foreground user |
| `UsbMountReceiver` | ❌ | ✅ | — | mount trigger fires in the foreground user |

¹ `persistent` is an `<application>` attribute; it governs the whole process. The
per-component durability comes from that plus foreground `START_STICKY`.

**Key point:** the producer/provider are `singleUser` (pinned to user 0); the USB
side is deliberately **not** `singleUser` because it must execute in whatever user
is foreground. The user boundary between them is crossed only by the provider's
ParcelFileDescriptor.

---

## 5. Runtime behavior

```
              ┌──────────── USER 0 (owner) — singleUser + persistent ───────────┐
  boot ─────► │  LoggerService  ── rotates ──►  dataDir/logs/log-*.log          │
              │       ▲ always-on, restarted if killed                          │
              │  LogProvider  ── query() / openFile() (PFD) / call("rotate") ──► │
              └───────────────────────────────┬─────────────────────────────────┘
                                   cross-user Binder (FD survives)
              ┌───────────────────────────────┴──── FOREGROUND USER (any id) ───┐
              │  UsbWatchService → UsbSyncService → stream PFD → USB stick       │
              └─────────────────────────────────────────────────────────────────┘
```

- **Single instance:** no matter which profile is foreground, there is one
  `LoggerService` and one `LogProvider`, both in user 0.
- **Survives boot / kill / user switch:** `persistent` + `START_STICKY` bring the
  producer back automatically; a profile switch never tears down the user-0 logger
  (only the foreground-user USB components come and go).
- **Foreground user reads across:** the sync worker resolves
  `content://0@com.sqa.logtest.logs/…` and opens a user-0 PFD via the provider.

---

## 6. Why this is the right fit

| Alternative | Problem it has |
|-------------|----------------|
| Per-user logger (no `singleUser`) | N partial captures, N file stores, no single truth |
| Manually-started service (no `persistent`) | misses boot window; dies under memory pressure and stays dead |
| Native daemon writing USB directly | needs raw `/mnt/media_rw` access + custom SELinux domain; heavier, cert risk |
| Foreground-user logger reading other users | cross-user paths fail; can't see user-0 data |

`singleUser` + `persistent` gives an always-on, single-instance user-0 producer
with a clean cross-user read surface — using only manifest attributes, no native
code and no extra sepolicy beyond what a platform app already needs.

---

## 7. Lifecycle / trigger matrix

| Event | Effect |
|-------|--------|
| Device boot | `persistent` app process starts; `BootReceiver` (directBoot) starts `LoggerService` in user 0 |
| Low-memory kill | process restarted by the system; `START_STICKY` re-delivers the service |
| Profile switch | user-0 logger untouched; foreground-user USB components restart in the new user |
| App opened in a secondary user | `singleUser` components still resolve to the user-0 instance |
| USB mount (foreground user) | `UsbWatchService` callback → `UsbSyncService` reads user-0 PFDs |

---

## 8. Requirements & limitations

**Requirements**
- **Platform signing** (UID 1000): `singleUser` and `persistent` are both honored
  only for system apps. Without it the tags are silently ignored and the app runs
  as an ordinary per-user app with a normal UID.
- `INTERACT_ACROSS_USERS_FULL` for the cross-user provider access.

**Limitations**
- `persistent` increases the always-resident memory footprint (acceptable for a
  system logging component, but it never fully idles out).
- `singleUser` pins everything to **user 0** — correct only when the human owner
  *is* user 0 (not a headless-system-user / HSUM device where the owner is
  non-zero). On HSUM the producer would need to target the actual owner user.
- The foreground-user watcher must be registered in the foreground user (open the
  app there); a boot-started watcher in user 0 can't see another profile's mount.

**Cert / EDLA notes**
- `INTERACT_ACROSS_USERS_FULL` (and `WRITE_MEDIA_STORAGE` on the USB side) are
  `signature|privileged` → must be allowlisted in `privapp-permissions-*.xml`.
- Give the app a dedicated SELinux `seinfo` domain rather than widening
  `platform_app`, and ship enforcing with zero AVC denials.
- Gate cross-user-read + USB-export behind enterprise provisioning so it reads as
  a disclosed managed capability in privacy review.

---

## 9. Verifying it works

```bash
# Confirm the app runs as UID 1000 (platform-signed → tags honored)
adb shell dumpsys package com.sqa.logtest | grep userId

# Confirm a SINGLE logger instance, in user 0 only
adb shell dumpsys activity services com.sqa.logtest | grep -E "LoggerService|user"

# Confirm it restarts after a kill (persistent)
adb shell am force-stop com.sqa.logtest   # …then re-check services; it should return
```

If `userId=1000`, `singleUser` and `persistent` are honored: one user-0 logger,
always resident. If `userId=10xxx`, the app isn't platform-signed and both tags
are being ignored — cross-user capture won't work.
