#!/usr/bin/env python3
"""
Push-to-talk in every language, over the air: for each language the app is switched
to it, HOLD TO TALK is held via adb, and the laptop's speaker plays that language's
test sentence (our own TTS clip from bench_out/tts/). The phone's mic → STT → frame
arrives at the laptop peer, and is scored against the known text.

This is TTS audio through a speaker, NOT real human speech. It tests the app's
mic/STT/send path in each language, not real-speaker accuracy.

    SERIAL=4DQ459JZ9P9T7XXC python work/ptt_langs_test.py   (phone must be hosting, adb forward on 26173)
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "work"))
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
import fake_peer  # noqa: E402
import lang_switch_test as ls  # noqa: E402
from common import cer, wer  # noqa: E402

MAN = json.loads((ROOT / "p2-models/manifest.json").read_text(encoding="utf-8"))["languages"]
SENTS = json.loads((ROOT / "p2-models/test_sentences.json").read_text(encoding="utf-8"))["sentences"]["road_blocked"]
PORT = int(os.environ.get("PORT", "26173"))


def main() -> int:
    langs = sys.argv[1].split(",") if len(sys.argv) > 1 else list(MAN)
    peer = fake_peer.Peer(PORT)
    results = []
    for code in langs:
        L = MAN[code]
        r = ls.select(L["name"])
        if r.get("ready_s") is None:
            print(code, "not ready:", r); continue
        clip = ROOT / "bench_out/tts" / f"{code}_{L['tts']['engine']}" / "05_road_blocked.wav"
        dur = sf.info(str(clip)).duration
        btn = ls.devui.find("Hold to talk")
        x, y = btn["x"], btn["y"]
        n_before = len(peer.rx)
        hold_ms = int((dur + 1.6) * 1000)
        subprocess.Popen(ls.ADB + ["shell", f"input swipe {x} {y} {x} {y} {hold_ms}"])
        time.sleep(0.9)
        subprocess.run(["afplay", str(clip)])
        t_wait = time.time()
        while len(peer.rx) == n_before and time.time() - t_wait < 8:
            time.sleep(0.1)
        got = peer.rx[n_before]["text"] if len(peer.rx) > n_before else ""
        row = {"lang": code, "ref": SENTS[code], "got": got, "cer": round(cer(SENTS[code], got), 3),
               "wer": round(wer(SENTS[code], got), 3), "frame_bytes": peer.rx[n_before]["bytes"] if got else None}
        results.append(row)
        print(f"{code}: CER {row['cer']:.3f} WER {row['wer']:.3f}  {got}", flush=True)
        time.sleep(1.5)
    os.makedirs(ROOT / "device_test", exist_ok=True)
    json.dump(results, open(ROOT / "device_test/ptt_langs.json", "w"), ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
