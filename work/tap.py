#!/usr/bin/env python3
"""Tap an on-screen button of the iTantra app by its label (uses uiautomator)."""
import re, subprocess, sys
ADB = "/Users/bhoumiksangle/Downloads/platform-tools/adb"
import os
SERIAL = os.environ.get("SERIAL")
if SERIAL: ADB = f"{ADB} -s {SERIAL}"
label = sys.argv[1]; hold_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 0
subprocess.run(ADB.split() + ["shell", "uiautomator dump /sdcard/ui.xml"], capture_output=True)
xml = subprocess.run(ADB.split() + ["shell", "cat /sdcard/ui.xml"], capture_output=True, text=True).stdout
m = re.search(r'text="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml)
if not m: sys.exit(f"button {label!r} not on screen")
x1, y1, x2, y2 = map(int, m.groups()); x, y = (x1 + x2) // 2, (y1 + y2) // 2
cmd = f"input swipe {x} {y} {x} {y} {hold_ms}" if hold_ms else f"input tap {x} {y}"
subprocess.run(ADB.split() + ["shell", cmd]); print(f"{'held' if hold_ms else 'tapped'} {label} at {x},{y}" + (f" for {hold_ms} ms" if hold_ms else ""))
