#!/usr/bin/env python3
"""
T4: rasa for bn kn ml mr ta te, styles 0 (ALEXA), 4 (CONV), 10 (NEWS),
compared with MMS on the same machine for kn mr ta te.

For every voice x text: TTS (timed) -> WAV -> STT (manifest model) -> CER.
Texts = the 5 alert phrases + the test sentence. WAVs go to t4_out/ for listening.

    python work/t4_rasa.py --threads 2
"""
from __future__ import annotations

import argparse
import json
import platform
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
from common import cer, load_json, make_stt, make_tts, read_char_vocab, sanitize, transcribe  # noqa: E402

LANGS = ["bn", "kn", "ml", "mr", "ta", "te"]
STYLES = {0: "ALEXA", 4: "CONV", 10: "NEWS"}
MMS = {"kn": "kan", "mr": "mar", "ta": "tam", "te": "tel"}


def timed_generate(tts, text, sid, speed, emotion_id):
    import sherpa_onnx
    g = sherpa_onnx.GenerationConfig()
    g.sid, g.speed = int(sid), float(speed)
    if emotion_id is not None:
        g.extra = {"emotion_id": str(int(emotion_id))}
    first = {}
    t0 = time.perf_counter()

    def cb(samples, progress):
        first.setdefault("t", time.perf_counter() - t0)
        return 1

    a = tts.generate(text, g, cb)
    total = time.perf_counter() - t0
    x = np.asarray(a.samples, dtype=np.float32)
    return x, int(a.sample_rate), first.get("t", total), total


def run_voice(tts, vocab, texts, sid, emotion_id, rec, out_dir: Path, code: str):
    out_dir.mkdir(parents=True, exist_ok=True)
    timed_generate(tts, sanitize(texts[0][1], vocab), sid, 1.0, emotion_id)  # warm-up
    rows = []
    for name, ref in texts:
        x, sr, first, total = timed_generate(tts, sanitize(ref, vocab), sid, 1.0, emotion_id)
        dur = len(x) / sr
        sf.write(out_dir / f"{code}_{name}.wav", x, sr)
        hyp = transcribe(rec, x, sr)
        rows.append({"name": name, "ref": ref, "hyp": hyp, "cer": round(cer(ref, hyp), 3), "sr": sr,
                     "dur_s": round(dur, 3), "first_audio_s": round(first, 3), "gen_s": round(total, 3),
                     "rtf": round(total / max(dur, 1e-6), 3)})
    return rows


def summary(rows):
    alerts = [r for r in rows if r["name"] != "road_blocked"]
    sent = [r for r in rows if r["name"] == "road_blocked"]
    return {"cer_alerts": round(float(np.mean([r["cer"] for r in alerts])), 3),
            "cer_sentence": sent[0]["cer"] if sent else None,
            "rtf_avg": round(float(np.mean([r["rtf"] for r in rows])), 3),
            "first_audio_avg_s": round(float(np.mean([r["first_audio_s"] for r in rows])), 3),
            "first_audio_max_s": round(float(np.max([r["first_audio_s"] for r in rows])), 3),
            "sentence_dur_s": sent[0]["dur_s"] if sent else None}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out", default=str(ROOT / "t4_out"))
    a = ap.parse_args()
    models, out = ROOT / "models", Path(a.out)
    man = load_json(models / "manifest.json")
    alerts = load_json(ROOT / "p2-models/alerts.json")["alerts"]
    sents = load_json(ROOT / "p2-models/test_sentences.json")["sentences"]

    cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
    import sherpa_onnx
    import onnxruntime
    result = {"machine": f"{cpu}, {platform.machine()}, macOS {platform.mac_ver()[0]}", "threads": a.threads,
              "sherpa_onnx": sherpa_onnx.__version__, "onnxruntime": onnxruntime.__version__,
              "rasa": {}, "mms": {}}
    print(result["machine"], "| threads", a.threads, flush=True)

    rasa_eng = man["tts_engines"]["rasa"]
    rasa = make_tts(rasa_eng, models, a.threads)
    rasa_vocab = read_char_vocab(models / rasa_eng["tokens"])

    for code in LANGS:
        L = man["languages"][code]
        texts = [(n, x["texts"][code]) for n, x in alerts.items()] + [(n, x[code]) for n, x in sents.items()]
        rec = make_stt(L["stt"], models, a.threads)
        transcribe(rec, np.zeros(16000, np.float32), 16000)  # warm-up
        sid = L["tts"]["sid"]
        for style, sname in STYLES.items():
            rows = run_voice(rasa, rasa_vocab, texts, sid, style, rec, out / f"rasa_style{style}_{sname}", code)
            s = summary(rows)
            result["rasa"].setdefault(code, {})[str(style)] = {"sid": sid, **s, "rows": rows}
            print(f"rasa {code} sid={sid:2} style={style:2} {sname:5}  CER alerts {s['cer_alerts']:.3f} "
                  f"sentence {s['cer_sentence']:.3f}  RTF {s['rtf_avg']:.3f}  first audio avg "
                  f"{s['first_audio_avg_s']:.2f}s max {s['first_audio_max_s']:.2f}s", flush=True)
        if code in MMS:
            d = ROOT / "dl/mms" / MMS[code]
            eng = {"model": str(d / "model.onnx"), "tokens": str(d / "tokens.txt"), "data_dir": ""}
            mms = make_tts(eng, Path("/"), a.threads)
            rows = run_voice(mms, read_char_vocab(d / "tokens.txt"), texts, 0, None, rec, out / "mms", code)
            s = summary(rows)
            result["mms"][code] = {**s, "rows": rows}
            print(f"MMS  {code}                     CER alerts {s['cer_alerts']:.3f} sentence "
                  f"{s['cer_sentence']:.3f}  RTF {s['rtf_avg']:.3f}  first audio avg "
                  f"{s['first_audio_avg_s']:.2f}s max {s['first_audio_max_s']:.2f}s", flush=True)
            del mms
        del rec

    (out / "t4_results.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nwrote {out / 't4_results.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
