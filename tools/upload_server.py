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
<title>LogDaemon — Sessions</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh}
header{padding:20px 28px;border-bottom:1px solid #1e293b;display:flex;align-items:center;gap:12px}
header h1{font-size:1.2rem;font-weight:600;letter-spacing:.02em}
header span{font-size:.75rem;color:#64748b;margin-left:auto}
#grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px;padding:24px 28px}
.card{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;cursor:pointer;
      text-decoration:none;color:inherit;display:block;transition:border-color .15s,transform .1s}
.card:hover{border-color:#60a5fa;transform:translateY(-1px)}
.card-head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:10px}
.sid{font-size:.85rem;font-weight:600;color:#cbd5e1;word-break:break-all}
.badge{font-size:.65rem;font-weight:700;letter-spacing:.06em;padding:2px 8px;border-radius:999px}
.live{background:#14532d;color:#86efac}
.done{background:#1e293b;color:#475569;border:1px solid #334155}
.pkgs{display:flex;flex-wrap:wrap;gap:5px;margin-bottom:10px}
.pkg{font-size:.7rem;background:#0f172a;color:#7dd3fc;padding:2px 8px;border-radius:4px}
.age{font-size:.7rem;color:#475569}
#empty{color:#475569;text-align:center;padding:80px 28px;font-size:.9rem}
</style>
</head>
<body>
<header>
  <h1>LogDaemon</h1>
  <span id="ts"></span>
</header>
<div id="grid"><div id="empty">No sessions yet. Start the daemon and plug in the USB drive.</div></div>
<script>
function ago(ts){
  const d=Math.floor((Date.now()/1000)-ts);
  if(d<5)return'just now';
  if(d<60)return d+'s ago';
  if(d<3600)return Math.floor(d/60)+'m ago';
  if(d<86400)return Math.floor(d/3600)+'h ago';
  return Math.floor(d/86400)+'d ago';
}
async function refresh(){
  document.getElementById('ts').textContent=new Date().toLocaleTimeString();
  const r=await fetch('/api/sessions').catch(()=>null);
  if(!r||!r.ok)return;
  const sessions=await r.json();
  const grid=document.getElementById('grid');
  if(!sessions.length){
    grid.innerHTML='<div id="empty">No sessions yet. Start the daemon and plug in the USB drive.</div>';
    return;
  }
  grid.innerHTML=sessions.map(s=>`
    <a class="card" href="/session?id=${encodeURIComponent(s.id)}">
      <div class="card-head">
        <div class="sid">${s.id}</div>
        <span class="badge ${s.live?'live':'done'}">${s.live?'LIVE':'DONE'}</span>
      </div>
      <div class="pkgs">${s.packages.map(p=>`<span class="pkg">${p}</span>`).join('')}</div>
      <div class="age">${ago(s.last_updated)}</div>
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
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;
     flex-direction:column;height:100vh;overflow:hidden}
header{padding:10px 16px;border-bottom:1px solid #1e293b;display:flex;align-items:center;
       gap:10px;flex-shrink:0}
header a{color:#60a5fa;font-size:.8rem;text-decoration:none}
header a:hover{text-decoration:underline}
#sid-label{font-size:.85rem;font-weight:600;color:#cbd5e1;flex:1;overflow:hidden;
           text-overflow:ellipsis;white-space:nowrap}
#live-badge{font-size:.65rem;font-weight:700;letter-spacing:.06em;padding:2px 8px;
            border-radius:999px;flex-shrink:0}
.live{background:#14532d;color:#86efac}
.done{background:#1e293b;color:#475569;border:1px solid #334155}

#charts{display:flex;gap:12px;padding:10px 16px;border-bottom:1px solid #1e293b;flex-shrink:0}
.chart-box{background:#1e293b;border:1px solid #334155;border-radius:8px;padding:10px;flex:1;min-width:0}
.chart-title{font-size:.65rem;color:#64748b;margin-bottom:6px;letter-spacing:.05em;text-transform:uppercase}
.level-bars{display:flex;flex-direction:column;gap:3px}
.level-row{display:flex;align-items:center;gap:6px;font-size:.65rem}
.level-row .lbl{width:10px;font-weight:700}
.level-row .bar-wrap{flex:1;height:8px;background:#0f172a;border-radius:3px;overflow:hidden}
.level-row .bar-fill{height:100%;border-radius:3px;transition:width .3s}
.level-row .cnt{width:40px;text-align:right;color:#64748b}
#timeline-svg{width:100%;height:48px}

#controls{display:flex;align-items:center;gap:8px;padding:8px 16px;
          border-bottom:1px solid #1e293b;flex-shrink:0;flex-wrap:wrap}
.pkg-tab{font-size:.75rem;padding:3px 10px;border-radius:999px;cursor:pointer;
         background:#1e293b;color:#94a3b8;border:1px solid #334155;transition:.15s}
.pkg-tab.active{background:#1e40af;color:#bfdbfe;border-color:#3b82f6}
.lvl-btn{font-size:.72rem;font-weight:700;padding:3px 9px;border-radius:5px;cursor:pointer;
         border:1px solid #334155;background:#1e293b;transition:.15s;opacity:.45}
.lvl-btn.on{opacity:1;border-color:transparent}
#lvl-V.on{background:#374151;color:#d1d5db}
#lvl-D.on{background:#1e3a8a;color:#93c5fd}
#lvl-I.on{background:#14532d;color:#86efac}
#lvl-W.on{background:#78350f;color:#fcd34d}
#lvl-E.on{background:#7f1d1d;color:#fca5a5}
#lvl-F.on{background:#4c1d95;color:#c4b5fd}
#search{font-size:.8rem;padding:4px 10px;background:#1e293b;border:1px solid #334155;
        border-radius:5px;color:#e2e8f0;width:180px;outline:none}
#search:focus{border-color:#60a5fa}
#count{font-size:.72rem;color:#475569;margin-left:auto}

#log-wrap{flex:1;overflow-y:auto;overflow-x:hidden}
table{width:100%;border-collapse:collapse;font-size:.75rem;font-family:ui-monospace,monospace}
thead th{position:sticky;top:0;background:#1e293b;padding:5px 8px;text-align:left;
         font-size:.65rem;color:#64748b;letter-spacing:.06em;border-bottom:1px solid #334155;
         z-index:10;white-space:nowrap}
td{padding:3px 8px;border-bottom:1px solid #0f172a;vertical-align:top}
tr.r-V{background:rgba(107,114,128,.06)}
tr.r-D{background:rgba(59,130,246,.06)}
tr.r-I{background:rgba(22,163,74,.06)}
tr.r-W{background:rgba(202,138,4,.09)}
tr.r-E{background:rgba(220,38,38,.09)}
tr.r-F{background:rgba(124,58,237,.12)}
.c-V{color:#9ca3af}.c-D{color:#60a5fa}.c-I{color:#4ade80}
.c-W{color:#fbbf24}.c-E{color:#f87171}.c-F{color:#a78bfa}
.col-time{color:#475569;white-space:nowrap}
.col-pid{color:#475569;white-space:nowrap}
.col-tag{color:#94a3b8;white-space:nowrap;max-width:120px;overflow:hidden;text-overflow:ellipsis}
.col-msg{color:#cbd5e1;word-break:break-all;cursor:pointer;max-width:600px}
.col-msg.collapsed{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#load-earlier{display:none;text-align:center;padding:10px;color:#60a5fa;cursor:pointer;
              font-size:.8rem;border-bottom:1px solid #1e293b}
#load-earlier:hover{color:#93c5fd}
</style>
</head>
<body>
<header>
  <a href="/">&larr; Sessions</a>
  <div id="sid-label"></div>
  <span id="live-badge" class="badge done">DONE</span>
</header>
<div id="charts">
  <div class="chart-box" id="dist-box">
    <div class="chart-title">Level Distribution</div>
    <div class="level-bars" id="dist-bars"></div>
  </div>
  <div class="chart-box" style="flex:2;min-width:0">
    <div class="chart-title">Timeline</div>
    <svg id="timeline-svg" preserveAspectRatio="none"></svg>
  </div>
</div>
<div id="controls">
  <div id="pkg-tabs"></div>
  <button class="lvl-btn on" id="lvl-V">V</button>
  <button class="lvl-btn on" id="lvl-D">D</button>
  <button class="lvl-btn on" id="lvl-I">I</button>
  <button class="lvl-btn on" id="lvl-W">W</button>
  <button class="lvl-btn on" id="lvl-E">E</button>
  <button class="lvl-btn on" id="lvl-F">F</button>
  <input id="search" type="text" placeholder="Search...">
  <span id="count"></span>
</div>
<div id="log-wrap">
  <div id="load-earlier" onclick="loadEarlier()">Load earlier lines</div>
  <table>
    <thead><tr>
      <th>Time</th><th>PID</th><th>Lvl</th><th>Tag</th><th>Message</th>
    </tr></thead>
    <tbody id="tbody"></tbody>
  </table>
</div>
<script>
const LEVELS=['V','D','I','W','E','F'];
const COLORS={V:'#6b7280',D:'#3b82f6',I:'#16a34a',W:'#ca8a04',E:'#dc2626',F:'#7c3aed'};
const params=new URLSearchParams(location.search);
const SID=params.get('id')||'';
let activePkg='';
let packages=[];
let enabledLevels=new Set(LEVELS);
let searchKw='';
let allRows=[];        // raw parsed rows [{time,pid,lvl,tag,msg}]
let fromLine=0;        // earliest loaded line index
let totalLines=0;
let searchTimer=null;
let liveTimer=null;

document.getElementById('sid-label').textContent=SID;

// ── level toggles ────────────────────────────────────────────────────────────
LEVELS.forEach(l=>{
  document.getElementById('lvl-'+l).addEventListener('click',()=>{
    if(enabledLevels.has(l))enabledLevels.delete(l);
    else enabledLevels.add(l);
    document.getElementById('lvl-'+l).classList.toggle('on',enabledLevels.has(l));
    render();
  });
});

document.getElementById('search').addEventListener('input',e=>{
  clearTimeout(searchTimer);
  searchTimer=setTimeout(()=>{searchKw=e.target.value.toLowerCase();render();},180);
});

// ── parse TSV line ───────────────────────────────────────────────────────────
function parseLine(raw){
  const p=raw.split('\t');
  return{time:p[0]||'',pid:p[1]||'',lvl:(p[3]||'').trim(),tag:p[4]||'',msg:p[5]||p.slice(5).join('\t')};
}

// ── render ───────────────────────────────────────────────────────────────────
function render(){
  const kw=searchKw;
  const visible=allRows.filter(r=>{
    if(!enabledLevels.has(r.lvl))return false;
    if(kw&&!r.msg.toLowerCase().includes(kw)&&!r.tag.toLowerCase().includes(kw))return false;
    return true;
  });
  document.getElementById('count').textContent=visible.length+' / '+allRows.length+' lines';
  const tbody=document.getElementById('tbody');
  tbody.innerHTML=visible.map(r=>`
    <tr class="r-${r.lvl}">
      <td class="col-time">${esc(r.time)}</td>
      <td class="col-pid">${esc(r.pid)}</td>
      <td class="c-${r.lvl}">${esc(r.lvl)}</td>
      <td class="col-tag" title="${esc(r.tag)}">${esc(r.tag)}</td>
      <td class="col-msg collapsed" onclick="this.classList.toggle('collapsed')">${esc(r.msg)}</td>
    </tr>`).join('');
}

function esc(s){
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// ── charts ───────────────────────────────────────────────────────────────────
function renderDist(summary){
  const pkg=activePkg;
  const counts=(summary&&summary[pkg])||{V:0,D:0,I:0,W:0,E:0,F:0};
  const total=Object.values(counts).reduce((a,b)=>a+b,0)||1;
  document.getElementById('dist-bars').innerHTML=LEVELS.map(l=>`
    <div class="level-row">
      <span class="lbl c-${l}">${l}</span>
      <div class="bar-wrap"><div class="bar-fill" style="width:${Math.round(counts[l]/total*100)}%;background:${COLORS[l]}"></div></div>
      <span class="cnt">${counts[l]}</span>
    </div>`).join('');
}

function renderTimeline(rows){
  if(!rows.length){document.getElementById('timeline-svg').innerHTML='';return;}
  // bucket by minute using time col "MM-DD HH:MM:SS.mmm"
  const buckets={};
  rows.forEach(r=>{
    const m=r.time.substring(0,14); // "MM-DD HH:MM"
    buckets[m]=(buckets[m]||0)+1;
  });
  const keys=Object.keys(buckets).sort();
  const max=Math.max(...Object.values(buckets));
  const W=800,H=48,n=keys.length;
  const bw=Math.max(2,Math.floor(W/Math.max(n,1))-1);
  const rects=keys.map((k,i)=>{
    const h=Math.max(2,Math.round(buckets[k]/max*(H-4)));
    return`<rect x="${i*(bw+1)}" y="${H-h}" width="${bw}" height="${h}" fill="#3b82f6" opacity=".7"/>`;
  }).join('');
  document.getElementById('timeline-svg').setAttribute('viewBox',`0 0 ${W} ${H}`);
  document.getElementById('timeline-svg').innerHTML=rects;
}

// ── data fetching ─────────────────────────────────────────────────────────────
async function loadLines(fromLineIdx,append){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${fromLineIdx}&limit=2000`).catch(()=>null);
  if(!res||!res.ok)return;
  const d=await res.json();
  totalLines=d.total;
  const parsed=d.lines.map(parseLine);
  if(append){
    allRows=[...parsed,...allRows];
    fromLine=d.from;
  }else{
    allRows=parsed;
    fromLine=d.from;
  }
  document.getElementById('load-earlier').style.display=fromLine>0?'block':'none';
  render();
  renderTimeline(allRows);
}

async function loadEarlier(){
  const target=Math.max(0,fromLine-2000);
  await loadLines(target,true);
}

async function switchPkg(pkg){
  activePkg=pkg;
  document.querySelectorAll('.pkg-tab').forEach(t=>{
    t.classList.toggle('active',t.dataset.pkg===pkg);
  });
  allRows=[];fromLine=0;totalLines=0;
  const start=Math.max(0,totalLines-2000);
  await loadLines(start,false);
  const sum=await fetch(`/api/session/${encodeURIComponent(SID)}/summary`).then(r=>r.json()).catch(()=>null);
  renderDist(sum);
}

async function init(){
  const res=await fetch(`/api/session/${encodeURIComponent(SID)}/packages`).catch(()=>null);
  if(!res||!res.ok)return;
  packages=await res.json();
  const tabs=document.getElementById('pkg-tabs');
  tabs.innerHTML=packages.map(p=>`<button class="pkg-tab" data-pkg="${esc(p)}" onclick="switchPkg('${esc(p)}')">${esc(p)}</button>`).join('');
  if(packages.length)await switchPkg(packages[0]);
  startLivePolling();
}

async function livePoll(){
  if(!activePkg)return;
  const lres=await fetch(`/api/session/${encodeURIComponent(SID)}/live`).catch(()=>null);
  const live=(lres&&lres.ok)?(await lres.json()).live:false;
  const badge=document.getElementById('live-badge');
  badge.textContent=live?'LIVE':'DONE';
  badge.className='badge '+(live?'live':'done');
  if(live){
    // tail: fetch from end
    const tailFrom=Math.max(0,totalLines-1);
    const res=await fetch(`/api/session/${encodeURIComponent(SID)}/lines?pkg=${encodeURIComponent(activePkg)}&from_line=${tailFrom}&limit=500`).catch(()=>null);
    if(res&&res.ok){
      const d=await res.json();
      if(d.count>0){
        totalLines=d.total;
        allRows=[...allRows,...d.lines.map(parseLine)];
        render();
        renderTimeline(allRows);
        const wrap=document.getElementById('log-wrap');
        wrap.scrollTop=wrap.scrollHeight;
      }
    }
  }
}

function startLivePolling(){
  liveTimer=setInterval(livePoll,3000);
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
