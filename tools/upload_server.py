#!/usr/bin/env python3
"""
LogDaemon upload server + web log viewer — zero-dependency local endpoint.

The daemon streams each package's .log / .log.tsv to this server in 64 KB
chunks as the session runs. This server writes each chunk at its declared
byte offset (idempotent across retries) and also serves a browser-based
log viewer at http://localhost:<port>/.

Protocol
--------
POST /upload?session=<id>&file=<name>&offset=<n>
    Body: raw bytes. Server seeks to <offset> in uploads/<session>/<file>
    and writes the body there.
    Response: 200 OK.

GET /                           → session browser (HTML)
GET /session?id=<id>            → log viewer for one session (HTML)
GET /api/sessions               → JSON list of sessions
GET /api/session/<id>/packages  → JSON list of packages in session
GET /api/session/<id>/lines     → JSON paginated TSV lines
    ?pkg=<name>&from_line=<n>&limit=<n>
GET /api/session/<id>/summary   → JSON per-package level counts
GET /api/session/<id>/live      → JSON {live: bool}

Run
---
    python3 upload_server.py --port 8080

Then set in the USB drive's log.sinfo:

    [options]
    upload_url=http://<your-pc-ip>:8080/upload
"""

import argparse
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

UPLOAD_DIR = "uploads"
LIVE_THRESHOLD_SEC = 15

_UNSAFE = re.compile(r"[^A-Za-z0-9._-]")


def safe_name(name: str) -> str:
    cleaned = _UNSAFE.sub("_", name)[:200]
    return cleaned or "unnamed"


def _sdir(sid: str) -> str:
    return os.path.join(UPLOAD_DIR, safe_name(sid))


# ── data helpers ──────────────────────────────────────────────────────────────

def list_sessions():
    sessions = []
    if not os.path.isdir(UPLOAD_DIR):
        return sessions
    for name in sorted(os.listdir(UPLOAD_DIR), reverse=True):
        d = os.path.join(UPLOAD_DIR, name)
        if not os.path.isdir(d):
            continue
        pkgs = sorted({
            f[:-8] for f in os.listdir(d)
            if f.endswith(".log.tsv") and not f.startswith("_")
        })
        try:
            mtime = max(
                os.path.getmtime(os.path.join(d, f))
                for f in os.listdir(d)
                if f.endswith(".log.tsv")
            ) if pkgs else os.path.getmtime(d)
        except (ValueError, OSError):
            mtime = os.path.getmtime(d)
        sessions.append({
            "id": name,
            "packages": pkgs,
            "live": (time.time() - mtime) < LIVE_THRESHOLD_SEC,
            "last_updated": int(mtime),
        })
    return sessions


def read_lines(sid: str, pkg: str, from_line: int = 0, limit: int = 2000):
    path = os.path.join(_sdir(sid), safe_name(pkg) + ".log.tsv")
    if not os.path.isfile(path):
        return {"lines": [], "total": 0, "from": from_line, "count": 0}
    rows = []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for i, raw in enumerate(f):
                if i == 0 and raw.startswith("time\t"):
                    continue  # skip header
                rows.append(raw.rstrip("\n"))
    except OSError:
        pass
    total = len(rows)
    start = max(0, from_line)
    chunk = rows[start: start + limit]
    return {"lines": chunk, "total": total, "from": start, "count": len(chunk)}


def session_summary(sid: str):
    summary_path = os.path.join(_sdir(sid), "_summary.tsv")
    result = {}
    if os.path.isfile(summary_path):
        try:
            with open(summary_path, "r", encoding="utf-8", errors="replace") as f:
                for raw in f:
                    parts = raw.rstrip("\n").split("\t")
                    if len(parts) >= 7:
                        pkg = parts[0]
                        result[pkg] = {
                            "V": int(parts[1] or 0),
                            "D": int(parts[2] or 0),
                            "I": int(parts[3] or 0),
                            "W": int(parts[4] or 0),
                            "E": int(parts[5] or 0),
                            "F": int(parts[6] or 0),
                        }
        except (OSError, ValueError):
            pass
    # fall back: scan each tsv if summary absent
    if not result:
        d = _sdir(sid)
        if os.path.isdir(d):
            for fname in os.listdir(d):
                if not fname.endswith(".log.tsv") or fname.startswith("_"):
                    continue
                pkg = fname[:-8]
                counts = {"V": 0, "D": 0, "I": 0, "W": 0, "E": 0, "F": 0}
                try:
                    with open(os.path.join(d, fname), "r",
                              encoding="utf-8", errors="replace") as f:
                        for i, raw in enumerate(f):
                            if i == 0 and raw.startswith("time\t"):
                                continue
                            parts = raw.split("\t")
                            if len(parts) >= 4:
                                lvl = parts[3].strip()
                                if lvl in counts:
                                    counts[lvl] += 1
                except OSError:
                    pass
                result[pkg] = counts
    return result


def session_live(sid: str) -> bool:
    d = _sdir(sid)
    if not os.path.isdir(d):
        return False
    tsv_files = [f for f in os.listdir(d) if f.endswith(".log.tsv")]
    if not tsv_files:
        return False
    try:
        mtime = max(os.path.getmtime(os.path.join(d, f)) for f in tsv_files)
        return (time.time() - mtime) < LIVE_THRESHOLD_SEC
    except OSError:
        return False


