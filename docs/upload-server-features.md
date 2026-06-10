# LogDaemon Upload Server — Web Viewer Features

## Overview

`tools/upload_server.py` is a zero-dependency Python 3 server that does two things:

1. **Receives** chunked log uploads from the native daemon over HTTP.
2. **Serves** a browser-based log viewer at the same port.

Run it with:

```bash
python3 tools/upload_server.py --port 8080
```

Then open `http://localhost:8080/` in any browser.

---

## Session Browser (`/`)

The landing page lists every session that has been received.

| Feature | Detail |
|---|---|
| **Session cards** | One card per session directory. Shows session ID, package list, and age. |
| **LIVE / DONE badge** | A session is LIVE if any of its `.log.tsv` files were written to within the last 15 seconds. The LIVE badge has an animated pulsing dot. |
| **Package chips** | Each captured package is shown as a coloured chip on the card. |
| **Age display** | Human-readable relative time: "just now", "3m ago", "2h ago", etc. |
| **Auto-refresh** | The grid polls `/api/sessions` every 6 seconds — no manual refresh needed. |
| **Spotlight hover** | A radial gradient follows the mouse cursor over each card for a subtle depth effect. |
| **Click to open** | Clicking any card navigates to the log viewer for that session. |
| **Empty state** | Friendly message with setup instructions when no sessions exist yet. |

---

## Log Viewer (`/session?id=<session-id>`)

The main analysis view for a single session.

### Header Bar

| Element | Detail |
|---|---|
| **Back button** | Returns to the session browser. |
| **Session ID** | Truncated with ellipsis if long; full ID shown in the page title. |
| **LIVE / DONE badge** | Reflects real-time session status; transitions with a toast notification. |
| **Export TSV button** | Downloads all currently-visible (filtered) rows as a `.tsv` file named `<session>_<pkg>_export.tsv`. |

---

### Charts Panel

#### Level Distribution

A horizontal bar chart showing the breakdown of log levels for the active package.

- Six bars: V · D · I · W · E · F, each coloured with the level's colour.
- Bar width is proportional to share of total lines.
- Count shown on the right of each bar.
- **Clicking a level bar toggles that level on/off** (same as the level buttons in the controls bar).
- Bars dim when their level is disabled.

#### Timeline Histogram

A per-minute bar chart of log volume over the session duration.

- Each bar represents one clock minute (bucketed from the `time` column).
- Bar height is proportional to line count in that minute, normalised to the peak minute.
- **Hover tooltip** shows the minute label and exact line count for any bar.
- Time range label displays the first and last minute in the chart.
- Updates incrementally as new lines arrive during a live session.

---

### Controls Bar

#### Package Tabs

- One tab per package captured in the session.
- Clicking a tab switches the viewer to that package's log file.
- Active tab is highlighted in blue.

#### Level Filter Buttons

Six toggle buttons — **V D I W E F** — one per Android log level.

- Active levels are highlighted with their level colour.
- Inactive levels are greyed out.
- Toggling re-filters the table instantly without a network request.
- Keyboard shortcuts `1`–`6` toggle V through F respectively.

#### Search Bar

- Filters rows by case-insensitive substring match against the **message** and **tag** columns (and timestamp).
- **160 ms debounce** — the filter fires shortly after typing stops, not on every keystroke.
- Matched substrings are **highlighted in amber** (`<mark>`) inside the table cells.
- A clear (×) button appears while text is present.
- Press `/` or `F` (when not typing) to focus the search bar.
- Press `Esc` to clear the search and blur the input.

#### Wrap Toggle

- Toggles the message column between **nowrap/ellipsis** (default) and **pre-wrap word-break** (wrap mode).
- Applies to all rows simultaneously.

#### Line Count

Shows `<visible> / <total> lines` — updates on every filter change.

---

### Log Table

A fixed-layout table with six columns:

