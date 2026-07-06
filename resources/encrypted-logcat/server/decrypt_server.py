#!/usr/bin/env python3
"""
Localhost decrypt server for encrypted .elog files, with a web UI.

Holds the RSA PRIVATE key (the only thing that can unlock). You pick a pulled
.elog file in the browser; it's uploaded to this loopback-only server, decrypted,
and the plaintext is shown back in the UI. Nothing leaves your machine.

Setup (no Flask — pure standard library + cryptography):
    pip install cryptography
    python decrypt_server.py                 # loads ./private_key.pem on 127.0.0.1:8734
    python decrypt_server.py mykey.pem 9000  # custom key path / port
Then open http://127.0.0.1:8734

Crypto matches EncryptedLogWriter.kt exactly:
    - session key wrapped with RSA-OAEP (SHA-256 + MGF1-SHA-256)
    - each line AES-256-GCM (12-byte nonce, 128-bit tag appended to ciphertext)
"""

import base64
import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HEADER_PREFIX = "ELOGv1|"
MAX_UPLOAD = 512 * 1024 * 1024  # 512 MB upload ceiling

_PRIVATE_KEY = None
_KEY_ID = "--------"


def load_private_key(path):
    global _PRIVATE_KEY, _KEY_ID
    with open(path, "rb") as f:
        _PRIVATE_KEY = serialization.load_pem_private_key(f.read(), password=None)
    der = _PRIVATE_KEY.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    _KEY_ID = hashlib.sha256(der).hexdigest()[:8]


