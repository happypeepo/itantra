#!/usr/bin/env python3
"""
Test every language on the laptop BEFORE anything goes on a phone.

For each language, for each alert phrase:
    text --(TTS)--> speech --(STT)--> text again
then compare the two texts. If they match closely, both models work.
No recordings and no native speaker are needed for this check.

It also catches the silent failures:
    - STT that returns empty text        (bad metadata or bad INT8)
    - STT that answers in the wrong script (mislabeled model files)
    - two STT files that are secretly identical (copy-paste model repos)
    - TTS that can't say some characters (they'd be dropped silently)
    - TTS sample rate different from the manifest (plays too fast/slow)

    python verify_models.py --models ./models
    python verify_models.py --models ./models --only hi,ta     # just some languages

Listen to the WAVs it writes in verify_out/ - a low error number doesn't
prove the voice sounds good, only that it's understandable to a machine.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
from common import (  # noqa: E402
    cer, dropped_chars, load_json, make_stt, make_tts, read_char_vocab,
    sanitize, script_share, sha256, transcribe, tts_generate,
)

PASS_CER, WARN_CER = 0.25, 0.50
HERE = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True, help="the models/ folder")
    ap.add_argument("--manifest", default=str(HERE / "manifest.json"))
    ap.add_argument("--alerts", default=str(HERE / "alerts.json"))
    ap.add_argument("--out", default="verify_out")
    ap.add_argument("--only", default="", help="comma-separated language codes")
    args = ap.parse_args()

    models, out = Path(args.models), Path(args.out)
    out.mkdir(exist_ok=True)
    man, alerts = load_json(args.manifest), load_json(args.alerts)["alerts"]
    langs = [c for c in man["languages"] if not args.only or c in args.only.split(",")]
    problems: list[str] = []

    # 1. Every file exists -------------------------------------------------
    print("== files")
    for code in langs:
        L = man["languages"][code]
        eng = man["tts_engines"][L["tts"]["engine"]]
        for key in ("model", "tokens"):
            for p in (L["stt"].get(key), eng.get(key)):
                if p and not (models / p).exists():
                    problems.append(f"{code}: missing {p}")
    if problems:
        print("\n".join("  MISSING " + p for p in problems))
        return 1
    print("  all present")

    # 2. No two STT models are the same file ------------------------------
    print("== STT files are all different")
    seen: dict[str, str] = {}
    for code in langs:
        p = models / man["languages"][code]["stt"]["model"]
        h = sha256(p)
        if h in seen:
            msg = f"{code} and {seen[h]} STT models are IDENTICAL files - one is mislabeled"
            problems.append(msg)
            print("  FAIL", msg)
        seen[h] = code
    print("  ok" if not any("IDENTICAL" in p for p in problems) else "")

    # 3. Round trip per language -----------------------------------------
    tts_cache: dict[str, object] = {}
    report = {}
    for code in langs:
        L = man["languages"][code]
        voice, eng_name = L["tts"], L["tts"]["engine"]
        eng = man["tts_engines"][eng_name]
        print(f"\n== {code} ({L['name']})  TTS={eng_name}  STT={L['stt']['model']}")

        vocab = read_char_vocab(models / eng["tokens"]) if eng["frontend"] == "characters" else None
        if eng_name not in tts_cache:
            tts_cache[eng_name] = make_tts(eng, models)
        tts = tts_cache[eng_name]
        t0 = time.time()
        stt = make_stt(L["stt"], models)
        print(f"  STT loaded in {time.time() - t0:.1f}s")

        rows = []
        for name, a in alerts.items():
            ref = a["texts"].get(code)
            if not ref:
                continue
            if vocab is not None:
                lost = dropped_chars(ref, vocab)
                if lost:
                    problems.append(f"{code}: TTS can't say {sorted(lost)} in '{name}'")
            text = sanitize(ref, vocab)
            samples, sr = tts_generate(tts, text, voice.get("sid", 0), voice.get("speed", 1.0),
                                       voice.get("emotion_id") if eng_name == "rasa" else None)
            if sr != eng["sample_rate"]:
                problems.append(f"{eng_name}: real sample rate {sr}, manifest says {eng['sample_rate']}")
            dur = len(samples) / sr
            rms = float(np.sqrt(np.mean(samples ** 2))) if len(samples) else 0.0
            sf.write(out / f"{code}_{name}.wav", samples, sr)

            t0 = time.time()
            hyp = transcribe(stt, samples, sr)
            rtf = (time.time() - t0) / max(dur, 1e-6)
            e = cer(ref, hyp)
            share = script_share(hyp, L["script"]) if hyp else 0.0
            rows.append({"alert": name, "ref": ref, "hyp": hyp, "cer": round(e, 3),
                         "dur_s": round(dur, 2), "stt_rtf_laptop": round(rtf, 3)})
            flag = "ok" if e <= PASS_CER else ("WARN" if e <= WARN_CER else "FAIL")
            print(f"  [{flag:4}] cer={e:.2f} dur={dur:.1f}s  {name}\n         ref: {ref}\n         got: {hyp}")

            if dur < 0.3 or rms < 1e-3:
                problems.append(f"{code}/{name}: TTS output is silent or too short")
            if not hyp:
                problems.append(f"{code}/{name}: STT returned EMPTY text (check normalize_type / INT8)")
            elif share < 0.8:
                problems.append(f"{code}/{name}: STT answered in the wrong script "
                                f"({share:.0%} {L['script']}) - mislabeled model?")

        avg = float(np.mean([r["cer"] for r in rows])) if rows else 1.0
        status = "PASS" if avg <= PASS_CER else ("WARN" if avg <= WARN_CER else "FAIL")
        if status != "PASS":
            problems.append(f"{code}: average round-trip CER {avg:.2f} ({status}) - listen to verify_out/{code}_*.wav")
        report[code] = {"status": status, "avg_cer": round(avg, 3), "rows": rows}
        del stt

    # 4. Summary ----------------------------------------------------------
    print("\n== summary")
    for code, r in report.items():
        print(f"  {code}  {r['status']:4}  avg CER {r['avg_cer']:.2f}")
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if problems:
        print("\n== problems to fix")
        for p in problems:
            print("  -", p)
    print(f"\nWAVs and report.json are in {out}/")
    return 0 if not problems else 2


if __name__ == "__main__":
    sys.exit(main())
