#!/usr/bin/env python3
"""
Stand-in for "the other phone": drive the iTantra app on ONE real phone from the laptop.

The phone taps HOST; `adb forward tcp:26173 tcp:26173` makes its server reachable at
127.0.0.1:26173 on the laptop; this script joins as the peer and speaks the exact wire
format (p2-models/scripts/frame.py). It prints every frame the phone sends back, and
echoes the phone's (even-numbered) pings like a real joiner does.

    python work/fake_peer.py rx-test            # pings, speech in all 10 languages, bad CRC, alerts
    python work/fake_peer.py listen --secs 60   # just print what the phone sends (for PTT tests)

Timing on the phone side is read from its logcat (tag iTantra) afterwards.
"""
from __future__ import annotations

import argparse
import json
import re
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
import frame as fr  # noqa: E402

ADB = "/Users/bhoumiksangle/Downloads/platform-tools/adb"
MAN = json.loads((ROOT / "p2-models/manifest.json").read_text(encoding="utf-8"))["languages"]
SCRIPT_OF = {L["wire_id"]: L["script"] for L in MAN.values()}
CODE_OF = {L["wire_id"]: c for c, L in MAN.items()}
SENTS = json.loads((ROOT / "p2-models/test_sentences.json").read_text(encoding="utf-8"))["sentences"]["road_blocked"]


def alarm_volume() -> str:
    out = subprocess.run([ADB, "shell", "dumpsys audio"], capture_output=True, text=True).stdout
    m = re.search(r"- STREAM_ALARM:.*?Current: ([^\n]*)", out, re.S)
    return m.group(1).strip()[:80] if m else "?"


class Peer:
    def __init__(self, port: int = 26173):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.s.settimeout(None)
        self.seq = 1
        self.rx: list[dict] = []
        self.pongs: dict[int, float] = {}
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        while True:
            raw = fr.read_frame(self.s)
            if raw is None:
                print("   [peer] phone closed the connection"); return
            t = time.perf_counter()
            try:
                m = fr.decode(raw, lambda w: SCRIPT_OF[w])
            except ValueError as e:
                print("   [peer] bad frame from phone:", e); continue
            m["bytes"] = len(raw)
            if m["type"] == fr.T_PING:
                if m["seq"] % 2 == 1:
                    self.pongs[m["seq"]] = t          # echo of our ping
                else:
                    self.s.sendall(raw)                # phone's ping: echo it back
                    print(f"   [peer] echoed phone ping #{m['seq']}")
                continue
            self.rx.append(m)
            what = m.get("text", f"alert id {m.get('alert_id')}")
            print(f"   [peer] RX from phone: type 0x{m['type']:02x} lang {CODE_OF.get(m['wire_id'])} #{m['seq']} "
                  f"{m['bytes']} bytes: {what}", flush=True)

    def _next(self) -> int:
        self.seq = (self.seq + 2) & 0xFFFF
        return self.seq

    def send_raw(self, b: bytes):
        self.s.sendall(b)

    def ping(self) -> float:
        seq = self._next() | 1
        t0 = time.perf_counter()
        self.send_raw(fr._frame(fr.T_PING, 0, seq, b""))
        while seq not in self.pongs and time.perf_counter() - t0 < 3:
            time.sleep(0.001)
        return (self.pongs[seq] - t0) * 1000 if seq in self.pongs else float("nan")

    def speech(self, lang: str, text: str) -> int:
        L = MAN[lang]
        seq = self._next()
        f = fr.encode_speech(text, L["wire_id"], L["script"], seq)
        self.send_raw(f)
        return len(f)

    def alert(self, alert_id: int, sender_lang: str):
        self.send_raw(fr.encode_alert(alert_id, MAN[sender_lang]["wire_id"], self._next()))


def rx_test(a) -> int:
    p = Peer()
    print("== ping (laptop <-> phone over USB adb forward)")
    rtts = [p.ping() for _ in range(5)]
    print("   RTT ms:", [round(x, 1) for x in rtts])

    print("== speech in every language (phone speaks it in the SENDER's language)")
    for lang in a.langs.split(","):
        n = p.speech(lang, SENTS[lang])
        print(f"   sent {lang}: {n} bytes  {SENTS[lang][:50]}", flush=True)
        time.sleep(a.gap)

    print("== corrupted frame (one payload bit flipped): phone must drop it, not speak it")
    bad = bytearray(fr.encode_speech("this must not be spoken", MAN["en"]["wire_id"], "Latin", 999)); bad[8] ^= 1
    p.send_raw(bytes(bad)); time.sleep(1)

    print("== alert from a Hindi sender (phone plays it in ITS OWN selected language)")
    print("   alarm volume before:", alarm_volume())
    p.alert(2, "hi")
    time.sleep(1.0)
    print("   alarm volume during:", alarm_volume())
    time.sleep(4)
    print("   alarm volume after: ", alarm_volume())

    print("== alert interrupts ongoing speech")
    p.speech("en", "This is a long message that should be cut off by the alert when it arrives, so it should not finish playing")
    time.sleep(1.5)
    p.alert(1, "ta")
    time.sleep(6)
    print("== done")
    return 0


def speech_seq(a) -> int:
    p = Peer()
    for lang in a.langs.split(","):
        n = p.speech(lang, SENTS[lang])
        print(f"   sent {lang}: {n} bytes", flush=True)
        time.sleep(a.gap)
    print(f"phone sent {len(p.rx)} speech frame(s) back:", [m.get("text") for m in p.rx])
    return 0


def listen(a) -> int:
    p = Peer()
    print(f"listening {a.secs}s for frames from the phone ...", flush=True)
    time.sleep(a.secs)
    print(json.dumps(p.rx, ensure_ascii=False))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("rx-test")
    r.add_argument("--langs", default="hi,en,bn,kn,ml,mr,ta,te,gu,or")
    r.add_argument("--gap", type=float, default=9.0, help="seconds between messages (let each one finish)")
    q = sub.add_parser("speech")
    q.add_argument("--langs", required=True, help="e.g. en,en,en,ta,ta,ta")
    q.add_argument("--gap", type=float, default=9.0)
    l = sub.add_parser("listen")
    l.add_argument("--secs", type=float, default=30)
    a = ap.parse_args()
    return {"rx-test": rx_test, "speech": speech_seq, "listen": listen}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
