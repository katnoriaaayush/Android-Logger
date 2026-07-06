# Encrypted logcat pipeline

Encrypt system logcat lines on-device, pull the file, and decrypt it on your PC
through a small localhost web UI.

## The idea in one line
The device holds only the **public key** — it can lock but never unlock. Your PC
holds the **private key** — the only thing that can unlock. A stolen device or a
copied log file is useless to anyone without your PC.

```
  ANDROID DEVICE                          YOUR PC
  ┌────────────────────────┐              ┌────────────────────────────┐
  │ logcat ─► EncryptedLog │   adb pull   │ decrypt_server.py          │
  │          Writer        │  ─────────►  │  (holds private key)       │
  │  public key only       │  .elog file  │  web UI: pick → Unlock     │
  │  internal storage      │              │  loopback 127.0.0.1 only   │
  └────────────────────────┘              └────────────────────────────┘
```

## Contents
```
encrypted-logcat/
├── README.md
├── tools/
│   └── KeyGen.kt              generate the RSA keypair (run once)
├── android/
│   ├── EncryptedLogWriter.kt  encrypts + appends log lines on-device
│   └── LogcatCollector.kt     streams full logcat into the writer
└── server/
    ├── decrypt_server.py      Python web-UI decrypt server  (recommended)
    └── DecryptServer.kt       headless JVM decrypt server    (alternative)
```

## How it works
1. **Once per session/file:** a random AES-256 key is generated, wrapped a single
   time with the RSA public key (`RSA-OAEP`, SHA-256 + MGF1-SHA-256), and written
   as a header line.
2. **Per log line:** encrypted with AES-256-GCM (hardware-accelerated on ARMv8) —
   easily handles hundreds of lines/second. Overhead ≈ 28 bytes/line.
3. **Pull:** copy the `.elog` file off the device with adb.
4. **Decrypt:** the server unwraps each session key with the private key, then
   bulk-decrypts every line and shows the plaintext — over loopback only.

File format (Base64, newline-delimited):
```
ELOGv1|<wrapped AES key>      <- header (one per session; app restarts add more)
<nonce>:<ciphertext+tag>      <- one per log line
...
```
A single file can hold several session blocks (each restart appends a new header
with its own key); the server walks them in order.

---

## Step 1 — Generate the keys (once)
```bash
cd tools
kotlinc KeyGen.kt -include-runtime -d keygen.jar && java -jar keygen.jar
```
Produces two files:
- `public_key.pem`  → copy into the Android app's `assets/` folder
- `private_key.pem` → keep ONLY on your PC, next to the server. **Never ship it to the device.**

## Step 2 — Integrate the writer into the Android app

1. Drop `EncryptedLogWriter.kt` and `LogcatCollector.kt` into your app module
   (adjust/remove the `package` line to match your project).
2. Put `public_key.pem` in `src/main/assets/`.
3. Start collection where it suits you — typically a foreground `Service`:

```kotlin
val pem  = context.assets.open("public_key.pem").bufferedReader().readText()
val pub  = EncryptedLogWriter.loadPublicKey(pem)
val file = File(context.filesDir, "logs/session-${System.currentTimeMillis()}.elog")

val writer    = EncryptedLogWriter(pub, file)
val collector = LogcatCollector(writer)
collector.start()
// ... on shutdown: collector.stop()
```

`context.filesDir` is app-private internal storage: `/data/data/<pkg>/files/`.

> **Permission note:** capturing *all* apps' logs requires the `READ_LOGS`
> permission, which is `signature|privileged` — granted only to system/platform-
> signed apps. A normal app captures only its own process's logs. (Fine for a
> platform app like a teaching board.)

You can also skip logcat and just call `writer.writeLine("...")` directly to
encrypt any lines you produce yourself.

## Step 3 — Pull the encrypted file

Platform / rooted device:
```bash
adb pull /data/data/<pkg>/files/logs/session-XXXX.elog ./
```
Debuggable build (no root):
```bash
adb exec-out run-as <pkg> cat files/logs/session-XXXX.elog > session-XXXX.elog
```

## Step 4 — Decrypt (web UI, recommended)
```bash
cd server
pip install cryptography                  # no Flask — uses the stdlib http.server
python decrypt_server.py                 # loads ./private_key.pem on 127.0.0.1:8734
# python decrypt_server.py mykey.pem 9000  # custom key path / port
```
Open **http://127.0.0.1:8734**, choose the pulled `.elog` (native file picker or
drag-and-drop), and hit **Unlock**. You get line counts, a filter box, and copy/
download of the plaintext. The header shows a short **key fingerprint** so you can
confirm which private key is loaded.

Put `private_key.pem` in the `server/` folder (or pass its path as the first arg).

### Headless alternative (no UI, JVM)
```bash
cd server
kotlinc DecryptServer.kt -include-runtime -d server.jar
java -jar server.jar private_key.pem 8734
curl "http://127.0.0.1:8734/logs?file=$PWD/session-XXXX.elog"
```

---

## Notes & hardening
- **Loopback only.** Both servers bind `127.0.0.1`; the response is decrypted
  plaintext and must never leave the box.
- **Integrity is built in.** GCM authenticates every line — a tampered or
  truncated line fails to decrypt and is skipped with a marker instead of
  returning garbage.
- **Not searchable.** Ciphertext isn't greppable; you decrypt whole files.
- **OAEP must match.** Writer and both servers pin `MGF1-SHA-256`. Change one side
  only and RSA unwrap will fail. (This is the classic Android↔server interop bug —
  already handled here.)
- **Throughput.** The writer flushes per line for durability; for absolute max
  speed, flush on a timer or per batch instead.
- **Web UI viewer** caps rendering at 5,000 rows for responsiveness (copy/download
  still use the full set) — use the filter to narrow large logs.
- **Optional extra hardening:** confine the headless server's `file=` param to a
  whitelisted directory; add a shared-secret header if others can reach your
  loopback; rotate keys per fleet/environment.

## Crypto summary
| Purpose            | Algorithm                                   |
|--------------------|---------------------------------------------|
| Key wrapping       | RSA-3072, OAEP, SHA-256 + MGF1-SHA-256      |
| Line encryption    | AES-256-GCM, 96-bit nonce, 128-bit tag      |
| Nonce              | monotonic counter (fresh key each session)  |
