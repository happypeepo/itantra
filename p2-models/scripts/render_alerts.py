#!/usr/bin/env python3
"""
Pre-make every alert as a WAV file, in every language.

The app never runs TTS for an alert. It just plays these files. That makes
alerts instant, and they can't fail because a model is still loading.

    python render_alerts.py --models ./models --out ./alerts

Writes:
    alerts/<lang>/<alert_name>.wav   16-bit mono, at the voice's sample rate
    alerts/index.json                alert id -> name, for P1 and P3

Each file = attention tone + short gap + the spoken phrase, made as loud as
possible without clipping. Use --no-tone to skip the tone.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
from common import load_json, make_tts, read_char_vocab, sanitize, tts_generate  # noqa: E402

HERE = Path(__file__).resolve().parent.parent
PEAK = 10 ** (-1 / 20)  # -1 dBFS


def tone(sr: int) -> np.ndarray:
    """Two short beeps, 880 Hz then 1320 Hz, with soft edges."""
    def beep(freq: float, secs: float) -> np.ndarray:
        t = np.arange(int(sr * secs)) / sr
        env = np.minimum(1.0, np.minimum(t, t[::-1]) / 0.01)  # 10 ms fade in/out
        return (np.sin(2 * np.pi * freq * t) * env).astype(np.float32)
    gap = np.zeros(int(sr * 0.08), dtype=np.float32)
    return np.concatenate([beep(880, 0.15), gap, beep(1320, 0.15)])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--manifest", default=str(HERE / "manifest.json"))
    ap.add_argument("--alerts", default=str(HERE / "alerts.json"))
    ap.add_argument("--out", default="alerts")
    ap.add_argument("--no-tone", action="store_true")
    args = ap.parse_args()

    models, out = Path(args.models), Path(args.out)
    man, alerts = load_json(args.manifest), load_json(args.alerts)["alerts"]
    cache: dict[str, object] = {}
    missing = []

    for code, L in man["languages"].items():
        voice, eng_name = L["tts"], L["tts"].get("engine")
        if not eng_name or eng_name not in man["tts_engines"]:
            print(f"  {code}: no TTS voice in the manifest - skipped")
            continue
        eng = man["tts_engines"][eng_name]
        if eng_name not in cache:
            cache[eng_name] = make_tts(eng, models)
        vocab = read_char_vocab(models / eng["tokens"]) if eng["frontend"] == "characters" else None
        (out / code).mkdir(parents=True, exist_ok=True)

        for name, a in alerts.items():
            text = a["texts"].get(code)
            if not text:
                missing.append(f"{code}/{name}")
                continue
            speech, sr = tts_generate(cache[eng_name], sanitize(text, vocab),
                                      voice.get("sid", 0), voice.get("speed", 1.0),
                                      voice.get("emotion_id") if eng_name == "rasa" else None)
            lead = np.zeros(int(sr * 0.15), dtype=np.float32)  # speaker wake-up time
            parts = [lead] if args.no_tone else [lead, tone(sr), np.zeros(int(sr * 0.2), np.float32)]
            audio = np.concatenate(parts + [speech, np.zeros(int(sr * 0.2), np.float32)])
            peak = float(np.max(np.abs(audio))) or 1.0
            audio = audio * (PEAK / peak)
            path = out / code / f"{name}.wav"
            sf.write(path, audio, sr, subtype="PCM_16")
            print(f"  {path}  {len(audio) / sr:.1f}s")

    index = {str(a["id"]): name for name, a in alerts.items()}
    (out / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    if missing:
        print("\nNo text for:", ", ".join(missing))
    print(f"\nWrote alerts to {out}/ - LISTEN to every file before the demo.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