| Column | Content |
|---|---|
| **Time** | Timestamp from logcat (`MM-DD HH:MM:SS.mmm`). Ellipsis if the column is too narrow. |
| **PID** | Process ID, right-aligned. |
| **L** | Single-letter log level, bold, coloured. |
| **Tag** | Log tag, truncated with ellipsis; full tag on hover via `title`. |
| **Message** | Log message. Collapsed to one line by default (ellipsis). Click to **expand** the full message in-place. Click again to collapse. Wrap mode overrides this per-row expansion globally. |
| *(copy)* | Copy icon. Invisible until the row is hovered, then fades in. |

#### Row colours

Each row carries a tinted background based on its level:

| Level | Tint |
|---|---|
| V | Subtle grey |
| D | Subtle blue |
| I | Subtle green |
| W | Subtle amber |
| E | Subtle red |
| F | Subtle purple |

#### Hover copy button

- Appears when hovering any row.
- Copies the full row as a tab-separated string (`time\tpid\t\tlevel\ttag\tmessage`) to the clipboard.
- Shows a "Copied to clipboard" toast on success.

#### Pagination — Load Earlier

- On first open the viewer loads the **last 3 000 lines** of the selected package.
- A **"↑ Load earlier lines"** bar appears at the top of the table when earlier lines exist.
- Clicking it prepends the previous 2 000 lines while **preserving scroll position** (the view does not jump).

---

### Live Tail

While a session is active:

- The viewer polls `/api/session/<id>/live` and `/api/session/<id>/lines` every **3 seconds**.
- New lines are **appended** to the bottom of the table.
- If the viewport is at the bottom, it **auto-scrolls** to follow new lines.
- If the viewport is scrolled up, a **"+N new lines"** toast appears instead of forcing a scroll.
- When the session transitions from LIVE to DONE a **"Session complete"** toast is shown.

---

### Jump-to-Bottom FAB

A floating action button labelled **"↓ Bottom"** appears in the bottom-right corner whenever the log table is scrolled up. Clicking it scrolls instantly to the last line. It hides automatically when at the bottom.

---

### Toast Notifications

Short non-blocking messages that fade in/out at the bottom-centre of the screen:

| Trigger | Message |
|---|---|
| Row copy button | "Copied to clipboard" |
| Export button | "Exported N rows" |
| New live lines while scrolled up | "+N new lines" |
| Session ends | "Session complete" |

---

## Keyboard Shortcuts

| Key | Action |
|---|---|
| `/` or `F` | Focus the search input |
| `Esc` | Clear search and blur input |
| `G` | Jump to bottom of log table |
| `1` | Toggle Verbose (V) |
| `2` | Toggle Debug (D) |
| `3` | Toggle Info (I) |
| `4` | Toggle Warning (W) |
| `5` | Toggle Error (E) |
| `6` | Toggle Fatal (F) |

---

## HTTP API

All JSON endpoints used by the UI:

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Session browser (HTML) |
| `GET` | `/session?id=<id>` | Log viewer for one session (HTML) |
| `GET` | `/api/sessions` | List of all sessions with metadata |
| `GET` | `/api/session/<id>/packages` | List of packages in a session |
| `GET` | `/api/session/<id>/lines?pkg=X&from_line=N&limit=N` | Paginated TSV lines (max 5 000 per call) |
| `GET` | `/api/session/<id>/summary` | Per-package level counts |
| `GET` | `/api/session/<id>/live` | `{live: bool}` based on file mtime |
| `POST` | `/upload?session=X&file=Y&offset=N` | Receive a chunk from the daemon |

---

## Upload Protocol

The native daemon sends each log file in 64 KB chunks:

```
POST /upload?session=<id>&file=<name>&offset=<n>
Body: raw bytes
Response: 200 OK
```

- Each chunk is written at its declared byte offset — retried chunks are idempotent.
- Session IDs and filenames are sanitised server-side to block path traversal.
- Files land in `uploads/<session-id>/<filename>`.

---

## Dependencies

None. Requires only the Python 3 standard library (`http.server`, `urllib.parse`, `json`, `os`, `re`, `time`, `argparse`).
