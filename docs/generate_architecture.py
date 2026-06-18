"""
Generates architecture.png — logdaemon flow state diagram.
Run: python3 generate_architecture.py
"""
from PIL import Image, ImageDraw, ImageFont
import os

# ── canvas ────────────────────────────────────────────────────────────────────
W, H = 1000, 1480
SCALE = 2          # 2× for retina-quality output
img = Image.new("RGB", (W * SCALE, H * SCALE), "#ffffff")
d = ImageDraw.Draw(img)

def s(v):
    """Scale a coordinate or size."""
    if isinstance(v, (list, tuple)):
        return [x * SCALE for x in v]
    return v * SCALE

# ── font helpers ──────────────────────────────────────────────────────────────
def font(size, bold=False):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans{}.ttf".format("-Bold" if bold else ""),
        "/usr/share/fonts/truetype/liberation/LiberationSans{}-Regular.ttf".format("-Bold" if bold else ""),
        "/usr/share/fonts/truetype/freefont/FreeSans{}.ttf".format("Bold" if bold else ""),
    ]
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, s(size))
    return ImageFont.load_default()

F_TITLE   = font(17, bold=True)
F_HEADING = font(12, bold=True)
F_BODY    = font(10.5)
F_SMALL   = font(9.5)
F_MAIN    = font(22, bold=True)

# ── colours ───────────────────────────────────────────────────────────────────
COLORS = {
    "boot":   {"bg": "#eff6ff", "border": "#93c5fd", "num_bg": "#dbeafe", "num_fg": "#1d4ed8", "head": "#1d4ed8"},
    "detect": {"bg": "#f5f3ff", "border": "#c4b5fd", "num_bg": "#ede9fe", "num_fg": "#6d28d9", "head": "#6d28d9"},
    "start":  {"bg": "#f0fdf4", "border": "#86efac", "num_bg": "#dcfce7", "num_fg": "#15803d", "head": "#15803d"},
    "active": {"bg": "#f8fafc", "border": "#cbd5e1", "num_bg": "#e2e8f0", "num_fg": "#334155", "head": "#334155"},
    "main":   {"bg": "#ecfdf5", "border": "#6ee7b7", "head": "#065f46"},
    "sync":   {"bg": "#fefce8", "border": "#fde047", "head": "#854d0e"},
    "end":    {"bg": "#fff7ed", "border": "#fdba74", "num_bg": "#fed7aa", "num_fg": "#c2410c", "head": "#c2410c"},
    "arrow":  "#94a3b8",
    "label_bg": "#f1f5f9",
    "label_fg": "#475569",
    "body_fg": "#374151",
    "dot":    "#9ca3af",
}

# ── drawing helpers ───────────────────────────────────────────────────────────

def rrect(x, y, w, h, r, fill, outline, lw=2):
    x, y, w, h, r, lw = s(x), s(y), s(w), s(h), s(r), s(lw)
    d.rounded_rectangle([x, y, x+w, y+h], radius=r, fill=fill, outline=outline, width=lw)

def text(x, y, txt, fnt, fill="#1e293b", anchor="lt"):
    d.text((s(x), s(y)), txt, font=fnt, fill=fill, anchor=anchor)

def badge(cx, cy, num, c):
    """Numbered circle badge."""
    r = s(13)
    cx, cy = s(cx), s(cy)
    d.ellipse([cx-r, cy-r, cx+r, cy+r], fill=c["num_bg"])
    d.text((cx, cy), str(num), font=font(10, bold=True), fill=c["num_fg"], anchor="mm")

def bullet_lines(x, y, lines, fnt=None, gap=18):
    """Render bullet list; returns y after last line."""
    if fnt is None:
        fnt = F_BODY
    for line in lines:
        d.text((s(x - 1), s(y)), "·", font=fnt, fill=COLORS["dot"])
        d.text((s(x + 10), s(y)), line, font=fnt, fill=COLORS["body_fg"])
        y += gap
    return y

