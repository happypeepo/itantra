#!/usr/bin/env python3
"""Tap (or hold) an iTantra app control by its visible label. Scrolls if it's off screen.

    SERIAL=<device> python work/tap.py Host
    SERIAL=<device> python work/tap.py "Hold to talk" 3000
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import devui  # noqa: E402

label, hold = sys.argv[1], (int(sys.argv[2]) if len(sys.argv) > 2 else 0)
n = devui.tap(label, hold)
print(f"{'held' if hold else 'tapped'} {label} at {n['x']},{n['y']}" + (f" for {hold} ms" if hold else ""))
