"""
Drive the iTantra app's UI over adb (uiautomator). Shared by the device test scripts.

Works with the Material 3 UI (commit 6362974): buttons are found by their visible
label, the page is scrolled when a control is off screen, and the language is picked
from the "Speech language" dropdown.

    SERIAL=<device> python work/tap.py "Hold to talk" 3000
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time

ADB_BIN = os.environ.get("ADB") or shutil.which("adb") or "/Users/bhoumiksangle/Downloads/platform-tools/adb"
ADB = [ADB_BIN] + (["-s", os.environ["SERIAL"]] if os.environ.get("SERIAL") else [])
PKG = "org.itantra.app"
NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?checked="(\w+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def sh(cmd: str) -> str:
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True).stdout


def ui(all_windows: bool = False) -> str:
    """Dump the screen. all_windows=True also includes popups such as the language
    dropdown (needs a recent Android; older ones ignore the flag)."""
    sh(f"uiautomator dump {'--windows ' if all_windows else ''}/sdcard/ui.xml")
    return sh("cat /sdcard/ui.xml")


def nodes(xml: str | None = None) -> list[dict]:
    xml = xml or ui()
    return [{"text": m[0], "cls": m[1], "checked": m[2] == "true",
             "x": (int(m[3]) + int(m[5])) // 2, "y": (int(m[4]) + int(m[6])) // 2, "y1": int(m[4]), "y2": int(m[6])}
            for m in NODE.findall(xml)]


def screen_h() -> int:
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(2)) if m else 2400


def scroll(direction: str = "down") -> None:
    h = screen_h()
    a, b = (int(h * 0.75), int(h * 0.30)) if direction == "down" else (int(h * 0.30), int(h * 0.75))
    sh(f"input swipe 20 {a} 20 {b} 300")  # page margin: a drag across a Switch would toggle it
    time.sleep(0.6)


def find(label: str, cls: str | None = None, tries: int = 4) -> dict | None:
    """Find a control by exact label, scrolling down then up if it's off screen.
    Controls cut off by the screen edge count as off screen."""
    h = screen_h()
    for direction in ("down", "up"):
        for _ in range(tries):
            for n in nodes():
                if n["text"] == label and (cls is None or cls in n["cls"]) and n["y1"] > 60 and n["y2"] < h - 40:
                    return n
            scroll(direction)
    return None


def tap(label: str, hold_ms: int = 0, cls: str | None = None) -> dict:
    n = find(label, cls)
    if not n:
        raise RuntimeError(f"control {label!r} not found on screen")
    if hold_ms:
        sh(f"input swipe {n['x']} {n['y']} {n['x']} {n['y']} {hold_ms}")
    else:
        sh(f"input tap {n['x']} {n['y']}")
    return n


def checked(label: str) -> bool | None:
    n = find(label)
    return n["checked"] if n else None


def set_switch(label: str, on: bool) -> None:
    n = find(label)
    if n and n["checked"] != on:
        sh(f"input tap {n['x']} {n['y']}")
        time.sleep(0.5)


def status() -> str:
    for _ in range(3):
        scroll("up")
    for n in nodes():
        if re.match(r"(Not connected|Connected|Hosting|Connecting|Disconnected)", n["text"]):
            return n["text"]
    return "?"


ORDER = ["Hindi", "English", "Bengali", "Gujarati", "Kannada", "Malayalam", "Marathi", "Odia", "Tamil", "Telugu"]  # manifest wire_id order = dropdown order


def picker() -> dict | None:
    for direction in (None, "down", "up", "up"):
        if direction:
            scroll(direction)
        p = next((n for n in nodes() if "Spinner" in n["cls"] or "AutoCompleteTextView" in n["cls"]), None)
        if p:
            return p
    return None


def _tap_item_in_popup(name: str) -> bool:
    """With the dropdown open, tap `name` if uiautomator can see the popup; scroll the list if needed."""
    for _ in range(6):
        items = [n for n in nodes(ui(all_windows=True)) if n["text"] in ORDER and "Spinner" not in n["cls"]]
        if not items:
            return False                                  # popup not visible to uiautomator (older Android)
        full = [n for n in items if n["y2"] - n["y1"] > 40]
        hit = next((n for n in full if n["text"] == name), None)
        if hit:
            sh(f"input tap {hit['x']} {hit['y']}")
            return True
        top, bot = min(full or items, key=lambda n: n["y"]), max(full or items, key=lambda n: n["y"])
        down = ORDER.index(name) > ORDER.index(bot["text"])
        a, b = (bot["y"], top["y"]) if down else (top["y"], bot["y"])
        sh(f"input swipe 540 {a} 540 {b} 400")
        time.sleep(0.7)
    return False


def select_language(name: str) -> None:
    """Pick `name` in the Speech language dropdown and check the picker shows it.
    Taps the item by label when the popup is visible to uiautomator (Android 14+);
    otherwise uses the keyboard: DOWN (index + 1) times, then ENTER."""
    for attempt in range(3):
        p = picker()
        if p is None:
            raise RuntimeError("language picker not found")
        if p["text"] == name:
            return
        sh(f"input tap {p['x']} {p['y']}")
        time.sleep(1.2)
        if not _tap_item_in_popup(name):
            for _ in range(ORDER.index(name) + 1):
                sh("input keyevent KEYCODE_DPAD_DOWN")
                time.sleep(0.12)
            sh("input keyevent KEYCODE_ENTER")
        time.sleep(1.0)
    p = picker()
    if not p or p["text"] != name:
        raise RuntimeError(f"picked {p and p['text']!r}, wanted {name!r}")


def join(ip: str) -> None:
    """Type the host's IP into the address field and tap Join."""
    f = find("Host IP address") or next((n for n in nodes() if "EditText" in n["cls"]), None)
    if f is None:
        for _ in range(3):
            scroll("up")
        f = next((n for n in nodes() if "EditText" in n["cls"]), None)
    sh(f"input tap {f['x']} {f['y']}")
    time.sleep(0.4)
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(20):
        sh("input keyevent KEYCODE_DEL")
    sh(f"input text {ip}")
    sh("input keyevent KEYCODE_BACK")   # hide the keyboard
    time.sleep(0.5)
    tap("Join")