def arrow(cx, y_top, y_bot, label=None):
    """Vertical arrow with optional label."""
    cx_s = s(cx)
    d.line([(cx_s, s(y_top)), (cx_s, s(y_bot - 10))], fill=COLORS["arrow"], width=s(2))
    # arrowhead
    aw = s(7)
    bot = s(y_bot)
    d.polygon([(cx_s, bot), (cx_s - aw, bot - s(12)), (cx_s + aw, bot - s(12))],
              fill=COLORS["arrow"])
    if label:
        tw = d.textlength(label, font=F_SMALL)
        lx = cx_s - tw / 2
        ly = s(y_top) + (s(y_bot) - s(y_top)) // 2 - s(9)
        pad = s(5)
        d.rounded_rectangle([lx - pad, ly - pad, lx + tw + pad, ly + s(10) + pad],
                             radius=s(4), fill=COLORS["label_bg"], outline="#e2e8f0", width=s(1))
        d.text((lx, ly), label, font=F_SMALL, fill=COLORS["label_fg"])

def state_card(x, y, w, h, c, num, title, lines, gap=18):
    """Full state card; returns bottom y."""
    rrect(x, y, w, h, 12, c["bg"], c["border"])
    badge(x + 22, y + 22, num, c)
    text(x + 42, y + 14, title, F_HEADING, fill=c["head"])
    bullet_lines(x + 18, y + 40, lines, gap=gap)
    return y + h

# ── layout constants ──────────────────────────────────────────────────────────
CX  = 500          # horizontal centre
X   = 60           # left edge of cards
CW  = 880          # card width
ARH = 52           # arrow zone height

# ── title ─────────────────────────────────────────────────────────────────────
title_txt = "logdaemon — Flow State Architecture"
tw = d.textlength(title_txt, font=F_MAIN)
d.text((s(CX), s(32)), title_txt, font=F_MAIN, fill="#1e293b", anchor="mt")
sub = "init.rc Native Daemon  ·  Android Log Capture System"
d.text((s(CX), s(62)), sub, font=F_SMALL, fill="#64748b", anchor="mt")

# ── STATE 1: Boot ─────────────────────────────────────────────────────────────
y = 92
c = COLORS["boot"]
rrect(X, y, CW, 100, 12, c["bg"], c["border"])
badge(X+22, y+22, 1, c)
text(X+42, y+14, "Boot", F_HEADING, fill=c["head"])
bullet_lines(X+18, y+40, [
    "init reads  logdaemon.rc  at device boot",
    "Daemon started as  uid = 0  (root)",
    "Waits for  sys.boot_completed = 1",
    "Auto-restarts every 5 s on exit",
], gap=16)
y += 100

# ── arrow ─────────────────────────────────────────────────────────────────────
arrow(CX, y+6, y+ARH-6, "boot_completed = 1")
y += ARH

# ── STATE 2: USB Detection ────────────────────────────────────────────────────
c = COLORS["detect"]
rrect(X, y, CW, 108, 12, c["bg"], c["border"])
badge(X+22, y+22, 2, c)
text(X+42, y+14, "USB Detection", F_HEADING, fill=c["head"])
bullet_lines(X+18, y+40, [
    "Scans  /mnt/media_rw/  every 5 s",
    "Finds log.sinfo  →  reads package list + min log level",
    "File closed immediately — no USB handle retained",
    "Loops until log.sinfo is present",
], gap=16)
# self-loop badge
slx, sly = X + CW - 4, y + 40
rrect(slx - 200, sly - 2, 196, 24, 6, "#f5f3ff", "#c4b5fd", lw=1)
text(slx - 196, sly + 2, "↺  log.sinfo not found — sleep 5 s", F_SMALL, fill="#6d28d9")
y += 108

# ── arrow ─────────────────────────────────────────────────────────────────────
arrow(CX, y+6, y+ARH-6, "log.sinfo found")
y += ARH

# ── STATE 3: Session Start ────────────────────────────────────────────────────
c = COLORS["start"]
rrect(X, y, CW, 108, 12, c["bg"], c["border"])
badge(X+22, y+22, 3, c)
text(X+42, y+14, "Session Start", F_HEADING, fill=c["head"])
bullet_lines(X+18, y+40, [
    "Creates  /data/media/0/LogDaemon/logs/<timestamp>/",
    "Opens  .log  and  .tsv  writers per target package",
    "Spawns sync thread",
    "Forks  logcat -T <last_ts>  as root — replays ring buffer from checkpoint",
], gap=16)
y += 108

