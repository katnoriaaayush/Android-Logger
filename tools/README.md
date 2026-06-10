# LogDaemon Tools

## upload_server.py

A zero-dependency local HTTP server that receives logs uploaded by the native
daemon when `upload_url` is set in the USB drive's `log.sinfo`.

### Run

```bash
python3 upload_server.py --port 8080
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--host` | `0.0.0.0` | Bind address (all interfaces). |
| `--port` | `8080` | Listen port. |
| `--dir` | `uploads` | Directory where received files are stored. |

Requires only Python 3 standard library — no `pip install`.

### Configure the device

Find the PC's LAN IP:

- **Windows:** `ipconfig` → IPv4 Address
- **macOS / Linux:** `ip addr` or `ifconfig`

Add the matching URL to the USB drive's `log.sinfo`:

```ini
[options]
upload_url=http://<your-pc-ip>:8080/upload
```

The phone and PC must be on the same network. Plug the USB into the device; the
daemon streams logs to the server during the session and finalises the upload on
ejection, then shows an on-device notification with the result.

### Where uploads land

```
uploads/
  2026-06-10_10-00-00/            # session id (matches the device session dir)
    com.example.app.log
    com.example.app.log.tsv
```

Each chunk is written at its declared byte offset, so retried chunks are
idempotent and the reassembled files are byte-identical to the device copies.

### Protocol

```
POST /upload?session=<id>&file=<name>&offset=<n>
Body: raw chunk bytes  →  written at byte <n> of uploads/<id>/<name>
Response: 200 OK

GET /     →  health check (prints the uploads directory)
```

Filenames and session ids are sanitised server-side (path traversal is blocked).
This server speaks plain HTTP only, matching the daemon's `http://` upload path.
