# LogDaemon Tools

## upload_server.py

A zero-dependency local HTTP server that receives logs uploaded by the native
daemon when `upload_url` is set in the USB drive's `log.sinfo`. It also serves
a browser-based log viewer at the same port.

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

### Log viewer

Open `http://localhost:8080/` in a browser after starting the server. The viewer
shows all received sessions. Click a session card to open the log viewer for it.

Viewer features:

- **Session browser** — cards with LIVE / DONE badge, package list, age; auto-refreshes every 6 s.
- **Package tabs** — switch between packages captured in a session.
- **Level filter** — toggle V / D / I / W / E / F individually.
- **Keyword search** — filters by tag or message with 180 ms debounce.
- **Level distribution chart** — horizontal bar per level with count.
- **Timeline histogram** — SVG bar chart bucketed by minute.
- **Colour-coded log table** — rows and level column tinted per level; click a message cell to expand/collapse.
- **Live tail** — polls every 3 s while the session is active and auto-scrolls to new lines.
- **Pagination** — loads the last 2 000 lines initially; "Load earlier lines" pages back in 2 000-line chunks.

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

GET /                                    →  session browser (HTML)
GET /session?id=<id>                     →  log viewer for session (HTML)
GET /api/sessions                        →  JSON session list
GET /api/session/<id>/packages           →  JSON package list
GET /api/session/<id>/lines              →  JSON paginated TSV lines
    ?pkg=<name>&from_line=<n>&limit=<n>
GET /api/session/<id>/summary            →  JSON per-package level counts
GET /api/session/<id>/live               →  JSON {live: bool}
```

Filenames and session ids are sanitised server-side (path traversal is blocked).
This server speaks plain HTTP only, matching the daemon's `http://` upload path.
