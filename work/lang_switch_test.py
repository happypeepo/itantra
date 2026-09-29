#!/usr/bin/env python3
"""
Switch the app through all 10 languages (twice) via the language picker and record,
after each switch: time until "<Language> ready" in the log, and the app's memory
(dumpsys meminfo TOTAL PSS). If native memory is released on switch, PSS stays flat
instead of growing with every language.

    SERIAL=192.168.1.6:33791 python work/lang_switch_test.py
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import devui  # noqa: E402

ADB, PKG, sh, ui = devui.ADB, devui.PKG, devui.sh, devui.ui
NAMES = devui.ORDER


def pss_mb() -> float:
    m = re.search(r"TOTAL PSS:\s+(\d+)", sh(f"dumpsys meminfo {PKG}"))
    return round(int(m.group(1)) / 1024, 1) if m else float("nan")


def select(name: str) -> dict:
    subprocess.run(ADB + ["logcat", "-c"])
    t0 = time.time()                      # start timing before the switch (selecting takes a few seconds)
    try:
        devui.select_language(name)
    except RuntimeError as ex:
        return {"lang": name, "error": str(ex)}
    ready = None
    while time.time() - t0 < 30:
        log = subprocess.run(ADB + ["logcat", "-d", "-s", "iTantra:I"], capture_output=True, text=True).stdout
        if f"{name} ready" in log:
            ready = time.time() - t0
            break
        if "Missing models" in log or "failed" in log.lower():
            return {"lang": name, "error": log.strip().splitlines()[-1][-160:]}
        time.sleep(0.3)
    time.sleep(1.0)
    return {"lang": name, "ready_s": round(ready, 1) if ready else None, "pss_mb": pss_mb()}


def main() -> int:
    rows = [{"lang": "start", "pss_mb": pss_mb()}]
    print(rows[0], flush=True)
    for rnd in (1, 2):
        for name in NAMES:
            r = select(name)
            r["round"] = rnd
            rows.append(r)
            print(r, flush=True)
    os.makedirs("device_test", exist_ok=True)
    json.dump(rows, open("device_test/lang_switch.json", "w"), indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
