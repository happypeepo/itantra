#!/usr/bin/env python3
"""
Before/after table for the size-reduction changes, from two phone_bench.py runs on the
same phone with the same clips and settings:

    phone_out_v1_rerun/phone_results.json   models/     (full-precision voices, INT8 STT)
    phone_out_v2/phone_results.json         models_v2/  (FP16 storage, trimmed espeak)

Accuracy of the phone-made speech is judged by the SAME laptop STT (models/ v1) for both,
so only the voice differs. Writes docs/size/phone_v1_vs_v2.json and prints markdown.

    python work/compare_phone.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
from common import cer, load_json, make_stt, transcribe, wer  # noqa: E402

RUNS = {"v1": ROOT / "phone_out_v1_rerun", "v2": ROOT / "phone_out_v2"}
MODELS = {"v1": ROOT / "models", "v2": ROOT / "models_v2"}


def mb(p: Path) -> float:
    return (sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) if p.is_dir() else p.stat().st_size) / 1e6


def main() -> int:
    man = load_json(ROOT / "models/manifest.json")
    R = {k: load_json(v / "phone_results.json") for k, v in RUNS.items()}
    out = {"device": R["v2"]["device"], "vad": {k: R[k]["vad"] for k in R},
           "battery_c": {k: [R[k]["battery_temp_c_start"], R[k].get("battery_temp_c_end")] for k in R}, "languages": {}}
    for lang, L in man["languages"].items():
        eng = L["tts"]["engine"]
        e = man["tts_engines"][eng]
        judge = make_stt(L["stt"], MODELS["v1"])
        row = {"engine": eng}
        for k in ("v1", "v2"):
            P = R[k]["languages"][lang]
            s2, s4 = P["stt"]["int8_t2"], P["stt"]["int8_t4"]
            t2, t4 = P["tts"]["t2"], P["tts"]["t4"]
            refs = [r["text"] for r in t4["rows"]]
            stt_wer = float(np.mean([wer(a, b) for a, b in zip(refs, s2["texts"])]))
            cs, ws = [], []
            for r in t4["rows"]:
                x, sr = sf.read(RUNS[k] / "tts_wav" / f"{lang}_{r['name']}_t4.wav", dtype="float32")
                h = transcribe(judge, x, sr)
                cs.append(cer(r["text"], h)); ws.append(wer(r["text"], h))
            row[k] = {
                "stt_mb": mb(MODELS[k] / L["stt"]["model"]), "tts_mb": mb(MODELS[k] / e["model"]),
                "stt_rtf_t2": s2["rtf"], "stt_rtf_t4": s4["rtf"], "stt_long_s": s2["single_longest"]["decode_s"],
                "stt_rss": s2["max_rss_mb"], "stt_load": s2["load_s_approx"], "stt_cer": s2["cer_avg"], "stt_wer": stt_wer,
                "texts": s2["texts"],
                "tts_rtf_t2": t2["rtf_avg"], "tts_rtf_t4": t4["rtf_avg"], "first_avg_t4": t4["first_audio_avg_s"],
                "first_max_t4": t4["first_audio_max_s"], "tts_rss": max(t2["max_rss_mb"], t4["max_rss_mb"]),
                "tts_load": t4["load_s_approx"], "tts_rt_cer": float(np.mean(cs)), "tts_rt_wer": float(np.mean(ws)),
            }
        row["stt_identical"] = f"{sum(a == b for a, b in zip(row['v1']['texts'], row['v2']['texts']))}/{len(row['v1']['texts'])}"
        for k in ("v1", "v2"):
            del row[k]["texts"]
        out["languages"][lang] = row
    out["size"] = {"models_all_mb": {k: mb(v) for k, v in MODELS.items()}}
    (ROOT / "docs/size/phone_v1_vs_v2.json").write_text(json.dumps(out, ensure_ascii=False, indent=1, default=float), encoding="utf-8")

    f = lambda x, n=3: f"{x:.{n}f}"  # noqa: E731
    print("| Lang | STT size | Voice size | STT transcripts identical | STT CER | STT RTF (2 thr) | STT RAM | Voice RTF (4 thr) | First audio avg (4 thr) | Voice RAM | Phone-voice round-trip CER |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for lang, r in out["languages"].items():
        a, b = r["v1"], r["v2"]
        print(f"| {lang} | {a['stt_mb']:.0f} → **{b['stt_mb']:.0f}** MB | {a['tts_mb']:.0f} → **{b['tts_mb']:.0f}** MB | {r['stt_identical']} | "
              f"{f(a['stt_cer'])} → {f(b['stt_cer'])} | {f(a['stt_rtf_t2'])} → {f(b['stt_rtf_t2'])} | {a['stt_rss']:.0f} → {b['stt_rss']:.0f} MB | "
              f"{f(a['tts_rtf_t4'], 2)} → {f(b['tts_rtf_t4'], 2)} | {f(a['first_avg_t4'], 2)} → {f(b['first_avg_t4'], 2)} s | "
              f"{a['tts_rss']:.0f} → {b['tts_rss']:.0f} MB | {f(a['tts_rt_cer'])} → {f(b['tts_rt_cer'])} |")
    L = out["languages"]
    avg = lambda k, key: float(np.mean([r[k][key] for r in L.values()]))  # noqa: E731
    print("\nmeans (v1 -> v2):")
    for key in ("stt_cer", "stt_wer", "stt_rtf_t2", "stt_rss", "stt_load", "tts_rtf_t4", "first_avg_t4", "first_max_t4", "tts_rss", "tts_load", "tts_rt_cer", "tts_rt_wer"):
        print(f"  {key:14} {avg('v1', key):8.3f} -> {avg('v2', key):8.3f}")
    print("VAD idle CPU %:", {k: v["idle_cpu_percent_one_core"] for k, v in out["vad"].items()}, "| battery C:", out["battery_c"])
    print("all models MB:", {k: round(v) for k, v in out["size"]["models_all_mb"].items()})
    return 0


if __name__ == "__main__":
    sys.exit(main())
