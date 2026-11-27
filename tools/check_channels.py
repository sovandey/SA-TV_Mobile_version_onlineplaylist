#!/usr/bin/env python3
"""
SA-TV channel checker (runs on GitHub Actions every 6 hours, or when someone reports a broken channel).

What it does for playlist/channels.m3u:
  * tests every link (HLS playlist + first segment, or a few bytes of an audio stream)
  * working links are moved first, so the app tries them first
  * if EVERY link of a channel fails, it looks for a replacement (same tvg-id) in the public iptv-org
    index and adds the working one as the first link (old links are kept as backups)
  * writes playlist/STATUS.md (+ report.md) so you can see which channels still fail

It NEVER deletes a channel and never hides one: GitHub servers are in the USA, so India/Bangladesh
only streams can look "dead" from there even though they work for your users. You decide what to remove.
"""
import concurrent.futures as cf
import http.client
import os
import re
import sys
import time
import urllib.request
from urllib.parse import urljoin

PLAYLIST = os.environ.get("PLAYLIST", "playlist/channels.m3u")
STATUS = os.environ.get("STATUS_FILE", "playlist/STATUS.md")
REPORT = os.environ.get("REPORT_FILE", "report.md")
INDEX_URL = os.environ.get("INDEX_URL", "https://iptv-org.github.io/iptv/index.m3u")
MAX_LINKS = 3
UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"


def http_get(url, timeout=12, rng=None, maxbytes=65536):
    headers = {"User-Agent": UA}
    if rng:
        headers["Range"] = rng
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.geturl(), r.read(maxbytes)


def media_lines(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def alive(url):
    try:
        path = url.lower().split("?")[0]
        if path.endswith(".m3u8") or ".m3u8" in url.lower():
            st, final, body = http_get(url)
            text = body.decode("utf-8", "ignore")
            if st != 200 or not text.lstrip().startswith("#EXTM3U"):
                return False
            lines = media_lines(text)
            if not lines:
                return False
            nxt = urljoin(final, lines[0])
            if ".m3u8" in nxt.lower():  # master playlist -> media playlist
                st2, final2, body2 = http_get(nxt)
                t2 = body2.decode("utf-8", "ignore")
                if st2 != 200 or not t2.lstrip().startswith("#EXTM3U"):
                    return False
                segs = media_lines(t2)
                if not segs:
                    return False
                seg = urljoin(final2, segs[-1])
            else:
                seg = urljoin(final, lines[-1])
            st3, _, b3 = http_get(seg, rng="bytes=0-1023", maxbytes=1024)
            return st3 in (200, 206) and len(b3) > 0
        st, _, body = http_get(url, maxbytes=2048)  # radio / direct stream
        return st == 200 and len(body) > 0
    except http.client.BadStatusLine as e:  # Shoutcast "ICY 200 OK"
        return "ICY" in str(e)
    except Exception:
        return False


def parse(path):
    header, entries, pending = [], [], None
    for raw in open(path, encoding="utf-8").read().splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            pending = raw
        elif line.startswith("#") or not line:
            if pending is None and not entries:
                header.append(raw)
        elif line.startswith("http"):
            if pending is None:
                continue
            j = pending.index(",")
            name = pending[j + 1:].strip()
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', pending[:j]))
            entries.append({"extinf": pending, "name": name, "attrs": attrs, "url": line})
            pending = None
    return header, entries


def load_index():
    try:
        st, _, _ = http_get(INDEX_URL, timeout=30, maxbytes=1)
    except Exception as e:
        print("iptv-org index not reachable:", e)
        return {}
    req = urllib.request.Request(INDEX_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        text = r.read().decode("utf-8", "ignore")
    by_id, lines = {}, text.splitlines()
    for i, l in enumerate(lines):
        if l.startswith("#EXTINF") and i + 1 < len(lines):
            m = re.search(r'tvg-id="([^"]+)"', l)
            u = lines[i + 1].strip()
            if m and u.startswith("http"):
                by_id.setdefault(m.group(1).split("@")[0].lower(), []).append(u)
    return by_id


def main():
    header, entries = parse(PLAYLIST)
    if not entries:
        print("playlist empty - nothing to do")
        return 0

    with cf.ThreadPoolExecutor(max_workers=24) as ex:
        results = list(ex.map(lambda e: alive(e["url"]), entries))
    for e, ok in zip(entries, results):
        e["ok"] = ok

    total_ok = sum(results)
    print(f"{total_ok}/{len(entries)} links work")
    if total_ok < len(entries) * 0.15:
        print("Almost nothing works - the runner is probably blocked. No changes written.")
        with open(REPORT, "w", encoding="utf-8") as f:
            f.write("Check skipped: almost every link failed, so the problem is probably the test "
                    "server's network, not the channels. Playlist was not changed.\n")
        return 0

    # group by channel name, keep first-appearance order
    groups = {}
    for e in entries:
        groups.setdefault(e["name"].lower(), []).append(e)

    index = None
    fixed, failing, reordered = [], [], []
    out_entries = []
    for key, grp in groups.items():
        if not any(e["ok"] for e in grp):
            tvg = (grp[0]["attrs"].get("tvg-id") or "").split("@")[0].lower()
            if tvg:
                if index is None:
                    index = load_index()
                cands = [u for u in index.get(tvg, []) if u not in {e["url"] for e in grp}][:6]
                with cf.ThreadPoolExecutor(max_workers=6) as ex:
                    good = [u for u, ok in zip(cands, ex.map(alive, cands)) if ok]
                if good:
                    new = dict(grp[0])
                    new["url"], new["ok"] = good[0], True
                    grp = [new] + grp
                    fixed.append(grp[0]["name"])
        if not any(e["ok"] for e in grp):
            failing.append(grp[0]["name"])
        ordered = sorted(grp, key=lambda e: 0 if e["ok"] else 1)  # stable: working first
        if [e["url"] for e in ordered] != [e["url"] for e in grp] and grp[0]["name"] not in fixed:
            reordered.append(grp[0]["name"])
        out_entries.extend(ordered[:MAX_LINKS])

    lines = list(header)
    if lines and lines[-1].strip():
        lines.append("")
    for e in out_entries:
        lines.append(e["extinf"])
        lines.append(e["url"])
    new_text = "\n".join(lines) + "\n"
    old_text = open(PLAYLIST, encoding="utf-8").read()
    if new_text != old_text:
        open(PLAYLIST, "w", encoding="utf-8").write(new_text)
        print("playlist updated")

    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
    rep = [f"## SA-TV channel check - {stamp}", "",
           f"- Working links: **{total_ok} / {len(entries)}**",
           f"- Replaced automatically: **{len(fixed)}**",
           f"- Reordered (working link moved first): **{len(reordered)}**",
           f"- Still failing from the test server: **{len(failing)}**", ""]
    if fixed:
        rep += ["### Replaced automatically"] + [f"- {n}" for n in fixed] + [""]
    if failing:
        rep += ["### Still failing (no working link found)",
                "Some may be India/Bangladesh-only and still work for your users. "
                "Remove or replace them in `playlist/channels.m3u` if they are really dead.", ""]
        rep += [f"- {n}" for n in failing] + [""]
    text = "\n".join(rep)
    open(STATUS, "w", encoding="utf-8").write(text)
    open(REPORT, "w", encoding="utf-8").write(text)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
