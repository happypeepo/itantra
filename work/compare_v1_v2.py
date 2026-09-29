#!/usr/bin/env python3
"""
Accuracy check, full-size set (v1) vs FP16-storage set (v2, now models/), all 10 languages, laptop.

A. STT: both versions transcribe the SAME clips (bench_out/tts/<lang>_<engine>/*.wav,
   6 per language). Reports how many transcripts are identical, CER/WER for both, RTF.
B. TTS: each voice speaks the 6 texts 3 times (VITS is random run to run), and the
   v1 STT transcribes them, so only the voice changes. Reports mean CER/WER and RTF.

    python work/compare_v1_v2.py --threads 2
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
from common import cer, load_json, make_stt, make_tts, read_char_vocab, sanitize, transcribe, tts_generate, wer  # noqa: E402

V1, V2 = ROOT / "dl/models_v1_full", ROOT / "models"  # v1 (full size) was removed 2026-09-30; rebuild to re-run


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--runs", type=int, default=3)
    a = ap.parse_args()
    man = load_json(V1 / "manifest.json")
    res = {"machine": "Apple M4, onnxruntime 1.30.0", "threads": a.threads, "stt": {}, "tts": {}}
    for lang, L in man["languages"].items():
        eng_name = L["tts"]["engine"]
        rows = json.loads((ROOT / "bench_out/tts" / f"{lang}_{eng_name}" / "result.json").read_text(encoding="utf-8"))["rows"]
        clips = [(sf.read(ROOT / "bench_out/tts" / f"{lang}_{eng_name}" / Path(r["wav"]).name, dtype="float32"), r["text"]) for r in rows]
        # A. STT
        out = {}
        hyps = {}
        for tag, M in (("v1", V1), ("v2", V2)):
            rec = make_stt(L["stt"], M, a.threads)
            transcribe(rec, np.zeros(16000, np.float32), 16000)
            hs, rtf = [], []
            for (x, sr), ref in clips:
                t0 = time.perf_counter(); h = transcribe(rec, x, sr); rtf.append((time.perf_counter() - t0) / (len(x) / sr)); hs.append(h)
            hyps[tag] = hs
            refs = [ref for _, ref in clips]
            out[tag] = {"cer": round(float(np.mean([cer(r, h) for r, h in zip(refs, hs)])), 4),
                        "wer": round(float(np.mean([wer(r, h) for r, h in zip(refs, hs)])), 4),
                        "rtf": round(float(np.mean(rtf)), 4)}
            del rec
        out["identical_transcripts"] = f"{sum(h1 == h2 for h1, h2 in zip(hyps['v1'], hyps['v2']))}/{len(clips)}"
        res["stt"][lang] = out
        print(f"STT {lang}: identical {out['identical_transcripts']}  CER v1 {out['v1']['cer']:.3f} v2 {out['v2']['cer']:.3f}  "
              f"RTF v1 {out['v1']['rtf']:.3f} v2 {out['v2']['rtf']:.3f}", flush=True)
        # B. TTS (the v1 STT judges both voices)
        eng = man["tts_engines"][eng_name]
        vocab = read_char_vocab(V1 / eng["tokens"]) if eng["frontend"] == "characters" else None
        emo = L["tts"].get("emotion_id") if eng_name == "rasa" else None
        texts = [ref for _, ref in clips]
        judge = make_stt(L["stt"], V1, a.threads)
        tout = {}
        for tag, M in (("v1", V1), ("v2", V2)):
            tts = make_tts(eng, M, a.threads)
            tts_generate(tts, sanitize(texts[0], vocab), L["tts"].get("sid", 0), 1.0, emo)
            cs, ws, rtf = [], [], []
            for _ in range(a.runs):
                for t in texts:
                    t0 = time.perf_counter()
                    x, sr = tts_generate(tts, sanitize(t, vocab), L["tts"].get("sid", 0), 1.0, emo)
                    rtf.append((time.perf_counter() - t0) / (len(x) / sr))
                    h = transcribe(judge, x, sr); cs.append(cer(t, h)); ws.append(wer(t, h))
            tout[tag] = {"cer": round(float(np.mean(cs)), 4), "wer": round(float(np.mean(ws)), 4), "rtf": round(float(np.mean(rtf)), 4), "n": len(cs)}
            del tts
        res["tts"][lang] = {"engine": eng_name, **tout}
        print(f"TTS {lang} ({eng_name}): CER v1 {tout['v1']['cer']:.3f} v2 {tout['v2']['cer']:.3f}  "
              f"RTF v1 {tout['v1']['rtf']:.3f} v2 {tout['v2']['rtf']:.3f}  (n={tout['v1']['n']})", flush=True)
    (ROOT / "docs/size").mkdir(parents=True, exist_ok=True)
    (ROOT / "docs/size/compare_v1_v2_laptop.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote docs/size/compare_v1_v2_laptop.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
