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

GET /                           -> session browser (HTML)
GET /session?id=<id>            -> log viewer for one session (HTML)
GET /api/sessions               -> JSON list of sessions
GET /api/session/<id>/packages  -> JSON list of packages in session
GET /api/session/<id>/lines     -> JSON paginated TSV lines
    ?pkg=<name>&from_line=<n>&limit=<n>
GET /api/session/<id>/summary   -> JSON per-package level counts
GET /api/session/<id>/live      -> JSON {live: bool}

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

# Session browser — Samsung One UI dark design language
HTML_INDEX = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LogDaemon</title>
<style>
:root{
  --bg:#1c1c1e;--sf:#2c2c2e;--sf2:#3a3a3c;
  --bd:#38383a;--bhi:#0381fe;
  --tx:#ebebf5;--tx2:#98989f;--tx3:#636366;
  --blue:#0381fe;--bdim:rgba(3,129,254,.14);
  --green:#30d158;--gdim:rgba(48,209,88,.12);
  --r:20px;
}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,system-ui,sans-serif;background:var(--bg);color:var(--tx);
     min-height:100vh;-webkit-font-smoothing:antialiased}
header{
  background:var(--sf);border-bottom:1px solid var(--bd);
  padding:0 24px;height:56px;
  display:flex;align-items:center;gap:12px;
  position:sticky;top:0;z-index:50;
}
.hdr-ic{
  width:32px;height:32px;border-radius:10px;flex-shrink:0;
  background:linear-gradient(145deg,#0381fe,#32aaff);
  display:flex;align-items:center;justify-content:center;
}
.hdr-ic svg{width:18px;height:18px;fill:none;stroke:#fff;stroke-width:2;stroke-linecap:round}
.hdr-t{font-size:.95rem;font-weight:700;letter-spacing:-.01em}
.hdr-s{font-size:.8rem;color:var(--tx2);font-weight:400;margin-left:4px}
.hdr-r{margin-left:auto;display:flex;align-items:center;gap:8px}
.sdot{width:7px;height:7px;border-radius:50%;background:var(--green);
      box-shadow:0 0 0 2px var(--gdim);animation:blink 2.4s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.3}}
#ts{font-size:.72rem;color:var(--tx3)}
.toolbar{padding:16px 24px 0;display:flex;align-items:center;gap:10px}
.sec-lbl{font-size:.72rem;font-weight:600;color:var(--tx2);letter-spacing:.06em;text-transform:uppercase}
.cpill{background:var(--sf2);border-radius:999px;padding:2px 9px;font-size:.68rem;color:var(--tx2)}
#grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(310px,1fr));gap:12px;padding:14px 24px 32px}
.card{
  background:var(--sf);border:1px solid var(--bd);border-radius:var(--r);
  padding:18px 20px 16px;cursor:pointer;text-decoration:none;color:inherit;display:block;
  transition:transform .18s cubic-bezier(.34,1.56,.64,1),box-shadow .18s,border-color .18s;
  position:relative;overflow:hidden;
}
.card:hover{
  transform:translateY(-3px) scale(1.005);
  box-shadow:0 8px 30px rgba(0,0,0,.45),0 0 0 1px rgba(3,129,254,.25);
  border-color:var(--bhi);
}
.card.live{border-top:2px solid var(--blue);padding-top:17px}
.card.live::after{
  content:'';position:absolute;top:0;left:0;right:0;height:2px;
  background:linear-gradient(90deg,transparent,#5eb5ff,transparent);
  animation:scan 2.8s linear infinite;
}
@keyframes scan{from{transform:translateX(-100%)}to{transform:translateX(100%)}}
.ct{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;margin-bottom:12px}
.sid{font-size:.78rem;font-weight:600;word-break:break-all;line-height:1.5;
     font-family:ui-monospace,Consolas,monospace}
.badge{flex-shrink:0;border-radius:999px;font-size:.58rem;font-weight:800;
       letter-spacing:.1em;padding:3px 10px;white-space:nowrap;text-transform:uppercase}
.bl{background:var(--bdim);color:#5eb5ff;border:1px solid rgba(3,129,254,.35)}
.bl::before{
  content:'';display:inline-block;width:5px;height:5px;border-radius:50%;
  background:#5eb5ff;margin-right:6px;vertical-align:middle;animation:blink 1.4s infinite;
}
.bd{background:rgba(255,255,255,.05);color:var(--tx3);border:1px solid var(--bd)}
.pkgs{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px}
.pc{font-size:.67rem;font-weight:500;
    background:rgba(3,129,254,.1);color:#7ec8ff;
    padding:3px 10px;border-radius:8px;border:1px solid rgba(3,129,254,.2);
    max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cf{display:flex;justify-content:space-between;align-items:center;
    border-top:1px solid var(--bd);padding-top:12px}
.age{font-size:.68rem;color:var(--tx3);display:flex;align-items:center;gap:5px}
.age svg{width:12px;height:12px;stroke:currentColor;fill:none;stroke-width:1.5}
.pkc{font-size:.68rem;color:var(--tx3)}
#empty{grid-column:1/-1;display:flex;flex-direction:column;align-items:center;
       padding:80px 24px;gap:10px;text-align:center}
.eic{width:56px;height:56px;border-radius:16px;background:var(--sf2);
     display:flex;align-items:center;justify-content:center;margin-bottom:6px}
.eic svg{width:28px;height:28px;stroke:var(--tx3);fill:none;stroke-width:1.5}
#empty h2{font-size:.95rem;font-weight:600;color:var(--tx2)}
#empty p{font-size:.78rem;color:var(--tx3);max-width:300px;line-height:1.6}
.cline{margin-top:4px;background:var(--sf);border:1px solid var(--bd);border-radius:10px;
       padding:8px 14px;font-size:.72rem;font-family:ui-monospace,Consolas,monospace;
       color:#7ec8ff;display:flex;align-items:center;gap:8px}
::-webkit-scrollbar{width:6px}
::-webkit-scrollbar-track{background:var(--bg)}
::-webkit-scrollbar-thumb{background:var(--sf2);border-radius:3px}
</style>
</head>
<body>
<header>
  <div class="hdr-ic">
    <svg viewBox="0 0 24 24"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
  </div>
  <span class="hdr-t">LogDaemon<span class="hdr-s">Sessions</span></span>
  <div class="hdr-r"><div class="sdot"></div><span id="ts"></span></div>
</header>
<div class="toolbar">
  <span class="sec-lbl">Sessions</span>
  <span class="cpill" id="cpill">0</span>
</div>
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
  document.getElementById('ts').textContent=new Date().toLocaleTimeString();
  const r=await fetch('/api/sessions').catch(()=>null);
  if(!r||!r.ok)return;
  const ss=await r.json();
  document.getElementById('cpill').textContent=ss.length;
  const g=document.getElementById('grid');
  if(!ss.length){
    g.innerHTML=`<div id="empty">
      <div class="eic"><svg viewBox="0 0 24 24"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg></div>
      <h2>No sessions yet</h2>
      <p>Start the daemon and plug in a USB drive with this line in log.sinfo:</p>
      <div class="cline">
        <svg viewBox="0 0 24 24" style="width:13px;height:13px;flex-shrink:0"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        upload_url=http://&lt;your-pc-ip&gt;:8080/upload
      </div>
    </div>`;
    return;
  }
  g.innerHTML=ss.map(s=>`
    <a class="card${s.live?' live':''}" href="/session?id=${encodeURIComponent(s.id)}">
      <div class="ct">
        <div class="sid">${esc(s.id)}</div>
        <span class="badge ${s.live?'bl':'bd'}">${s.live?'Live':'Done'}</span>
      </div>
      <div class="pkgs">${s.packages.length
        ?s.packages.map(p=>`<span class="pc">${esc(p)}</span>`).join('')
        :'<span style="font-size:.67rem;color:var(--tx3)">No packages captured</span>'}</div>
      <div class="cf">
        <span class="age">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          ${ago(s.last_updated)}
        </span>
        <span class="pkc">${s.packages.length} pkg${s.packages.length!==1?'s':''}</span>
      </div>
    </a>`).join('');
}
refresh();
setInterval(refresh,6000);
</script>
</body>
</html>
"""

# Log viewer — Android Studio Logcat layout + Samsung One UI chrome
HTML_VIEWER = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Logcat</title>
<style>
/* ── Design tokens ── */
:root{
  --bg:#1c1c1e;--sf:#2c2c2e;--sf2:#3a3a3c;
  --bd:#38383a;--bhi:#0381fe;
  --tx:#ebebf5;--tx2:#98989f;--tx3:#636366;
  --blue:#0381fe;
  /* Android Studio Logcat level palette */
  --Vc:#8e8e93;--Vt:#aeaeb2;--Vbg:rgba(142,142,147,.07);
  --Dc:#5b9aff;--Dt:#7eb2ff;--Dbg:rgba(91,154,255,.07);
  --Ic:#30d158;--It:#5dde7f;--Ibg:rgba(48,209,88,.07);
  --Wc:#ffd60a;--Wt:#ffe44d;--Wbg:rgba(255,214,10,.08);
  --Ec:#ff453a;--Et:#ff6961;--Ebg:rgba(255,69,58,.08);
  --Fc:#bf5af2;--Ft:#d083f5;--Fbg:rgba(191,90,242,.1);
}
*{box-sizing:border-box;margin:0;padding:0}
body{
  font-family:-apple-system,system-ui,sans-serif;
  background:var(--bg);color:var(--tx);
  display:flex;flex-direction:column;height:100vh;overflow:hidden;
  -webkit-font-smoothing:antialiased;
}

/* ── Top bar ── */
#topbar{
  height:48px;background:var(--sf);border-bottom:1px solid var(--bd);
  display:flex;align-items:center;gap:0;flex-shrink:0;padding:0 6px;
}
.tb-back{
  display:flex;align-items:center;gap:5px;padding:6px 10px;border-radius:8px;
  color:var(--blue);font-size:.8rem;font-weight:500;text-decoration:none;
  transition:background .15s;white-space:nowrap;
}
.tb-back:hover{background:rgba(3,129,254,.12)}
.tb-back svg{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:2.2;
             stroke-linecap:round;stroke-linejoin:round;flex-shrink:0}
.tb-div{width:1px;height:22px;background:var(--bd);margin:0 6px;flex-shrink:0}
#sid-label{
  font-size:.76rem;font-weight:600;flex:1;min-width:0;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
  padding:0 6px;font-family:ui-monospace,Consolas,monospace;color:var(--tx);
}
.tb-acts{display:flex;align-items:center;gap:4px;padding-right:8px;flex-shrink:0}
.tb-btn{
  display:flex;align-items:center;gap:5px;background:transparent;border:none;
  color:var(--tx2);padding:5px 10px;border-radius:7px;font-size:.72rem;
  cursor:pointer;transition:background .15s,color .15s;white-space:nowrap;
}
.tb-btn:hover{background:var(--sf2);color:var(--tx)}
.tb-btn svg{width:14px;height:14px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round}
#live-badge{
  display:flex;align-items:center;gap:5px;
  font-size:.6rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;
  padding:4px 11px;border-radius:999px;white-space:nowrap;border:1px solid;
}
.bl{background:rgba(48,209,88,.1);color:#5dde7f;border-color:rgba(48,209,88,.3)}
.bl .dot{width:5px;height:5px;border-radius:50%;background:#30d158;
         animation:blink 1.4s infinite;flex-shrink:0}
.bd-badge{background:rgba(255,255,255,.05);color:var(--tx3);border-color:var(--bd)}

/* ── Charts accordion ── */
#charts-wrap{flex-shrink:0;border-bottom:1px solid var(--bd)}
#ch-tog{
  display:flex;align-items:center;gap:7px;width:100%;background:var(--sf);
  border:none;border-bottom:1px solid var(--bd);cursor:pointer;
  padding:6px 14px;color:var(--tx2);font-size:.68rem;font-weight:600;
  letter-spacing:.06em;text-transform:uppercase;transition:background .15s;
}
#ch-tog:hover{background:var(--sf2)}
#ch-tog svg{width:12px;height:12px;stroke:currentColor;fill:none;stroke-width:2;transition:transform .2s}
#ch-tog.open svg{transform:rotate(180deg)}
#ch-inner{display:none;padding:10px 12px;gap:10px;flex-direction:row;background:var(--sf)}
#ch-inner.open{display:flex}
.cc{background:var(--bg);border:1px solid var(--bd);border-radius:10px;padding:10px 14px;flex:1;min-width:0}
.clbl{font-size:.58rem;color:var(--tx3);letter-spacing:.07em;text-transform:uppercase;
      margin-bottom:8px;font-weight:600}
.dr{display:flex;align-items:center;gap:8px;font-size:.63rem;padding:2px 4px;
    border-radius:5px;cursor:pointer;transition:background .12s}
.dr:hover{background:rgba(255,255,255,.05)}
.dr.off{opacity:.32}
.dlbl{width:10px;font-weight:800;text-align:center;font-size:.67rem}
.dtrack{flex:1;height:5px;background:rgba(255,255,255,.08);border-radius:3px;overflow:hidden}
.dfill{height:100%;border-radius:3px;transition:width .38s cubic-bezier(.4,0,.2,1)}
.dcnt{width:44px;text-align:right;color:var(--tx3);font-variant-numeric:tabular-nums;font-size:.61rem}
#tl-svg{width:100%;height:40px;cursor:crosshair;display:block}
#tl-range{font-size:.58rem;color:var(--tx3);margin-bottom:5px}
#tl-tip{
  position:fixed;background:var(--sf);border:1px solid var(--bd);border-radius:8px;
  padding:5px 11px;font-size:.68rem;color:var(--tx);pointer-events:none;
  display:none;z-index:200;white-space:nowrap;box-shadow:0 4px 16px rgba(0,0,0,.4);
}

/* ── Controls toolbar (Logcat style) ── */
#controls{
  height:38px;background:var(--sf);border-bottom:1px solid var(--bd);
  display:flex;align-items:center;flex-shrink:0;padding:0 8px;overflow-x:auto;gap:0;
}
#controls::-webkit-scrollbar{height:0}
#pkg-tabs{
  display:flex;align-items:center;gap:3px;flex-shrink:0;
  padding-right:8px;margin-right:6px;border-right:1px solid var(--bd);
}
.ptab{
  font-size:.68rem;font-weight:500;padding:3px 11px;border-radius:999px;cursor:pointer;
  background:transparent;color:var(--tx3);border:1px solid transparent;
  transition:background .14s,color .14s,border-color .14s;white-space:nowrap;
}
.ptab:hover{background:var(--sf2);color:var(--tx2)}
.ptab.active{background:rgba(3,129,254,.14);color:#7ec8ff;border-color:rgba(3,129,254,.32)}
.lchips{display:flex;align-items:center;gap:3px;padding:0 6px;flex-shrink:0}
.lc{
  font-size:.62rem;font-weight:800;letter-spacing:.05em;
  padding:2px 7px;border-radius:5px;cursor:pointer;text-transform:uppercase;
  border:1px solid var(--bd);background:transparent;color:var(--tx3);transition:.14s;
}
.lc:hover{background:var(--sf2);color:var(--tx2)}
.lc.on{border-color:transparent}
.lc-V.on{background:rgba(142,142,147,.2);color:var(--Vt)}
.lc-D.on{background:rgba(91,154,255,.18);color:var(--Dt)}
.lc-I.on{background:rgba(48,209,88,.18);color:var(--It)}
.lc-W.on{background:rgba(255,214,10,.18);color:var(--Wt)}
.lc-E.on{background:rgba(255,69,58,.18);color:var(--Et)}
.lc-F.on{background:rgba(191,90,242,.18);color:var(--Ft)}
.csep{width:1px;height:18px;background:var(--bd);margin:0 5px;flex-shrink:0}
.sbox{
  display:flex;align-items:center;border:1px solid var(--bd);border-radius:7px;
  background:rgba(255,255,255,.04);overflow:hidden;transition:border-color .15s;flex-shrink:0;
}
.sbox:focus-within{border-color:var(--blue);background:rgba(3,129,254,.05)}
.sbox svg{width:13px;height:13px;stroke:var(--tx3);fill:none;stroke-width:1.8;
          margin-left:8px;flex-shrink:0}
#search{
  background:transparent;border:none;outline:none;color:var(--tx);
  font-size:.74rem;padding:4px 8px;width:170px;
}
#search::placeholder{color:var(--tx3)}
#src-clr{
  background:transparent;border:none;color:var(--tx3);cursor:pointer;
  padding:4px 7px;font-size:.7rem;display:none;transition:color .12s;
}
#src-clr:hover{color:var(--tx)}
.cr{margin-left:auto;display:flex;align-items:center;gap:6px;flex-shrink:0;padding-left:6px}
.wbtn{
  background:transparent;border:1px solid var(--bd);border-radius:5px;
  color:var(--tx3);font-size:.63rem;padding:2px 8px;cursor:pointer;transition:.14s;
  white-space:nowrap;
}
.wbtn:hover{color:var(--tx2);background:var(--sf2)}
.wbtn.on{background:rgba(3,129,254,.14);color:#7ec8ff;border-color:rgba(3,129,254,.32)}
#lcount{font-size:.67rem;color:var(--tx3);white-space:nowrap;font-variant-numeric:tabular-nums}

/* ── Log table (Logcat DNA) ── */
#log-wrap{flex:1;overflow-y:auto;overflow-x:auto}
#load-earlier{
  display:none;text-align:center;padding:7px;color:var(--blue);cursor:pointer;
  font-size:.7rem;border-bottom:1px solid var(--bd);background:var(--sf);
  transition:background .12s;
}
#load-earlier:hover{background:var(--sf2)}
table{
  width:100%;border-collapse:collapse;
  font-size:.71rem;font-family:ui-monospace,Consolas,'Courier New',monospace;
  table-layout:fixed;
}
/* column widths */
col.c0{width:3px}   /* level accent bar */
col.c1{width:112px} /* time */
col.c2{width:56px}  /* pid */
col.c3{width:24px}  /* level badge */
col.c4{width:126px} /* tag */
col.c5{width:auto}  /* message */
col.c6{width:26px}  /* copy */
thead th{
  position:sticky;top:0;z-index:10;background:var(--sf);
  padding:3px 6px;text-align:left;
  font-size:.57rem;color:var(--tx3);letter-spacing:.08em;
  text-transform:uppercase;font-weight:600;
  border-bottom:1px solid var(--bd);
}
thead th:first-child{padding:0}
tbody tr:hover{filter:brightness(1.15)}
tbody tr:hover .cpbtn{opacity:.65}
/* 3-pixel left accent — the Logcat signature */
td.bar{padding:0;width:3px;min-width:3px}
tr.rV td.bar{background:var(--Vc)} tr.rD td.bar{background:var(--Dc)}
tr.rI td.bar{background:var(--Ic)} tr.rW td.bar{background:var(--Wc)}
tr.rE td.bar{background:var(--Ec)} tr.rF td.bar{background:var(--Fc)}
tr.rV{background:var(--Vbg)} tr.rD{background:var(--Dbg)} tr.rI{background:var(--Ibg)}
tr.rW{background:var(--Wbg)} tr.rE{background:var(--Ebg)} tr.rF{background:var(--Fbg)}
td{padding:2px 6px;border-bottom:1px solid rgba(255,255,255,.022);
   vertical-align:top;overflow:hidden}
.ttime{color:var(--tx3);white-space:nowrap;text-overflow:ellipsis;overflow:hidden;font-size:.67rem}
.tpid{color:var(--tx3);white-space:nowrap;text-align:right;font-size:.67rem}
/* level badge — rounded rect with level color */
.lvb{
  display:inline-block;width:17px;height:17px;border-radius:5px;
  text-align:center;line-height:17px;font-size:.6rem;font-weight:900;
}
.lvV{background:rgba(142,142,147,.24);color:var(--Vt)}
.lvD{background:rgba(91,154,255,.24);color:var(--Dt)}
.lvI{background:rgba(48,209,88,.24);color:var(--It)}
.lvW{background:rgba(255,214,10,.24);color:var(--Wt)}
.lvE{background:rgba(255,69,58,.24);color:var(--Et)}
.lvF{background:rgba(191,90,242,.24);color:var(--Ft)}
.ttag{color:var(--tx2);white-space:nowrap;text-overflow:ellipsis;overflow:hidden;font-size:.69rem}
.tmsg{color:var(--tx);cursor:pointer;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}
.tmsg.wrap{white-space:pre-wrap;word-break:break-all;overflow:visible}
.tmsg.exp{white-space:pre-wrap;word-break:break-all;overflow:visible}
mark{background:rgba(255,214,10,.28);color:#ffe44d;border-radius:2px;padding:0 1px}
.cpbtn{opacity:0;text-align:center;cursor:pointer;color:var(--tx3);
       transition:opacity .12s,color .12s;padding:2px 3px}
.cpbtn:hover{color:var(--tx)}
.erow td{text-align:center;padding:36px;color:var(--tx3);font-family:system-ui;font-size:.8rem}

/* ── Toast ── */
#toast{
  position:fixed;bottom:66px;left:50%;transform:translateX(-50%);
  background:rgba(44,44,46,.96);border:1px solid var(--bd);color:var(--tx);
  border-radius:12px;padding:7px 18px;font-size:.75rem;
  opacity:0;transition:opacity .22s;pointer-events:none;z-index:300;
  white-space:nowrap;backdrop-filter:blur(12px);
  box-shadow:0 4px 24px rgba(0,0,0,.5);
}
#toast.show{opacity:1}

/* ── FAB ── */
#fab{
  position:fixed;bottom:16px;right:16px;
  background:var(--blue);color:#fff;border:none;border-radius:14px;
  padding:9px 16px;font-size:.73rem;font-weight:600;cursor:pointer;
  box-shadow:0 4px 20px rgba(3,129,254,.4);
  transition:opacity .2s,transform .2s;
  opacity:0;pointer-events:none;z-index:200;
  display:flex;align-items:center;gap:6px;
}
#fab svg{width:14px;height:14px;stroke:#fff;fill:none;stroke-width:2.5;stroke-linecap:round}
#fab.show{opacity:1;pointer-events:auto}
#fab:hover{filter:brightness(1.1)}

@keyframes blink{0%,100%{opacity:1}50%{opacity:.25}}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--sf2);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--bd)}
</style>
</head>
<body>

<div id="topbar">
  <a class="tb-back" href="/">
    <svg viewBox="0 0 24 24"><polyline points="15 18 9 12 15 6"/></svg>Sessions
  </a>
  <div class="tb-div"></div>
  <div id="sid-label"></div>
  <div class="tb-acts">
    <button class="tb-btn" onclick="exportTsv()">
      <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
      Export
    </button>
    <div id="live-badge" class="bd-badge">Done</div>
  </div>
</div>

<div id="charts-wrap">
  <button id="ch-tog" onclick="toggleCharts()">
    <svg viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"/></svg>
    Charts
    <span id="ch-sum" style="font-weight:400;color:var(--tx3);text-transform:none;letter-spacing:0;margin-left:4px;font-size:.65rem"></span>
  </button>
  <div id="ch-inner">
    <div class="cc" style="flex:0 0 192px">
      <div class="clbl">Level distribution</div>
      <div id="dist-bars"></div>
    </div>
    <div class="cc" style="flex:1">
      <div class="clbl">Timeline
        <span id="tl-range" style="font-weight:400;color:var(--tx3);text-transform:none;letter-spacing:0;font-size:.56rem;margin-left:6px"></span>
      </div>
      <svg id="tl-svg" preserveAspectRatio="none"></svg>
    </div>
  </div>
</div>
<div id="tl-tip"></div>

<div id="controls">
  <div id="pkg-tabs"></div>
  <div class="lchips">
    <button class="lc lc-V on" id="lc-V" title="Verbose  1">V</button>
    <button class="lc lc-D on" id="lc-D" title="Debug  2">D</button>
    <button class="lc lc-I on" id="lc-I" title="Info  3">I</button>
    <button class="lc lc-W on" id="lc-W" title="Warning  4">W</button>
    <button class="lc lc-E on" id="lc-E" title="Error  5">E</button>
    <button class="lc lc-F on" id="lc-F" title="Fatal  6">F</button>
  </div>
  <div class="csep"></div>
  <div class="sbox">
    <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
    <input id="search" type="text" placeholder="Filter  \xb7  / to focus">
    <button id="src-clr" onclick="clearSearch()">\xd7</button>
  </div>
  <div class="cr">
    <button class="wbtn" id="wbtn" onclick="toggleWrap()">Wrap</button>
    <span id="lcount"></span>
  </div>
</div>

<div id="log-wrap">
  <div id="load-earlier" onclick="loadEarlier()">↑ Load earlier lines</div>
  <table>
    <colgroup>
      <col class="c0"><col class="c1"><col class="c2">
      <col class="c3"><col class="c4"><col class="c5"><col class="c6">
    </colgroup>
    <thead><tr>
      <th style="padding:0"></th>
      <th>Time</th><th style="text-align:right">PID</th>
      <th></th><th>Tag</th><th>Message</th><th></th>
    </tr></thead>
    <tbody id="tbody"></tbody>
  </table>
</div>

<div id="toast"></div>
<button id="fab" onclick="jumpBottom()">
  <svg viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></svg>
  Bottom
</button>

<script>
const LEVELS=['V','D','I','W','E','F'];
const LBAR={V:'#8e8e93',D:'#5b9aff',I:'#30d158',W:'#ffd60a',E:'#ff453a',F:'#bf5af2'};
const LDIST={V:'rgba(142,142,147,.65)',D:'rgba(91,154,255,.75)',I:'rgba(48,209,88,.75)',
             W:'rgba(255,214,10,.75)',E:'rgba(255,69,58,.75)',F:'rgba(191,90,242,.75)'};
const SID=(new URLSearchParams(location.search)).get('id')||'';
let activePkg='',packages=[],enabledLevels=new Set(LEVELS);
let searchKw='',wrapMode=false,chartsOpen=false;
let allRows=[],filteredRows=[],fromLine=0,totalLines=0;
let isLive=false,atBottom=true,searchTimer=null,cachedSummary=null;
let tlBuckets={},tlKeys=[];

document.title='Logcat — '+SID;
document.getElementById('sid-label').textContent=SID;

function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function hi(s,kw){
  if(!kw)return esc(s);
  const i=s.toLowerCase().indexOf(kw);
  if(i<0)return esc(s);
  return esc(s.slice(0,i))+'<mark>'+esc(s.slice(i,i+kw.length))+'</mark>'+hi(s.slice(i+kw.length),kw);
}
function toast(msg,ms=2000){
  const t=document.getElementById('toast');
  t.textContent=msg;t.classList.add('show');
  clearTimeout(toast._t);toast._t=setTimeout(()=>t.classList.remove('show'),ms);
}

// keyboard shortcuts
document.addEventListener('keydown',e=>{
  if(e.target.tagName==='INPUT')return;
  if(e.key==='/'||e.key==='f'){e.preventDefault();document.getElementById('search').focus();return}
  if(e.key==='g'||e.key==='G'){jumpBottom();return}
  if(e.key==='c'||e.key==='C'){toggleCharts();return}
  if(e.key==='Escape'){clearSearch();return}
  const m={'1':'V','2':'D','3':'I','4':'W','5':'E','6':'F'};
  if(m[e.key])toggleLevel(m[e.key]);
});
document.getElementById('search').addEventListener('keydown',e=>{
  if(e.key==='Escape'){clearSearch();document.getElementById('search').blur()}
});

// charts accordion
function toggleCharts(){
  chartsOpen=!chartsOpen;
  document.getElementById('ch-tog').classList.toggle('open',chartsOpen);
  document.getElementById('ch-inner').classList.toggle('open',chartsOpen);
}

// level toggles
function toggleLevel(l){
  if(enabledLevels.has(l))enabledLevels.delete(l);else enabledLevels.add(l);
  document.getElementById('lc-'+l).classList.toggle('on',enabledLevels.has(l));
  document.querySelectorAll('.dr').forEach(r=>r.classList.toggle('off',!enabledLevels.has(r.dataset.lvl)));
  applyFilter();
}
LEVELS.forEach(l=>document.getElementById('lc-'+l).addEventListener('click',()=>toggleLevel(l)));
document.getElementById('dist-bars').addEventListener('click',e=>{
  const r=e.target.closest('.dr');if(r)toggleLevel(r.dataset.lvl);
});

// search
document.getElementById('search').addEventListener('input',e=>{
  clearTimeout(searchTimer);
  document.getElementById('src-clr').style.display=e.target.value?'block':'none';
  searchTimer=setTimeout(()=>{searchKw=e.target.value.toLowerCase();applyFilter();},160);
});
function clearSearch(){
  document.getElementById('search').value='';
  document.getElementById('src-clr').style.display='none';
  searchKw='';applyFilter();
}

// wrap
function toggleWrap(){
  wrapMode=!wrapMode;
  document.getElementById('wbtn').classList.toggle('on',wrapMode);
  document.querySelectorAll('.tmsg').forEach(td=>td.classList.toggle('wrap',wrapMode));
}

// parse TSV — auto-detect 5-col (with tid) or 4-col (no tid) layout
function parseLine(raw){
  const p=raw.split('\t');
  const isL=s=>s&&s.length===1&&'VDIWEF'.includes(s);
  let lvl='I',tag='',msg='';
  if(isL((p[3]||'').trim())){
    lvl=p[3].trim();tag=p[4]||'';msg=p.length>5?p.slice(5).join('\t'):(p[4]||'');
  }else if(isL((p[2]||'').trim())){
    lvl=p[2].trim();tag=p[3]||'';msg=p.length>4?p.slice(4).join('\t'):(p[3]||'');
  }else{msg=raw;}
  return{time:p[0]||'',pid:p[1]||'',lvl,tag,msg};
}

// filter + render
function applyFilter(){
  const kw=searchKw;
  filteredRows=allRows.filter(r=>{
    if(LEVELS.includes(r.lvl)&&!enabledLevels.has(r.lvl))return false;
    if(kw&&!r.msg.toLowerCase().includes(kw)&&!r.tag.toLowerCase().includes(kw)&&!r.time.includes(kw))return false;
    return true;
  });
  renderTable();
}

function renderTable(){
  const kw=searchKw;
  document.getElementById('lcount').textContent=filteredRows.length.toLocaleString()+' / '+allRows.length.toLocaleString();
  if(!filteredRows.length){
    let msg='Waiting for log data…';
    if(allRows.length>0&&searchKw)msg='No results for “'+esc(searchKw)+'”';
    else if(allRows.length>0)msg='All '+allRows.length.toLocaleString()+' rows hidden by level filter';
    document.getElementById('tbody').innerHTML=`<tr class="erow"><td colspan="7">${msg}</td></tr>`;
    return;
  }
  const wc=wrapMode?' wrap':'';
  document.getElementById('tbody').innerHTML=filteredRows.map((r,i)=>`<tr class="r${r.lvl}">
  <td class="bar"></td>
  <td class="ttime">${esc(r.time)}</td>
  <td class="tpid">${esc(r.pid)}</td>
  <td style="padding:2px 3px;text-align:center"><span class="lvb lv${r.lvl}">${r.lvl}</span></td>
  <td class="ttag" title="${esc(r.tag)}">${esc(r.tag)}</td>
  <td class="tmsg${wc}" onclick="this.classList.toggle('exp')">${hi(r.msg,kw)}</td>
  <td class="cpbtn" onclick="cpRow(${i})" title="Copy">⧓</td>
</tr>`).join('');
}

function cpRow(i){
  const r=filteredRows[i];
  navigator.clipboard.writeText([r.time,r.pid,'',r.lvl,r.tag,r.msg].join('\t'))
    .then(()=>toast('Copied')).catch(()=>toast('Copy failed'));
}

function exportTsv(){
  const body='time\tpid\ttid\tlevel\ttag\tmessage\n'+
    filteredRows.map(r=>[r.time,r.pid,'',r.lvl,r.tag,r.msg].join('\t')).join('\n');
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([body],{type:'text/tab-separated-values'}));
  a.download=SID+'_'+activePkg+'_export.tsv';a.click();
  toast('Exported '+filteredRows.length.toLocaleString()+' rows');
}

function jumpBottom(){document.getElementById('log-wrap').scrollTop=1e9}
document.getElementById('log-wrap').addEventListener('scroll',function(){
  const at=this.scrollTop+this.clientHeight>=this.scrollHeight-60;
  atBottom=at;document.getElementById('fab').classList.toggle('show',!at);
},{passive:true});

// charts
function renderDist(summary){
  const c=(summary&&summary[activePkg])||{V:0,D:0,I:0,W:0,E:0,F:0};
  const tot=Object.values(c).reduce((a,b)=>a+b,0)||1;
  document.getElementById('dist-bars').innerHTML=LEVELS.map(l=>`
    <div class="dr${enabledLevels.has(l)?'':' off'}" data-lvl="${l}">
      <span class="dlbl" style="color:${LBAR[l]}">${l}</span>
      <div class="dtrack"><div class="dfill" style="width:${Math.round(c[l]/tot*100)}%;background:${LDIST[l]}"></div></div>
      <span class="dcnt">${c[l].toLocaleString()}</span>
    </div>`).join('');
  const sum=['E','W','I','D'].filter(l=>c[l]>0)
    .map(l=>`<span style="color:${LBAR[l]}">${l}:${c[l].toLocaleString()}</span>`).join(' ');
  document.getElementById('ch-sum').innerHTML=sum;
}

function buildTimeline(rows){
  tlBuckets={};
  rows.forEach(r=>{const m=r.time.length>=14?r.time.substring(0,14):'??';tlBuckets[m]=(tlBuckets[m]||0)+1;});
  tlKeys=Object.keys(tlBuckets).sort();
}

function renderTimeline(){
  const svg=document.getElementById('tl-svg');
  if(!tlKeys.length){svg.innerHTML='';return;}
  const max=Math.max(...tlKeys.map(k=>tlBuckets[k]));
  const W=800,H=40,n=tlKeys.length,bw=Math.max(1,Math.floor(W/Math.max(n,1))-1);
  svg.setAttribute('viewBox',`0 0 ${W} ${H}`);
  svg.innerHTML=tlKeys.map((k,i)=>{
    const h=Math.max(2,Math.round(tlBuckets[k]/max*(H-4)));
    return`<rect x="${i*(bw+1)}" y="${H-h-2}" width="${bw}" height="${h}" rx="1" fill="#5b9aff" opacity=".7" data-k="${esc(k)}" data-c="${tlBuckets[k]}"/>`;
  }).join('');
  if(tlKeys.length>1)
    document.getElementById('tl-range').textContent=tlKeys[0].trim()+' – '+tlKeys[tlKeys.length-1].trim();
  svg.onmousemove=e=>{
    const tip=document.getElementById('tl-tip'),r=e.target.closest('rect');
    if(r){tip.textContent=r.dataset.k+' · '+Number(r.dataset.c).toLocaleString()+' lines';
          tip.style.cssText=`display:block;left:${e.clientX+12}px;top:${e.clientY-30}px`;}
    else tip.style.display='none';
  };
  svg.onmouseleave=()=>document.getElementById('tl-tip').style.display='none';
}

// data
async function loadLines(fromIdx,prepend){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${fromIdx}&limit=2000`).catch(()=>null);
  if(!res||!res.ok)return;
  const d=await res.json();totalLines=d.total;
  const parsed=d.lines.map(parseLine);
  if(prepend){allRows=[...parsed,...allRows];fromLine=d.from;}
  else{allRows=parsed;fromLine=d.from;}
  document.getElementById('load-earlier').style.display=fromLine>0?'block':'none';
  buildTimeline(allRows);renderTimeline();applyFilter();
}

async function loadEarlier(){
  const el=document.getElementById('log-wrap'),ph=el.scrollHeight;
  await loadLines(Math.max(0,fromLine-2000),true);
  el.scrollTop=el.scrollHeight-ph;
}

async function switchPkg(pkg){
  activePkg=pkg;
  document.querySelectorAll('.ptab').forEach(t=>t.classList.toggle('active',t.dataset.pkg===pkg));
  allRows=[];filteredRows=[];fromLine=0;totalLines=0;
  document.getElementById('tbody').innerHTML='';
  const probe=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=0&limit=0`).catch(()=>null);
  if(probe&&probe.ok){const pd=await probe.json();totalLines=pd.total;fromLine=Math.max(0,totalLines-3000);}
  await loadLines(fromLine,false);
  if(!cachedSummary)
    cachedSummary=await fetch(`/api/session/${encodeURIComponent(SID)}/summary`).then(r=>r.json()).catch(()=>null);
  renderDist(cachedSummary);
  if(atBottom)jumpBottom();
}

async function livePoll(){
  if(!activePkg)return;
  const lr=await fetch(`/api/session/${encodeURIComponent(SID)}/live`).catch(()=>null);
  const live=(lr&&lr.ok)?(await lr.json()).live:false;
  const badge=document.getElementById('live-badge');
  if(live!==isLive){
    isLive=live;
    badge.className=live?'bl':'bd-badge';
    badge.innerHTML=live?'<div class="dot"></div>Live':'Done';
    if(!live)toast('Session complete',3000);
  }
  if(!live)return;
  const tailFrom=allRows.length>0?totalLines:0;
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${tailFrom}&limit=500`).catch(()=>null);
  if(!res||!res.ok)return;
  const d=await res.json();
  if(d.count>0){
    const prev=allRows.length;totalLines=d.total;
    allRows=tailFrom===0?d.lines.map(parseLine):[...allRows,...d.lines.map(parseLine)];
    buildTimeline(allRows);renderTimeline();applyFilter();
    const added=allRows.length-prev;
    if(added>0){if(atBottom)jumpBottom();else toast('+'+added.toLocaleString()+' new lines');}
  }
}

async function init(){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/packages`).catch(()=>null);
  if(!res||!res.ok){
    document.getElementById('tbody').innerHTML='<tr class="erow"><td colspan="7">Session not found.</td></tr>';
    return;
  }
  packages=await res.json();
  document.getElementById('pkg-tabs').innerHTML=packages.map(p=>`<button class="ptab" data-pkg="${esc(p)}" onclick="switchPkg('${esc(p)}')">${esc(p)}</button>`).join('');
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
