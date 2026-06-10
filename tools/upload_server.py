#!/usr/bin/env python3
"""
LogDaemon upload server — a zero-dependency local endpoint for the native
daemon's HTTP log upload.

The daemon (see logdaemon_helper.c) streams each package's .log / .log.tsv to
this server in 64 KB chunks as the session runs, using a byte offset so chunks
are idempotent and resumable. This server simply writes each chunk at the
offset it was sent for.

Protocol
--------
POST /upload?session=<id>&file=<name>&offset=<n>
    Body: raw bytes. Server seeks to <offset> in uploads/<session>/<file>
    and writes the body there. Writing at an explicit offset makes retried
    chunks idempotent (same bytes land in the same place).
    Response: 200 OK.

GET /
    Health check — prints the uploads directory.

Run
---
    python3 upload_server.py --port 8080

Then put the matching URL in the USB drive's log.sinfo:

    [options]
    upload_url=http://<your-pc-ip>:8080/upload

Find <your-pc-ip> with `ipconfig` (Windows) or `ip addr` / `ifconfig` (Mac/Linux).
The phone and PC must be on the same network.
"""

import argparse
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

UPLOAD_DIR = "uploads"

# Only allow a safe filename charset — blocks path traversal (../, absolute paths).
_UNSAFE = re.compile(r"[^A-Za-z0-9._-]")


def safe_name(name: str) -> str:
    cleaned = _UNSAFE.sub("_", name)[:200]
    return cleaned or "unnamed"


class Handler(BaseHTTPRequestHandler):
    # ── helpers ───────────────────────────────────────────────────────────────
    def _reply(self, code: int, msg: bytes = b"OK"):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(msg)))
        self.end_headers()
        self.wfile.write(msg)

    def log_message(self, *_args):
        pass  # silence the default per-request stderr logging

    # ── routes ────────────────────────────────────────────────────────────────
    def do_GET(self):
        if urlparse(self.path).path == "/":
            body = (
                "LogDaemon upload server is running.\n"
                f"Uploads dir: {os.path.abspath(UPLOAD_DIR)}\n"
            ).encode()
            self._reply(200, body)
        else:
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

        # Write the chunk at its declared offset — idempotent across retries.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o644)
        try:
            os.lseek(fd, offset, os.SEEK_SET)
            os.write(fd, data)
        finally:
            os.close(fd)

        print(f"[recv] {session}/{fname}  {len(data):>6} B @ offset {offset}", flush=True)
        self._reply(200, b"OK")


def main():
    ap = argparse.ArgumentParser(description="LogDaemon local upload server")
    ap.add_argument("--host", default="0.0.0.0", help="bind address (default: all interfaces)")
    ap.add_argument("--port", type=int, default=8080, help="listen port (default: 8080)")
    ap.add_argument("--dir", default="uploads", help="directory to store uploads (default: ./uploads)")
    args = ap.parse_args()

    global UPLOAD_DIR
    UPLOAD_DIR = args.dir
    os.makedirs(UPLOAD_DIR, exist_ok=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"LogDaemon upload server  →  http://{args.host}:{args.port}")
    print(f"Storing uploads in       →  {os.path.abspath(UPLOAD_DIR)}")
    print(f"log.sinfo line           →  upload_url=http://<your-pc-ip>:{args.port}/upload")
    print("Press Ctrl+C to stop.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")


if __name__ == "__main__":
    main()