# ── HTML pages ────────────────────────────────────────────────────────────────

HTML_INDEX = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LogDaemon</title>
<style>
:root{
  --bg:#0b1120;--surface:#111827;--border:#1f2d40;--border-hi:#3b82f6;
  --text:#e2e8f0;--muted:#4b5c6e;--live-bg:#052e16;--live-fg:#6ee7b7;
  --done-bg:#111827;--done-fg:#4b5c6e;
}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);min-height:100vh}
header{
  padding:16px 28px;border-bottom:1px solid var(--border);
  display:flex;align-items:center;gap:14px;
  background:linear-gradient(180deg,#0f1c2e 0%,var(--bg) 100%);
}
.logo{display:flex;align-items:center;gap:8px}
.logo-icon{width:28px;height:28px;background:linear-gradient(135deg,#1d4ed8,#0ea5e9);
           border-radius:7px;display:flex;align-items:center;justify-content:center;
           font-size:.75rem;font-weight:800;color:#fff;letter-spacing:-.02em;flex-shrink:0}
header h1{font-size:1rem;font-weight:600;color:var(--text)}
header h1 span{color:var(--muted);font-weight:400}
#refresh-info{font-size:.72rem;color:var(--muted);margin-left:auto;display:flex;align-items:center;gap:6px}
.dot{width:6px;height:6px;border-radius:50%;background:#22c55e;animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}

#grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px;padding:24px 28px}
.card{
  background:var(--surface);border:1px solid var(--border);border-radius:12px;
  padding:18px 16px;cursor:pointer;text-decoration:none;color:inherit;display:block;
  transition:border-color .18s,box-shadow .18s,transform .12s;position:relative;overflow:hidden;
}
.card::before{
  content:'';position:absolute;inset:0;border-radius:12px;opacity:0;
  background:radial-gradient(600px circle at var(--mx,50%) var(--my,50%),rgba(59,130,246,.07),transparent 40%);
  transition:opacity .2s;pointer-events:none;
}
.card:hover::before{opacity:1}
.card:hover{border-color:var(--border-hi);box-shadow:0 0 0 1px rgba(59,130,246,.15),0 4px 20px rgba(0,0,0,.4);transform:translateY(-2px)}
.card-head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:12px;gap:8px}
.sid{font-size:.82rem;font-weight:600;color:#cbd5e1;word-break:break-all;line-height:1.4}
.badge{
  font-size:.6rem;font-weight:800;letter-spacing:.08em;padding:3px 9px;border-radius:999px;
  flex-shrink:0;white-space:nowrap;
}
.badge-live{background:var(--live-bg);color:var(--live-fg);border:1px solid #166534;position:relative}
.badge-live::before{
  content:'';display:inline-block;width:5px;height:5px;border-radius:50%;
  background:var(--live-fg);margin-right:5px;vertical-align:middle;animation:pulse 1.5s infinite;
}
.badge-done{background:var(--done-bg);color:var(--done-fg);border:1px solid #1f2d40}
.pkgs{display:flex;flex-wrap:wrap;gap:5px;margin-bottom:12px}
.pkg-chip{
  font-size:.68rem;background:#0d1f35;color:#7dd3fc;
  padding:2px 9px;border-radius:5px;border:1px solid #1e3a5f;
}
.meta-row{display:flex;justify-content:space-between;align-items:center}
.age{font-size:.68rem;color:var(--muted)}
.line-count{font-size:.68rem;color:var(--muted)}
#empty{
  color:var(--muted);text-align:center;padding:100px 28px;
  grid-column:1/-1;
}
#empty p{font-size:.9rem;margin-bottom:8px}
#empty code{font-size:.8rem;color:#60a5fa;background:#0d1f35;padding:3px 8px;border-radius:4px}
</style>
</head>
<body>
<header>
  <div class="logo">
    <div class="logo-icon">LD</div>
    <h1>LogDaemon <span>/ Sessions</span></h1>
  </div>
  <div id="refresh-info"><div class="dot"></div><span id="ts">loading...</span></div>
</header>
<div id="grid"></div>
<script>
function ago(ts){
  const d=Math.floor((Date.now()/1000)-ts);
  if(d<5)return'just now';if(d<60)return d+'s ago';
  if(d<3600)return Math.floor(d/60)+'m ago';
  if(d<86400)return Math.floor(d/3600)+'h ago';
  return Math.floor(d/86400)+'d ago';
}
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
async function refresh(){
  document.getElementById('ts').textContent='Updated '+new Date().toLocaleTimeString();
  const r=await fetch('/api/sessions').catch(()=>null);
  if(!r||!r.ok)return;
  const sessions=await r.json();
  const grid=document.getElementById('grid');
  if(!sessions.length){
    grid.innerHTML=`<div id="empty">
      <p>No sessions yet.</p>
      <p style="margin-bottom:12px;color:#64748b">Start the daemon and plug in a configured USB drive.</p>
      <code>upload_url=http://&lt;your-pc-ip&gt;:8080/upload</code>
    </div>`;
    return;
  }
  grid.innerHTML=sessions.map(s=>`
    <a class="card" href="/session?id=${encodeURIComponent(s.id)}"
       onmousemove="this.style.setProperty('--mx',event.offsetX+'px');this.style.setProperty('--my',event.offsetY+'px')">
      <div class="card-head">
        <div class="sid">${esc(s.id)}</div>
        <span class="badge ${s.live?'badge-live':'badge-done'}">${s.live?'LIVE':'DONE'}</span>
      </div>
      <div class="pkgs">${s.packages.map(p=>`<span class="pkg-chip">${esc(p)}</span>`).join('')||'<span style="font-size:.68rem;color:#4b5c6e">no packages</span>'}</div>
      <div class="meta-row">
        <span class="age">${ago(s.last_updated)}</span>
        <span class="line-count">${s.packages.length} pkg${s.packages.length!==1?'s':''}</span>
      </div>
    </a>`).join('');
}
refresh();
setInterval(refresh,6000);
</script>
</body>
</html>
"""

HTML_VIEWER = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LogDaemon — Viewer</title>
<style>
:root{
  --bg:#0b1120;--surface:#111827;--surface2:#162032;--border:#1f2d40;--border-hi:#3b82f6;
  --text:#e2e8f0;--muted:#4b5c6e;--muted2:#64748b;
  --V:#6b7280;--D:#3b82f6;--I:#16a34a;--W:#ca8a04;--E:#dc2626;--F:#7c3aed;
  --Vt:#9ca3af;--Dt:#60a5fa;--It:#4ade80;--Wt:#fbbf24;--Et:#f87171;--Ft:#a78bfa;
  --Vb:rgba(107,114,128,.07);--Db:rgba(59,130,246,.07);--Ib:rgba(22,163,74,.07);
  --Wb:rgba(202,138,4,.1);--Eb:rgba(220,38,38,.1);--Fb:rgba(124,58,237,.12);
}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);
     display:flex;flex-direction:column;height:100vh;overflow:hidden}

/* ── header ── */
header{
  padding:8px 16px;border-bottom:1px solid var(--border);
  display:flex;align-items:center;gap:10px;flex-shrink:0;
  background:linear-gradient(180deg,#0f1c2e 0%,var(--bg) 100%);
}
.back-btn{
  display:flex;align-items:center;gap:4px;color:#60a5fa;font-size:.78rem;
  text-decoration:none;padding:3px 8px;border-radius:5px;border:1px solid #1e3a5f;
  transition:background .15s;white-space:nowrap;
}
.back-btn:hover{background:#0d1f35}
#sid-label{font-size:.82rem;font-weight:600;color:#cbd5e1;flex:1;
           overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}
.hdr-actions{display:flex;align-items:center;gap:6px;flex-shrink:0}
#live-badge{
  font-size:.6rem;font-weight:800;letter-spacing:.08em;padding:3px 10px;
  border-radius:999px;white-space:nowrap;
}
.badge-live{background:#052e16;color:#6ee7b7;border:1px solid #166534}
.badge-live::before{
  content:'';display:inline-block;width:5px;height:5px;border-radius:50%;
  background:#6ee7b7;margin-right:5px;vertical-align:middle;animation:pulse 1.4s infinite;
}
.badge-done{background:var(--surface);color:var(--muted);border:1px solid var(--border)}
.icon-btn{
  background:var(--surface2);border:1px solid var(--border);border-radius:6px;
  color:var(--muted2);font-size:.72rem;padding:4px 10px;cursor:pointer;
  transition:border-color .15s,color .15s;white-space:nowrap;
}
.icon-btn:hover{border-color:var(--border-hi);color:var(--text)}

/* ── charts panel ── */
#charts{
  display:flex;gap:10px;padding:8px 16px;
  border-bottom:1px solid var(--border);flex-shrink:0;
}
.chart-box{
  background:var(--surface);border:1px solid var(--border);border-radius:8px;
  padding:10px 12px;flex:1;min-width:0;
}
.chart-title{font-size:.6rem;color:var(--muted2);margin-bottom:7px;letter-spacing:.07em;text-transform:uppercase}
.level-bars{display:flex;flex-direction:column;gap:4px}
.lbar{display:flex;align-items:center;gap:7px;font-size:.65rem;cursor:pointer;
      border-radius:4px;padding:1px 3px;transition:background .12s}
.lbar:hover{background:rgba(255,255,255,.04)}
.lbar .lbl{width:9px;font-weight:800;text-align:center}
.lbar .bar-track{flex:1;height:6px;background:#0b1120;border-radius:3px;overflow:hidden}
.lbar .bar-fill{height:100%;border-radius:3px;transition:width .35s cubic-bezier(.4,0,.2,1)}
.lbar .cnt{width:38px;text-align:right;color:var(--muted2);font-variant-numeric:tabular-nums}
#tl-svg{width:100%;height:44px;cursor:crosshair}
#tl-tooltip{
  position:fixed;background:#1e293b;border:1px solid #334155;border-radius:6px;
  padding:4px 10px;font-size:.68rem;color:var(--text);pointer-events:none;
  display:none;z-index:100;white-space:nowrap;
}

/* ── controls bar ── */
#controls{
  display:flex;align-items:center;gap:7px;padding:7px 16px;
  border-bottom:1px solid var(--border);flex-shrink:0;flex-wrap:wrap;
  background:var(--surface);
}
#pkg-tabs{display:flex;gap:5px;flex-wrap:wrap}
.pkg-tab{
  font-size:.73rem;padding:3px 12px;border-radius:999px;cursor:pointer;
  background:var(--bg);color:#94a3b8;border:1px solid var(--border);
  transition:background .15s,border-color .15s,color .15s;
}
.pkg-tab:hover{border-color:#3b5a8a;color:var(--text)}
.pkg-tab.active{background:#1e3a8a;color:#bfdbfe;border-color:#3b82f6}

.sep{width:1px;height:20px;background:var(--border);flex-shrink:0}

.lvl-btn{
  font-size:.7rem;font-weight:800;padding:3px 9px;border-radius:5px;cursor:pointer;
  border:1px solid var(--border);background:var(--bg);color:var(--muted);
  transition:.15s;letter-spacing:.04em;
}
.lvl-btn.on{border-color:transparent;color:#fff}
#lvl-V.on{background:#374151}
#lvl-D.on{background:#1e3a8a}
#lvl-I.on{background:#14532d}
#lvl-W.on{background:#78350f}
#lvl-E.on{background:#7f1d1d}
#lvl-F.on{background:#4c1d95}

.search-wrap{display:flex;align-items:center;gap:0;border:1px solid var(--border);
             border-radius:6px;overflow:hidden;background:var(--bg);transition:border-color .15s}
.search-wrap:focus-within{border-color:var(--border-hi)}
#search{
  font-size:.78rem;padding:4px 10px;background:transparent;
  border:none;color:var(--text);width:190px;outline:none;
}
#search-clear{
  background:transparent;border:none;color:var(--muted);cursor:pointer;
  padding:4px 8px;font-size:.8rem;display:none;
}
#search-clear:hover{color:var(--text)}

#wrap-toggle{
  font-size:.7rem;padding:3px 9px;border-radius:5px;cursor:pointer;
  border:1px solid var(--border);background:var(--bg);color:var(--muted);transition:.15s;
}
#wrap-toggle.on{border-color:#334155;color:var(--text);background:#1e293b}

#count{font-size:.7rem;color:var(--muted);margin-left:auto;white-space:nowrap;
       font-variant-numeric:tabular-nums}
#kb-hint{font-size:.62rem;color:var(--muted);white-space:nowrap;display:flex;gap:4px}
kbd{background:#1e293b;border:1px solid #334155;border-radius:3px;padding:1px 5px;
    font-size:.62rem;color:#94a3b8;font-family:inherit}

/* ── log table ── */
#log-wrap{flex:1;overflow-y:auto;overflow-x:auto;scroll-behavior:auto}
#load-earlier{
  display:none;text-align:center;padding:9px;color:#60a5fa;cursor:pointer;
  font-size:.75rem;border-bottom:1px solid var(--border);
  transition:background .12s;
}
#load-earlier:hover{background:var(--surface2)}
table{width:100%;border-collapse:collapse;font-size:.74rem;font-family:ui-monospace,Consolas,monospace;
      table-layout:fixed}
col.col-time{width:112px}
col.col-pid{width:64px}
col.col-lvl{width:30px}
col.col-tag{width:130px}
col.col-msg{width:auto}
col.col-copy{width:30px}
thead th{
  position:sticky;top:0;z-index:10;
  background:var(--surface);padding:5px 8px;text-align:left;
  font-size:.62rem;color:var(--muted2);letter-spacing:.07em;text-transform:uppercase;
  border-bottom:1px solid var(--border);
}
tbody tr{transition:background .08s}
tbody tr:hover .copy-cell{opacity:1}
tbody tr:hover{filter:brightness(1.12)}
td{padding:2px 8px;border-bottom:1px solid rgba(255,255,255,.03);vertical-align:top;overflow:hidden}
tr.r-V{background:var(--Vb)} tr.r-D{background:var(--Db)} tr.r-I{background:var(--Ib)}
tr.r-W{background:var(--Wb)} tr.r-E{background:var(--Eb)} tr.r-F{background:var(--Fb)}
.c-V{color:var(--Vt)} .c-D{color:var(--Dt)} .c-I{color:var(--It)}
.c-W{color:var(--Wt)} .c-E{color:var(--Et)} .c-F{color:var(--Ft)}
.col-time{color:var(--muted);white-space:nowrap;text-overflow:ellipsis;overflow:hidden}
.col-pid{color:var(--muted);white-space:nowrap;text-align:right}
.col-tag{color:#94a3b8;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}
.col-msg{color:#d4dce8;cursor:pointer;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}
.col-msg.wrap{white-space:pre-wrap;word-break:break-all;overflow:visible}
.col-msg.expanded{white-space:pre-wrap;word-break:break-all;overflow:visible}
mark{background:#854d0e;color:#fef3c7;border-radius:2px;padding:0 1px}
.copy-cell{
  opacity:0;text-align:center;cursor:pointer;color:var(--muted);transition:opacity .12s,color .12s;
  padding:2px 4px;
}
.copy-cell:hover{color:var(--text)}
.copy-cell.copied{color:#4ade80;opacity:1}

/* ── toast ── */
#toast{
  position:fixed;bottom:72px;left:50%;transform:translateX(-50%);
  background:#1e3a8a;border:1px solid #3b82f6;color:#bfdbfe;
  border-radius:8px;padding:7px 16px;font-size:.78rem;
  opacity:0;transition:opacity .25s;pointer-events:none;z-index:200;white-space:nowrap;
}
#toast.show{opacity:1}

/* ── jump-to-bottom FAB ── */
#fab{
  position:fixed;bottom:20px;right:20px;
  background:#1e3a8a;border:1px solid #3b82f6;color:#bfdbfe;
  border-radius:999px;padding:7px 14px;font-size:.75rem;cursor:pointer;
  box-shadow:0 4px 20px rgba(0,0,0,.5);transition:opacity .2s,transform .2s;
  opacity:0;pointer-events:none;z-index:150;
}
#fab.show{opacity:1;pointer-events:auto}
#fab:hover{background:#1d4ed8}

@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
::-webkit-scrollbar{width:7px;height:7px}
::-webkit-scrollbar-track{background:var(--bg)}
::-webkit-scrollbar-thumb{background:#1f2d40;border-radius:4px}
::-webkit-scrollbar-thumb:hover{background:#2d4060}
</style>
</head>
<body>
<header>
  <a class="back-btn" href="/">&#8592; Sessions</a>
  <div id="sid-label"></div>
  <div class="hdr-actions">
    <button class="icon-btn" onclick="exportTsv()" title="Export visible rows as TSV">Export TSV</button>
    <span id="live-badge" class="badge-done">DONE</span>
  </div>
</header>

<div id="charts">
  <div class="chart-box" style="flex:0 0 200px;min-width:0">
    <div class="chart-title">Level Distribution</div>
    <div class="level-bars" id="dist-bars"></div>
  </div>
  <div class="chart-box" style="flex:1;min-width:0">
    <div class="chart-title">Timeline <span id="tl-range" style="color:var(--muted);font-size:.58rem;font-weight:400"></span></div>
    <svg id="tl-svg" preserveAspectRatio="none"></svg>
  </div>
</div>
<div id="tl-tooltip"></div>

<div id="controls">
  <div id="pkg-tabs"></div>
  <div class="sep"></div>
  <button class="lvl-btn on" id="lvl-V" title="Verbose">V</button>
  <button class="lvl-btn on" id="lvl-D" title="Debug">D</button>
  <button class="lvl-btn on" id="lvl-I" title="Info">I</button>
  <button class="lvl-btn on" id="lvl-W" title="Warning">W</button>
  <button class="lvl-btn on" id="lvl-E" title="Error">E</button>
  <button class="lvl-btn on" id="lvl-F" title="Fatal">F</button>
  <div class="sep"></div>
  <div class="search-wrap">
    <input id="search" type="text" placeholder="Search  /  to focus">
    <button id="search-clear" onclick="clearSearch()" title="Clear">&#10005;</button>
  </div>
  <button class="icon-btn" id="wrap-toggle" onclick="toggleWrap()" title="Toggle line wrap">Wrap</button>
  <span id="count"></span>
  <div class="kb-hint" style="display:flex;gap:5px;align-items:center">
    <kbd>/</kbd><span style="font-size:.62rem;color:var(--muted)">search</span>
    <kbd>Esc</kbd><span style="font-size:.62rem;color:var(--muted)">clear</span>
    <kbd>G</kbd><span style="font-size:.62rem;color:var(--muted)">bottom</span>
  </div>
</div>

<div id="log-wrap">
  <div id="load-earlier" onclick="loadEarlier()">&#8593; Load earlier lines</div>
  <table>
    <colgroup>
      <col class="col-time"><col class="col-pid"><col class="col-lvl">
      <col class="col-tag"><col class="col-msg"><col class="col-copy">
    </colgroup>
    <thead><tr>
      <th>Time</th><th style="text-align:right">PID</th><th>L</th>
      <th>Tag</th><th>Message</th><th></th>
    </tr></thead>
    <tbody id="tbody"></tbody>
  </table>
</div>

<div id="toast"></div>
<button id="fab" onclick="jumpBottom()">&#8595; Bottom</button>

<script>
const LEVELS=['V','D','I','W','E','F'];
const LCOLORS={V:'#6b7280',D:'#3b82f6',I:'#16a34a',W:'#ca8a04',E:'#dc2626',F:'#7c3aed'};
const SID=(new URLSearchParams(location.search)).get('id')||'';
let activePkg='';
let packages=[];
let enabledLevels=new Set(LEVELS);
let searchKw='';
let wrapMode=false;
let allRows=[];
let filteredRows=[];
let fromLine=0;
let totalLines=0;
let isLive=false;
let atBottom=true;
let searchTimer=null;
let cachedSummary=null;
let tlBuckets={};
let tlKeys=[];

document.title='LogDaemon — '+SID;
document.getElementById('sid-label').textContent=SID;

// ── helpers ───────────────────────────────────────────────────────────────────
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}

function hi(s,kw){
  if(!kw)return esc(s);
  const idx=s.toLowerCase().indexOf(kw);
  if(idx<0)return esc(s);
  return esc(s.slice(0,idx))+'<mark>'+esc(s.slice(idx,idx+kw.length))+'</mark>'+hi(s.slice(idx+kw.length),kw);
}

function showToast(msg,dur=2200){
  const t=document.getElementById('toast');
  t.textContent=msg;t.classList.add('show');
  clearTimeout(showToast._t);
  showToast._t=setTimeout(()=>t.classList.remove('show'),dur);
}

// ── keyboard shortcuts ────────────────────────────────────────────────────────
document.addEventListener('keydown',e=>{
  if(e.target.tagName==='INPUT')return;
  if(e.key==='/'||e.key==='f'){e.preventDefault();document.getElementById('search').focus();return}
  if(e.key==='g'||e.key==='G'){jumpBottom();return}
  if(e.key==='Escape'){clearSearch();return}
  const map={1:'V',2:'D',3:'I',4:'W',5:'E',6:'F'};
  if(map[e.key]){toggleLevel(map[e.key]);return}
});
document.getElementById('search').addEventListener('keydown',e=>{
  if(e.key==='Escape'){clearSearch();document.getElementById('search').blur()}
});

// ── level toggles ────────────────────────────────────────────────────────────
function toggleLevel(l){
  if(enabledLevels.has(l))enabledLevels.delete(l);else enabledLevels.add(l);
  document.getElementById('lvl-'+l).classList.toggle('on',enabledLevels.has(l));
  applyFilter();
}
LEVELS.forEach(l=>document.getElementById('lvl-'+l).addEventListener('click',()=>toggleLevel(l)));

// click level bar also toggles
document.getElementById('dist-bars').addEventListener('click',e=>{
  const row=e.target.closest('.lbar');
  if(row)toggleLevel(row.dataset.lvl);
});

// ── search ────────────────────────────────────────────────────────────────────
document.getElementById('search').addEventListener('input',e=>{
  clearTimeout(searchTimer);
  const clr=document.getElementById('search-clear');
  clr.style.display=e.target.value?'block':'none';
  searchTimer=setTimeout(()=>{searchKw=e.target.value.toLowerCase();applyFilter();},160);
});

function clearSearch(){
  document.getElementById('search').value='';
  document.getElementById('search-clear').style.display='none';
  searchKw='';applyFilter();
}

// ── wrap toggle ───────────────────────────────────────────────────────────────
function toggleWrap(){
  wrapMode=!wrapMode;
  document.getElementById('wrap-toggle').classList.toggle('on',wrapMode);
  document.querySelectorAll('.col-msg').forEach(td=>td.classList.toggle('wrap',wrapMode));
}

// ── parse TSV line ────────────────────────────────────────────────────────────
function parseLine(raw){
  const p=raw.split('\t');
  return{time:p[0]||'',pid:p[1]||'',lvl:(p[3]||'I').trim(),tag:p[4]||'',msg:p[5]!==undefined?p.slice(5).join('\t'):''};
}

// ── filter + render ───────────────────────────────────────────────────────────
function applyFilter(){
  const kw=searchKw;
  filteredRows=allRows.filter(r=>{
    if(!enabledLevels.has(r.lvl))return false;
    if(kw&&!r.msg.toLowerCase().includes(kw)&&!r.tag.toLowerCase().includes(kw)&&!r.time.includes(kw))return false;
    return true;
  });
  renderTable();
}

function renderTable(){
  const kw=searchKw;
  document.getElementById('count').textContent=filteredRows.length.toLocaleString()+' / '+allRows.length.toLocaleString()+' lines';
  const wc=wrapMode?' wrap':'';
  document.getElementById('tbody').innerHTML=filteredRows.map((r,i)=>`
<tr class="r-${r.lvl}" data-i="${i}">
  <td class="col-time" title="${esc(r.time)}">${esc(r.time)}</td>
  <td class="col-pid">${esc(r.pid)}</td>
  <td class="c-${r.lvl}" style="text-align:center;font-weight:700">${r.lvl}</td>
  <td class="col-tag" title="${esc(r.tag)}">${esc(r.tag)}</td>
  <td class="col-msg${wc}" onclick="toggleExpand(this)">${hi(r.msg,kw)}</td>
  <td class="copy-cell" onclick="copyRow(${i})" title="Copy line">&#9112;</td>
</tr>`).join('');
}

function toggleExpand(td){td.classList.toggle('expanded')}

function copyRow(i){
  const r=filteredRows[i];
  const text=[r.time,r.pid,'',r.lvl,r.tag,r.msg].join('\t');
  navigator.clipboard.writeText(text).then(()=>{
    showToast('Copied to clipboard');
  }).catch(()=>showToast('Copy failed'));
}

// ── export ────────────────────────────────────────────────────────────────────
function exportTsv(){
  const header='time\tpid\ttid\tlevel\ttag\tmessage\n';
  const body=filteredRows.map(r=>[r.time,r.pid,'',r.lvl,r.tag,r.msg].join('\t')).join('\n');
  const blob=new Blob([header+body],{type:'text/tab-separated-values'});
  const a=document.createElement('a');
  a.href=URL.createObjectURL(blob);
  a.download=SID+'_'+activePkg+'_export.tsv';
  a.click();
  showToast('Exported '+filteredRows.length.toLocaleString()+' rows');
}

// ── jump to bottom + FAB ──────────────────────────────────────────────────────
function jumpBottom(){
  const w=document.getElementById('log-wrap');
  w.scrollTop=w.scrollHeight;
}

document.getElementById('log-wrap').addEventListener('scroll',function(){
  const atEnd=this.scrollTop+this.clientHeight>=this.scrollHeight-40;
  atBottom=atEnd;
  document.getElementById('fab').classList.toggle('show',!atEnd);
},{ passive:true });

// ── charts ────────────────────────────────────────────────────────────────────
function renderDist(summary){
  const counts=(summary&&summary[activePkg])||{V:0,D:0,I:0,W:0,E:0,F:0};
  const total=Object.values(counts).reduce((a,b)=>a+b,0)||1;
  document.getElementById('dist-bars').innerHTML=LEVELS.map(l=>{
    const pct=Math.round(counts[l]/total*100);
    const on=enabledLevels.has(l);
    return`<div class="lbar" data-lvl="${l}" style="opacity:${on?1:.4}">
      <span class="lbl c-${l}">${l}</span>
      <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${LCOLORS[l]}"></div></div>
      <span class="cnt">${counts[l].toLocaleString()}</span>
    </div>`;
  }).join('');
}

function buildTimeline(rows){
  tlBuckets={};
  rows.forEach(r=>{
    const m=r.time.length>=14?r.time.substring(0,14):'??';
    tlBuckets[m]=(tlBuckets[m]||0)+1;
  });
  tlKeys=Object.keys(tlBuckets).sort();
}

function renderTimeline(){
  const svg=document.getElementById('tl-svg');
  if(!tlKeys.length){svg.innerHTML='';document.getElementById('tl-range').textContent='';return;}
  const max=Math.max(...tlKeys.map(k=>tlBuckets[k]));
  const W=800,H=44,n=tlKeys.length;
  const bw=Math.max(1,Math.floor(W/Math.max(n,1))-1);
  const rects=tlKeys.map((k,i)=>{
    const h=Math.max(2,Math.round(tlBuckets[k]/max*(H-4)));
    return`<rect x="${i*(bw+1)}" y="${H-h-2}" width="${bw}" height="${h}" fill="#3b82f6" opacity=".75" data-k="${esc(k)}" data-c="${tlBuckets[k]}"/>`;
  }).join('');
  svg.setAttribute('viewBox',`0 0 ${W} ${H}`);
  svg.innerHTML=rects;
  if(tlKeys.length>1)
    document.getElementById('tl-range').textContent=tlKeys[0].trim()+' — '+tlKeys[tlKeys.length-1].trim();

  // hover tooltip
  svg.onmousemove=e=>{
    const tip=document.getElementById('tl-tooltip');
    const rect=e.target.closest('rect');
    if(rect){
      tip.textContent=rect.dataset.k+' • '+Number(rect.dataset.c).toLocaleString()+' lines';
      tip.style.display='block';
      tip.style.left=(e.clientX+12)+'px';
      tip.style.top=(e.clientY-28)+'px';
    }else{tip.style.display='none'}
  };
  svg.onmouseleave=()=>{document.getElementById('tl-tooltip').style.display='none'};
}

// ── data fetching ─────────────────────────────────────────────────────────────
async function loadLines(fromLineIdx,prepend){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${fromLineIdx}&limit=2000`).catch(()=>null);
  if(!res||!res.ok)return;
  const d=await res.json();
  totalLines=d.total;
  const parsed=d.lines.map(parseLine);
  if(prepend){
    allRows=[...parsed,...allRows];
    fromLine=d.from;
  }else{
    allRows=parsed;
    fromLine=d.from;
  }
  document.getElementById('load-earlier').style.display=fromLine>0?'block':'none';
  buildTimeline(allRows);
  renderTimeline();
  applyFilter();
}

async function loadEarlier(){
  const scrollEl=document.getElementById('log-wrap');
  const prevH=scrollEl.scrollHeight;
  await loadLines(Math.max(0,fromLine-2000),true);
  scrollEl.scrollTop=scrollEl.scrollHeight-prevH;
}

async function switchPkg(pkg){
  activePkg=pkg;
  document.querySelectorAll('.pkg-tab').forEach(t=>t.classList.toggle('active',t.dataset.pkg===pkg));
  allRows=[];filteredRows=[];fromLine=0;totalLines=0;
  document.getElementById('tbody').innerHTML='';
  // load last 3000 lines
  const res0=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=0&limit=1`).catch(()=>null);
  if(res0&&res0.ok){
    const d0=await res0.json();
    totalLines=d0.total;
    fromLine=Math.max(0,totalLines-3000);
  }
  await loadLines(fromLine,false);
  if(!cachedSummary)
    cachedSummary=await fetch(`/api/session/${encodeURIComponent(SID)}/summary`).then(r=>r.json()).catch(()=>null);
  renderDist(cachedSummary);
  if(atBottom)jumpBottom();
}

// ── live polling ──────────────────────────────────────────────────────────────
async function livePoll(){
  if(!activePkg)return;
  const lr=await fetch(`/api/session/${encodeURIComponent(SID)}/live`).catch(()=>null);
  const live=(lr&&lr.ok)?(await lr.json()).live:false;
  const badge=document.getElementById('live-badge');
  if(live!==isLive){
    isLive=live;
    badge.className=live?'badge-live':'badge-done';
    badge.textContent=live?'LIVE':'DONE';
    if(!live)showToast('Session complete',3000);
  }
  if(!live)return;
  const tailFrom=totalLines>0?totalLines:0;
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${tailFrom}&limit=500`).catch(()=>null);
  if(!res||!res.ok)return;
  const d=await res.json();
  if(d.count>0){
    const prev=allRows.length;
    totalLines=d.total;
    allRows=[...allRows,...d.lines.map(parseLine)];
    buildTimeline(allRows);
    renderTimeline();
    applyFilter();
    const added=allRows.length-prev;
    if(added>0){
      if(atBottom){jumpBottom();}
      else{showToast('+'+added.toLocaleString()+' new lines');}
    }
  }
}

// ── init ──────────────────────────────────────────────────────────────────────
async function init(){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/packages`).catch(()=>null);
  if(!res||!res.ok){
    document.getElementById('tbody').innerHTML='<tr><td colspan="6" style="text-align:center;padding:40px;color:var(--muted)">Session not found.</td></tr>';
    return;
  }
  packages=await res.json();
  const tabs=document.getElementById('pkg-tabs');
  tabs.innerHTML=packages.map(p=>`<button class="pkg-tab" data-pkg="${esc(p)}" onclick="switchPkg('${esc(p)}')">${esc(p)}</button>`).join('');
  if(packages.length)await switchPkg(packages[0]);
  setInterval(livePoll,3000);
}

init();
</script>
</body>
</html>
"""


# ── HTTP handler ──────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    def _reply(self, code: int, body: bytes, ctype: str = "text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, data, code: int = 200):
        body = json.dumps(data).encode()
        self._reply(code, body, "application/json")

    def log_message(self, *_args):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        p = u.path
        q = parse_qs(u.query)

        if p == "/":
            self._reply(200, HTML_INDEX.encode(), "text/html; charset=utf-8")
            return

        if p == "/session":
            self._reply(200, HTML_VIEWER.encode(), "text/html; charset=utf-8")
            return

        # ── JSON API ──────────────────────────────────────────────────────────

        if p == "/api/sessions":
            self._json(list_sessions())
            return

        # /api/session/<id>/packages|lines|summary|live
        m = re.match(r"^/api/session/([^/]+)/(\w+)$", p)
        if m:
            sid = m.group(1)
            action = m.group(2)

            if action == "packages":
                d = _sdir(sid)
                if not os.path.isdir(d):
                    self._json([])
                    return
                pkgs = sorted({
                    f[:-8] for f in os.listdir(d)
                    if f.endswith(".log.tsv") and not f.startswith("_")
                })
                self._json(pkgs)
                return

            if action == "lines":
                pkg = q.get("pkg", [""])[0]
                try:
                    from_line = int(q.get("from_line", ["0"])[0])
                except ValueError:
                    from_line = 0
                try:
                    limit = min(5000, int(q.get("limit", ["2000"])[0]))
                except ValueError:
                    limit = 2000
                self._json(read_lines(sid, pkg, from_line, limit))
                return

            if action == "summary":
                self._json(session_summary(sid))
                return

            if action == "live":
                self._json({"live": session_live(sid)})
                return

        self._reply(404, b"not found")

    def do_POST(self):
        u = urlparse(self.path)
        if u.path != "/upload":
            self._reply(404, b"not found")
            return

        q = parse_qs(u.query)
        session = safe_name(q.get("session", [""])[0])
        fname = safe_name(q.get("file", [""])[0])
        try:
            offset = int(q.get("offset", ["-1"])[0])
        except ValueError:
            offset = -1

        if not q.get("session") or not q.get("file") or offset < 0:
            self._reply(400, b"missing/invalid session, file, or offset")
            return

        length = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(length) if length > 0 else b""

        session_dir = os.path.join(UPLOAD_DIR, session)
        os.makedirs(session_dir, exist_ok=True)
        path = os.path.join(session_dir, fname)

        fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o644)
        try:
            os.lseek(fd, offset, os.SEEK_SET)
            os.write(fd, data)
        finally:
            os.close(fd)

        print(f"[recv] {session}/{fname}  {len(data):>6} B @ offset {offset}", flush=True)
        self._reply(200, b"OK")


# ── entry point ───────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="LogDaemon upload server + log viewer")
    ap.add_argument("--host", default="0.0.0.0", help="bind address (default: all interfaces)")
    ap.add_argument("--port", type=int, default=8080, help="listen port (default: 8080)")
    ap.add_argument("--dir", default="uploads", help="directory to store uploads (default: ./uploads)")
    args = ap.parse_args()

    global UPLOAD_DIR
    UPLOAD_DIR = args.dir
    os.makedirs(UPLOAD_DIR, exist_ok=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"LogDaemon upload server  ->  http://{args.host}:{args.port}")
    print(f"Storing uploads in       ->  {os.path.abspath(UPLOAD_DIR)}")
    print(f"log.sinfo line           ->  upload_url=http://<your-pc-ip>:{args.port}/upload")
    print(f"Log viewer               ->  http://localhost:{args.port}/")
    print("Press Ctrl+C to stop.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")


if __name__ == "__main__":
    main()
