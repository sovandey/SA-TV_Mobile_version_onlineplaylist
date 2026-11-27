#!/usr/bin/env python3
"""Called by the release workflow: writes the new version into playlist/config.json."""
import json
import sys

code, tag, repo = int(sys.argv[1]), sys.argv[2], sys.argv[3]
notes = sys.argv[4] if len(sys.argv) > 4 else ""
path = "playlist/config.json"
cfg = json.load(open(path, encoding="utf-8"))
cfg["latestVersionCode"] = code
cfg["latestVersionName"] = tag.lstrip("v")
cfg["apkUrl"] = f"https://github.com/{repo}/releases/download/{tag}/SA-TV.apk"
cfg["notes"] = notes
json.dump(cfg, open(path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