# ── arrow ─────────────────────────────────────────────────────────────────────
arrow(CX, y+6, y+ARH-6, "threads running")
y += ARH

# ── STATE 4: Active Session ───────────────────────────────────────────────────
c = COLORS["active"]
ACTIVE_H = 250
rrect(X, y, CW, ACTIVE_H, 12, c["bg"], c["border"])
badge(X+22, y+22, 4, c)
text(X+42, y+14, "Active Session", F_HEADING, fill=c["head"])

# parallel label
par = "── running in parallel ──"
pw = d.textlength(par, font=F_SMALL)
d.text((s(CX), s(y+40)), par, font=F_SMALL, fill="#94a3b8", anchor="mt")

# sub-cards
sub_y = y + 56
sub_h = ACTIVE_H - 70
half_w = (CW - 36) // 2

# Main Thread
cm = COLORS["main"]
rrect(X+10, sub_y, half_w, sub_h, 8, cm["bg"], cm["border"])
text(X+22, sub_y+10, "Main Thread", F_HEADING, fill=cm["head"])
bullet_lines(X+20, sub_y+30, [
    "Reads logcat pipe as root",
    "Captures logs from all user profiles",
    "Matches PID to target package",
    "Writes .log / .tsv to internal storage",
    "Checkpoints .last_ts every 64 lines",
], gap=15)

# Sync Thread
cs = COLORS["sync"]
sx = X + 10 + half_w + 16
rrect(sx, sub_y, half_w, sub_h, 8, cs["bg"], cs["border"])
text(sx+12, sub_y+10, "Sync Thread", F_HEADING, fill=cs["head"])
bullet_lines(sx+10, sub_y+30, [
    "Wakes every 5 s",
    "Checks USB via  stat()",
    "Reads offset → streams 64 KB chunk",
    "open → fwrite → close  per cycle",
    "3 consecutive misses = USB gone",
], gap=15)

y += ACTIVE_H

# ── arrow ─────────────────────────────────────────────────────────────────────
arrow(CX, y+6, y+ARH-6, "USB ejected  (3 consecutive misses · 15 s)")
y += ARH

# ── STATE 5: Session End ──────────────────────────────────────────────────────
c = COLORS["end"]
rrect(X, y, CW, 108, 12, c["bg"], c["border"])
badge(X+22, y+22, 5, c)
text(X+42, y+14, "Session End", F_HEADING, fill=c["head"])
bullet_lines(X+18, y+40, [
    "g_usb_gone = 1  →  capture loop exits cleanly",
    "Log writers flushed and closed",
    "_summary.tsv  written to internal storage",
    "Sync thread performs final USB flush",
    "Process exits  →  init restarts daemon after 5 s",
], gap=16)
y += 108

# ── restart loop banner ───────────────────────────────────────────────────────
y += 12
rrect(X, y, CW, 36, 8, "#f8fafc", "#94a3b8", lw=1)
loop_txt = "↺   init restarts logdaemon after 5 s  →  returns to USB Detection (step 2)"
d.text((s(CX), s(y+10)), loop_txt, font=F_SMALL, fill="#64748b", anchor="mt")
y += 36

# ── final crop & save ─────────────────────────────────────────────────────────
from PIL import ImageChops
bg_ref = Image.new("RGB", img.size, "#ffffff")
bbox = ImageChops.difference(img, bg_ref).getbbox()
pad = s(28)
cropped = img.crop((
    max(0, bbox[0] - pad),
    max(0, bbox[1] - pad),
    min(img.width,  bbox[2] + pad),
    min(img.height, bbox[3] + pad),
))
# downsample back to 1×
out = cropped.resize((cropped.width // SCALE, cropped.height // SCALE), Image.LANCZOS)
out.save("/home/user/Android-Logger/docs/architecture.png", "PNG", dpi=(150, 150))
print(f"Saved architecture.png  {out.size[0]}×{out.size[1]} px")