def decrypt_elog(data: bytes):
    """Walk the file, unwrap each session header, GCM-decrypt each line."""
    lines = []
    total = decrypted = skipped = 0
    current = None  # AESGCM for the active session

    for raw in data.split(b"\n"):
        if not raw.strip():
            continue
        line = raw.decode("utf-8", "replace").strip("\r")

        if line.startswith(HEADER_PREFIX):
            wrapped = base64.b64decode(line[len(HEADER_PREFIX):])
            aes_key = _PRIVATE_KEY.decrypt(
                wrapped,
                padding.OAEP(
                    mgf=padding.MGF1(algorithm=hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            )
            current = AESGCM(aes_key)
            continue

        total += 1
        if current is None:
            skipped += 1
            lines.append({"ok": False, "text": "[data before any session key — skipped]"})
            continue

        idx = line.find(":")
        if idx <= 0:
            skipped += 1
            lines.append({"ok": False, "text": "[malformed line — skipped]"})
            continue

        try:
            nonce = base64.b64decode(line[:idx])
            ct = base64.b64decode(line[idx + 1:])
            pt = current.decrypt(nonce, ct, None).decode("utf-8", "replace")
            decrypted += 1
            lines.append({"ok": True, "text": pt})
        except Exception as e:
            # GCM auth failure lands here too: a tampered or truncated line.
            skipped += 1
            lines.append({"ok": False, "text": f"[undecryptable: {type(e).__name__} — skipped]"})

    return {"total": total, "decrypted": decrypted, "skipped": skipped, "lines": lines}


class Handler(BaseHTTPRequestHandler):
    server_version = "logvault/1.0"

    def _send(self, code, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode("utf-8"), "application/json")

    def do_GET(self):
        if urlparse(self.path).path == "/":
            html = PAGE.replace("__KEYID__", _KEY_ID).encode("utf-8")
            self._send(200, html, "text/html; charset=utf-8")
        else:
            self._json(404, {"error": "not found"})

    # The browser POSTs the raw file bytes as the request body (no multipart form,
    # so no Flask/werkzeug needed); the filename rides along as the ?name= param.
    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != "/decrypt":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            self._json(400, {"error": "No file received. Pick a .elog file and try again."})
            return
        if length > MAX_UPLOAD:
            self._json(413, {"error": "File too large (>512 MB)."})
            return
        data = self.rfile.read(length)
        filename = (parse_qs(parsed.query).get("name") or ["upload.elog"])[0]
        try:
            result = decrypt_elog(data)
        except Exception as e:
            self._json(400, {"error": f"Could not read this file as an .elog: {type(e).__name__}"})
            return
        result["filename"] = filename
        self._json(200, result)

    def log_message(self, *args):
        pass  # keep the console quiet


PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>logvault — decrypt</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  :root{
    --ink:#0E141C; --panel:#151E2A; --edge:#26333f;
    --text:#E7EDF4; --muted:#8695A8; --faint:#5C6B7d;
    --brass:#E3B341; --brass-soft:rgba(227,179,65,.14);
    --teal:#4FD1A5; --danger:#E0736A;
    --radius:14px;
  }
  *{box-sizing:border-box}
  html,body{margin:0}
  body{
    background:
      radial-gradient(1100px 500px at 78% -10%, rgba(227,179,65,.06), transparent 60%),
      radial-gradient(900px 500px at 0% 110%, rgba(79,209,165,.05), transparent 55%),
      var(--ink);
    color:var(--text);
    font-family:"IBM Plex Sans",system-ui,sans-serif;
    -webkit-font-smoothing:antialiased;
    min-height:100vh;
    line-height:1.5;
  }
  .wrap{max-width:900px;margin:0 auto;padding:34px 22px 80px}

  header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:30px}
  .brand{display:flex;align-items:center;gap:13px}
  .glyph{
    width:38px;height:38px;border-radius:10px;flex:none;
    background:linear-gradient(150deg,#2a3644,#141d28);
    border:1px solid var(--edge);
    display:grid;place-items:center;
    box-shadow:inset 0 1px 0 rgba(255,255,255,.05);
  }
  .glyph svg{width:19px;height:19px}
  .brand h1{font-size:19px;font-weight:700;letter-spacing:-.2px;margin:0}
  .brand p{margin:0;font-size:12.5px;color:var(--muted)}
  .chips{display:flex;gap:9px;align-items:center;flex-wrap:wrap}
  .chip{
    font-family:"IBM Plex Mono",monospace;font-size:11.5px;
    padding:6px 11px;border-radius:999px;border:1px solid var(--edge);
    background:rgba(255,255,255,.02);color:var(--muted);
    display:inline-flex;align-items:center;gap:7px;
  }
  .dot{width:6px;height:6px;border-radius:50%;background:var(--teal);box-shadow:0 0 0 3px rgba(79,209,165,.14)}
  .chip b{color:var(--brass);font-weight:500}

  /* Drop zone — the "vault slot" */
  .slot{
    position:relative;border-radius:var(--radius);
    border:1.5px dashed var(--edge);
    background:linear-gradient(180deg, rgba(255,255,255,.015), rgba(0,0,0,.12));
    padding:44px 28px;text-align:center;
    transition:border-color .18s, background .18s, transform .18s;
  }
  .slot.drag{border-color:var(--brass);background:var(--brass-soft)}
  .slot .keyhole{
    width:52px;height:52px;margin:0 auto 16px;border-radius:13px;
    display:grid;place-items:center;
    background:var(--brass-soft);border:1px solid rgba(227,179,65,.35);
  }
  .slot .keyhole svg{width:24px;height:24px;stroke:var(--brass)}
  .slot h2{margin:0 0 6px;font-size:16.5px;font-weight:600}
  .slot p{margin:0 0 18px;color:var(--muted);font-size:13.5px}
  .btn{
    font-family:inherit;font-size:14px;font-weight:600;cursor:pointer;
    border-radius:10px;border:1px solid var(--edge);
    padding:11px 18px;color:var(--text);background:#1e2836;
    transition:border-color .15s, background .15s, transform .05s, opacity .15s;
  }
  .btn:hover{border-color:#3a4a5c;background:#233042}
  .btn:active{transform:translateY(1px)}
  .btn:disabled{opacity:.4;cursor:not-allowed}
  .btn.primary{
    background:linear-gradient(180deg,#Edc059,#d7a531);color:#22190a;border-color:#b78c22;
  }
  .btn.primary:hover{background:linear-gradient(180deg,#f2c869,#dbab38)}
  .btn:focus-visible{outline:2px solid var(--brass);outline-offset:2px}

  .picked{
    margin-top:18px;display:none;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;
  }
  .picked.show{display:flex}
  .file-tag{
    font-family:"IBM Plex Mono",monospace;font-size:12.5px;color:var(--text);
    background:rgba(255,255,255,.03);border:1px solid var(--edge);
    padding:8px 13px;border-radius:9px;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
  }
  .file-tag .sz{color:var(--faint);margin-left:8px}

  /* Results */
  .results{margin-top:26px;display:none}
  .results.show{display:block;animation:rise .35s ease both}
  @keyframes rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}

  .stats{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:14px}
  .stat{
    flex:1;min-width:120px;background:var(--panel);border:1px solid var(--edge);
    border-radius:12px;padding:13px 16px;
  }
  .stat .n{font-family:"IBM Plex Mono",monospace;font-size:22px;font-weight:500;letter-spacing:-.5px}
  .stat .l{font-size:11.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.6px;margin-top:2px}
  .stat.ok .n{color:var(--teal)}
  .stat.skip .n{color:var(--danger)}

  .toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
  .search{flex:1;min-width:180px;position:relative}
  .search input{
    width:100%;font-family:"IBM Plex Mono",monospace;font-size:13px;color:var(--text);
    background:var(--panel);border:1px solid var(--edge);border-radius:10px;
    padding:10px 12px 10px 34px;
  }
  .search input::placeholder{color:var(--faint)}
  .search input:focus{outline:none;border-color:#3a4a5c}
  .search svg{position:absolute;left:11px;top:50%;transform:translateY(-50%);width:15px;height:15px;stroke:var(--faint)}
  .toggle{display:inline-flex;align-items:center;gap:7px;font-size:12.5px;color:var(--muted);cursor:pointer;user-select:none}
  .toggle input{accent-color:var(--brass)}

  .viewer{
    background:#0B1017;border:1px solid var(--edge);border-radius:12px;overflow:hidden;
  }
  .viewer .cap{
    padding:8px 14px;font-size:12px;color:var(--faint);border-bottom:1px solid var(--edge);
    font-family:"IBM Plex Mono",monospace;background:rgba(255,255,255,.015);
  }
  .code{max-height:56vh;overflow:auto;font-family:"IBM Plex Mono",monospace;font-size:12.5px;line-height:1.7}
  .row{display:flex;padding:0 14px;white-space:pre-wrap;word-break:break-word}
  .row:hover{background:rgba(255,255,255,.025)}
  .row .ln{flex:none;width:56px;text-align:right;padding-right:16px;color:#3c4a5a;user-select:none}
  .row.hideln .ln{display:none}
  .row .tx{flex:1;color:#cfd8e3}
  .row.bad .tx{color:var(--danger);opacity:.85}

  .empty{color:var(--faint);text-align:center;padding:40px 20px;font-size:13.5px}
  .error{
    display:none;margin-top:16px;padding:13px 16px;border-radius:11px;
    background:rgba(224,115,106,.1);border:1px solid rgba(224,115,106,.35);
    color:#f0a89f;font-size:13.5px;
  }
  .error.show{display:block}

  .spin{width:15px;height:15px;border:2px solid rgba(34,25,10,.35);border-top-color:#22190a;border-radius:50%;
    display:inline-block;vertical-align:-2px;margin-right:8px;animation:sp .7s linear infinite}
  @keyframes sp{to{transform:rotate(360deg)}}

  input[type=file]{position:absolute;width:1px;height:1px;opacity:0;overflow:hidden}
  @media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
  @media (max-width:560px){.wrap{padding-top:24px}.slot{padding:34px 18px}}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="brand">
      <div class="glyph"><svg viewBox="0 0 24 24" fill="none" stroke="#E3B341" stroke-width="1.8" stroke-linecap="round"><rect x="4" y="10.5" width="16" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/></svg></div>
      <div>
        <h1>logvault</h1>
        <p>Decrypt a pulled .elog file</p>
      </div>
    </div>
    <div class="chips">
      <span class="chip"><span class="dot"></span>127.0.0.1 · local only</span>
      <span class="chip">key <b>····__KEYID__</b></span>
    </div>
  </header>

  <div class="slot" id="slot">
    <div class="keyhole"><svg viewBox="0 0 24 24" fill="none" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="9" r="4"/><path d="M12 13v5M9.5 16h5"/></svg></div>
    <h2>Drop an encrypted file here</h2>
    <p>or pick one from your computer — .elog files from your device</p>
    <button class="btn" id="choose">Choose file</button>
    <input type="file" id="file" accept=".elog,.log,.txt">
    <div class="picked" id="picked">
      <span class="file-tag" id="filetag"></span>
      <button class="btn primary" id="unlock">Unlock</button>
    </div>
  </div>

  <div class="error" id="error"></div>

  <div class="results" id="results">
    <div class="stats">
      <div class="stat"><div class="n" id="s-total">0</div><div class="l">lines</div></div>
      <div class="stat ok"><div class="n" id="s-ok">0</div><div class="l">decrypted</div></div>
      <div class="stat skip"><div class="n" id="s-skip">0</div><div class="l">skipped</div></div>
    </div>
    <div class="toolbar">
      <div class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2"/></svg>
        <input id="filter" placeholder="filter lines…" spellcheck="false">
      </div>
      <label class="toggle"><input type="checkbox" id="lntoggle" checked> line numbers</label>
      <button class="btn" id="copy">Copy</button>
      <button class="btn" id="download">Download</button>
    </div>
    <div class="viewer">
      <div class="cap" id="cap"></div>
      <div class="code" id="code"></div>
    </div>
  </div>
</div>

<script>
const $ = s => document.querySelector(s);
const slot=$("#slot"), fileInput=$("#file"), picked=$("#picked"), filetag=$("#filetag");
const results=$("#results"), errBox=$("#error"), code=$("#code"), cap=$("#cap");
const RENDER_CAP = 5000;
let allLines = [];        // {ok, text}
let chosenFile = null;

function human(bytes){
  if(bytes<1024) return bytes+" B";
  if(bytes<1048576) return (bytes/1024).toFixed(1)+" KB";
  return (bytes/1048576).toFixed(1)+" MB";
}
function esc(s){return s.replace(/[&<>]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[c]));}
function showError(msg){errBox.textContent=msg;errBox.classList.add("show");}
function clearError(){errBox.classList.remove("show");}

function setFile(f){
  if(!f) return;
  chosenFile=f;
  filetag.innerHTML = esc(f.name) + `<span class="sz">${human(f.size)}</span>`;
  picked.classList.add("show");
  clearError();
}

$("#choose").onclick = ()=>fileInput.click();
fileInput.onchange = e => setFile(e.target.files[0]);

["dragenter","dragover"].forEach(ev=>slot.addEventListener(ev,e=>{e.preventDefault();slot.classList.add("drag");}));
["dragleave","drop"].forEach(ev=>slot.addEventListener(ev,e=>{e.preventDefault();slot.classList.remove("drag");}));
slot.addEventListener("drop",e=>{ if(e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); });

$("#unlock").onclick = async ()=>{
  if(!chosenFile) return;
  clearError();
  const btn=$("#unlock"); const label=btn.textContent;
  btn.disabled=true; btn.innerHTML='<span class="spin"></span>Unlocking…';
  try{
    const res=await fetch("/decrypt?name="+encodeURIComponent(chosenFile.name),{method:"POST",body:chosenFile});
    const data=await res.json();
    if(!res.ok){ showError(data.error||"Decryption failed."); return; }
    allLines=data.lines;
    $("#s-total").textContent=data.total.toLocaleString();
    $("#s-ok").textContent=data.decrypted.toLocaleString();
    $("#s-skip").textContent=data.skipped.toLocaleString();
    results.classList.add("show");
    $("#filter").value="";
    render();
    results.scrollIntoView({behavior:"smooth",block:"nearest"});
  }catch(err){
    showError("Could not reach the server. Is it still running?");
  }finally{
    btn.disabled=false; btn.textContent=label;
  }
};

function render(){
  const q=$("#filter").value.toLowerCase();
  const showLn=$("#lntoggle").checked;
  const filtered=[];
  allLines.forEach((ln,i)=>{ if(!q || ln.text.toLowerCase().includes(q)) filtered.push([i+1,ln]); });

  if(filtered.length===0){
    code.innerHTML=`<div class="empty">${allLines.length? "No lines match this filter." : "This file produced no lines."}</div>`;
    cap.textContent = allLines.length+" lines total";
    return;
  }
  const slice=filtered.slice(0,RENDER_CAP);
  code.innerHTML = slice.map(([n,ln])=>
    `<div class="row ${ln.ok?"":"bad"} ${showLn?"":"hideln"}"><span class="ln">${n}</span><span class="tx">${esc(ln.text)}</span></div>`
  ).join("");
  cap.textContent = filtered.length>RENDER_CAP
    ? `Showing ${RENDER_CAP.toLocaleString()} of ${filtered.length.toLocaleString()} matching lines — narrow the filter to see more`
    : `${filtered.length.toLocaleString()} line${filtered.length===1?"":"s"}${q?" matching":""}`;
}
$("#filter").addEventListener("input", render);
$("#lntoggle").addEventListener("change", render);

function plainText(){ return allLines.map(l=>l.text).join("\n"); }
$("#copy").onclick = async ()=>{
  await navigator.clipboard.writeText(plainText());
  const b=$("#copy"); b.textContent="Copied"; setTimeout(()=>b.textContent="Copy",1200);
};
$("#download").onclick = ()=>{
  const blob=new Blob([plainText()],{type:"text/plain"});
  const a=document.createElement("a");
  a.href=URL.createObjectURL(blob);
  a.download=(chosenFile?chosenFile.name.replace(/\.elog$/,""):"logs")+".decrypted.txt";
  a.click(); URL.revokeObjectURL(a.href);
};
</script>
</body>
</html>"""


if __name__ == "__main__":
    key_path = sys.argv[1] if len(sys.argv) > 1 else "private_key.pem"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8734
    try:
        load_private_key(key_path)
    except FileNotFoundError:
        sys.exit(f"Private key not found: {key_path}\n"
                 f"Generate it with KeyGen.kt, or pass a path: python decrypt_server.py <key.pem> [port]")
    print(f"logvault decrypt server  ->  http://127.0.0.1:{port}")
    print(f"key loaded: ····{_KEY_ID}   (loopback only — decrypted output never leaves this machine)")
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")
        server.shutdown()
