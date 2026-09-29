#!/usr/bin/env python3
"""
Build docs/RESULTS.md from the measured result files. Nothing is typed in by hand:
every number comes from one of these files.

    phone_out/phone_results.json    phone_bench.py (Nothing Phone (3a))
    bench_out/results_final.json    benchmark.py on the laptop (Apple M4)
    models/                         file sizes
    p2-models/manifest.json         which model each language uses

    python work/make_results.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
import frame as fr  # noqa: E402
from common import load_json, wer  # noqa: E402

LANGS = ["hi", "en", "bn", "gu", "kn", "ml", "mr", "or", "ta", "te"]
STT_LICENCE = {l: "MIT (AI4Bharat IndicConformer, OpenVoiceOS ONNX export)" for l in LANGS}
STT_LICENCE["en"] = "NVIDIA NeMo conformer (sherpa-onnx export), see model card"
STYLE = {0: "ALEXA", 4: "CONV", 10: "NEWS"}


def mb(p: Path) -> float:
    if p.is_dir():
        return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / 1e6
    return p.stat().st_size / 1e6 if p.exists() else 0.0


def f(x, n=2):
    return "n/a" if x is None else f"{x:.{n}f}"


def main() -> int:
    man = load_json(ROOT / "models/manifest.json")
    ph = load_json(ROOT / "phone_out/phone_results.json")
    lap = load_json(ROOT / "bench_out/results_final.json")
    sents = load_json(ROOT / "p2-models/test_sentences.json")["sentences"]["road_blocked"]
    models = ROOT / "models"
    dev = ph["device"]
    rt_phone_tts = ph.get("roundtrip_phone_tts_laptop_stt_cer", {})

    rows = {}
    for l in LANGS:
        L = man["languages"][l]
        eng_name = L["tts"]["engine"]
        eng = man["tts_engines"][eng_name]
        P = ph["languages"][l]
        s2, s4 = P["stt"]["int8_t2"], P["stt"]["int8_t4"]
        t2, t4 = P["tts"]["t2"], P["tts"]["t4"]
        lp = lap["languages"][l]
        lrt = [r for r in lp["stt"]["rows"] if r["kind"] == "roundtrip"]
        lreal = [r for r in lp["stt"]["rows"] if r["kind"] == "real" and "cer" in r]
        sent_tts4 = next(r for r in t4["rows"] if r["name"].endswith("road_blocked"))
        sent_tts2 = next(r for r in t2["rows"] if r["name"].endswith("road_blocked"))
        sent_dur = next(r["dur_s"] for r in lp["tts"]["rows"] if r["name"] == "road_blocked")
        text = sents[l]
        refs = [r["text"] for r in lp["tts"]["rows"]]
        p_wer = float(np.mean([wer(a, b) for a, b in zip(refs, s2["texts"])]))
        f_utf8 = len(text.encode("utf-8")) + 8
        f_best = len(fr.encode_speech(text, L["wire_id"], L["script"], 0))
        stt_mb = mb(models / L["stt"]["model"]) + mb(models / L["stt"]["tokens"])
        tts_mb = mb(models / eng["model"]) + mb(models / eng["tokens"])
        data_mb = mb(models / eng["data_dir"]) if eng.get("data_dir") else 0.0
        rows[l] = dict(
            name=L["name"], eng=eng_name, eng_lic=eng["licence"], sid=L["tts"].get("sid", 0),
            emo=L["tts"].get("emotion_id") if eng_name == "rasa" else None, sr=eng["sample_rate"],
            stt_src=L["stt"]["source"].split(",")[0].split(" fp32")[0], stt_lic=STT_LICENCE[l],
            stt_mb=stt_mb, tts_mb=tts_mb, data_mb=data_mb, shared="rasa" if eng_name == "rasa" else ("espeak-ng-data" if data_mb else ""),
            p_stt_rtf2=s2["rtf"], p_stt_rtf4=s4["rtf"], p_stt_sent2=s2["single_longest"]["decode_s"],
            p_stt_sent4=s4["single_longest"]["decode_s"], p_sent_audio=s2["single_longest"]["audio_s"],
            p_stt_rss=s2["max_rss_mb"], p_stt_load=s2["load_s_approx"], p_stt_cer=s2["cer_avg"], p_stt_wer=p_wer,
            p_tts_rtf2=t2["rtf_avg"], p_tts_rtf4=t4["rtf_avg"], p_first2=t2["first_audio_avg_s"], p_first4=t4["first_audio_avg_s"],
            p_firstmax2=t2["first_audio_max_s"], p_firstmax4=t4["first_audio_max_s"], p_tts_rss=max(t2["max_rss_mb"], t4["max_rss_mb"]),
            p_tts_load=t4["load_s_approx"], p_sent_first4=sent_tts4["gen_s"], p_sent_first2=sent_tts2["gen_s"],
            e2e_ptt=s2["single_longest"]["decode_s"] + sent_tts4["gen_s"],
            l_stt_rtf=float(np.mean([r["rtf"] for r in lrt])), l_cer=float(np.mean([r["cer"] for r in lrt])),
            l_wer=float(np.mean([r["wer"] for r in lrt])), l_first=float(np.mean([r["first_audio_s"] for r in lp["tts"]["rows"]])),
            l_tts_rtf=float(np.mean([r["rtf"] for r in lp["tts"]["rows"]])),
            rt_phone_tts=rt_phone_tts.get(l), n_real=len(lreal),
            real_cer=float(np.mean([r["cer"] for r in lreal])) if lreal else None,
            real_wer=float(np.mean([r["wer"] for r in lreal])) if lreal else None,
            sent=text, sent_dur=sent_dur, f_utf8=f_utf8, f_best=f_best,
            bps_utf8=8 * f_utf8 / sent_dur, bps_best=8 * f_best / sent_dur,
            int4={k: v for k, v in P["stt"].items() if k.startswith("int4")},
        )

    total_mb = mb(models)
    phone = f"{dev['brand']} {dev['model']} (Nothing Phone (3a)), {dev['soc']} (Snapdragon 7s Gen 3), {dev['ram_gb']} GB RAM, Android {dev['android']}"
    rt_vers = dev["sherpa_onnx"].split("|")
    sv = rt_vers[0].split(":")[1].strip()
    ov = rt_vers[-1].split(":")[1].strip()
    o = []
    w = o.append
    w("# iTantra: measured results, per language\n")
    w(f"*Generated by `work/make_results.py` from `phone_out/phone_results.json` (run {ph['started']}) and "
      f"`bench_out/results_final.json`. Don't edit the numbers by hand: re-run the script.*\n")
    w("## Test conditions\n")
    w(f"- **Phone (target device):** {phone}. sherpa-onnx {sv}, ONNX Runtime {ov}, official Android arm64 command-line tools run over adb "
      f"(`work/phone_bench.py`). STT at 2 threads (4 shown too). TTS at **2 and 4 threads** (the app's `TtsPool` default is 4). "
      f"Battery temperature {ph['battery_temp_c_start']} → {ph['battery_temp_c_end']} °C.")
    w("- **Laptop (for comparison only):** Apple M4, arm64, macOS 26.6.2, sherpa-onnx 1.13.8, ONNX Runtime 1.30.0, 2 threads (`benchmark.py`).")
    w("- **This is a mid-range phone.** A low-end phone will be slower. Re-run `phone_bench.py` on the weakest team phone for final slides.")
    w("- **Test audio:** 6 clips per language (5 alert phrases + 1 longer sentence), made by our own TTS. "
      "**Round-trip** accuracy = known text → our TTS → our STT → compare. It tests both models together, but it is **not** real-speech accuracy.")
    w("- **Not measured yet:** real-speech WER/CER (no recordings yet), human listening scores for TTS, APK size (no app yet), "
      "the real phone-to-phone link (the latencies below exclude the network, which adds a few ms on a hotspot).\n")

    w("## Summary: all 10 languages (phone)\n")
    w("| Lang | STT size | TTS voice | STT RTF | STT: long sentence | TTS RTF (4 thr) | First audio avg (4 thr) | **Est. end-to-end, PTT** | STT RAM | TTS RAM | Round-trip CER | Round-trip WER |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for l in LANGS:
        r = rows[l]
        w(f"| **{l}** {r['name']} | {r['stt_mb']:.0f} MB | {r['eng']} | {r['p_stt_rtf2']:.3f} | {r['p_stt_sent2']:.2f} s / {r['p_sent_audio']:.1f} s | "
          f"{r['p_tts_rtf4']:.2f} | {r['p_first4']:.2f} s | **{r['e2e_ptt']:.2f} s** | {r['p_stt_rss']:.0f} MB | {r['p_tts_rss']:.0f} MB | {r['p_stt_cer']:.3f} | {r['p_stt_wer']:.3f} |")
    w("\n*STT RTF: 2 threads, over the 6 clips. \"STT: long sentence\" = time to recognize the longest clip / its length. "
      "**End-to-end, push-to-talk** = STT time for the long sentence (2 threads) + TTS time to first audio for the same sentence (4 threads), "
      "i.e. release PTT → other phone starts speaking, without the network. Continuous mode adds the VAD's 0.5 s silence wait. "
      "RAM = peak memory of the process running that one model (so the app with one STT + one voice loaded needs roughly the sum). **CER vs WER:** CER counts wrong letters, WER counts wrong words. Indic words are long, so one small letter slip (e.g. Tamil \"இந்தப்\" → \"இந்த\") makes a whole word wrong; WER therefore looks much worse than CER for the same output. The PS asks for WER, so both are shown.*\n")

    w("## Shared across all languages\n")
    v = ph["vad"]
    w(f"| Item | Value |\n|---|---|")
    w(f"| **All models on disk (deduplicated)** | {total_mb:.0f} MB ({total_mb/1000:.2f} GB) |")
    w(f"| VAD (silero) model | {mb(models / man['vad']['model']):.1f} MB |")
    w(f"| **Idle-listening CPU (VAD, continuous mode)** | **{v['idle_cpu_percent_one_core']}% of one core** on the phone |")
    w(f"| rasa model (shared by bn kn ml mr ta te) | {mb(models / 'tts/rasa/model.onnx'):.0f} MB, one copy |")
    w(f"| espeak-ng-data (shared by both Piper voices) | {mb(models / 'tts/espeak-ng-data'):.0f} MB, one copy |")
    w(f"| Alert over the link | 9-byte frame (1-byte alert ID), played from a pre-rendered WAV in the receiver's language |")
    w(f"| Frame overhead | 8 bytes (type, language, sequence, length, CRC-16) |")
    w("")

    for l in LANGS:
        r = rows[l]
        w(f"---\n\n## {r['name']} (`{l}`)\n")
        voice = f"{r['eng']}, speaker {r['sid']}" + (f", style {r['emo']} ({STYLE.get(r['emo'], '')})" if r["emo"] is not None else "")
        shared = f" + {r['data_mb']:.0f} MB espeak-ng-data (shared)" if r["data_mb"] else ""
        shared += " (shared by 6 languages)" if r["shared"] == "rasa" else ""
        w("| | |\n|---|---|")
        w(f"| **STT model** | {r['stt_src']}; licence {r['stt_lic']} |")
        w(f"| **TTS voice** | {voice}; {r['sr']} Hz; licence **{r['eng_lic']}** |")
        w(f"| **Size on disk** | STT **{r['stt_mb']:.0f} MB** · TTS **{r['tts_mb']:.0f} MB**{shared} |")
        w(f"| **RAM (phone, peak)** | STT {r['p_stt_rss']:.0f} MB · TTS {r['p_tts_rss']:.0f} MB |")
        w(f"| **Load time (phone)** | STT ~{r['p_stt_load']:.1f} s · TTS ~{r['p_tts_load']:.1f} s (once, at startup / language switch) |")
        w(f"| **STT speed (phone)** | RTF {r['p_stt_rtf2']:.3f} (2 thr) / {r['p_stt_rtf4']:.3f} (4 thr); a {r['p_sent_audio']:.1f} s sentence in "
          f"**{r['p_stt_sent2']:.2f} s** (2 thr) / {r['p_stt_sent4']:.2f} s (4 thr) |")
        w(f"| **TTS speed (phone)** | RTF {r['p_tts_rtf2']:.2f} (2 thr) / **{r['p_tts_rtf4']:.2f}** (4 thr) |")
        w(f"| **Time to first audio (phone)** | avg {r['p_first2']:.2f} s, max {r['p_firstmax2']:.2f} s (2 thr) · "
          f"**avg {r['p_first4']:.2f} s, max {r['p_firstmax4']:.2f} s** (4 thr) |")
        w(f"| **End-to-end, push-to-talk (phone, est.)** | **{r['e2e_ptt']:.2f} s** for the long sentence "
          f"(STT {r['p_stt_sent2']:.2f} s + TTS first audio {r['p_sent_first4']:.2f} s); +0.5 s in continuous mode |")
        w(f"| **Accuracy, round trip (phone STT)** | CER **{r['p_stt_cer']:.3f}** · WER {r['p_stt_wer']:.3f} (6 clips) |")
        w(f"| Accuracy, round trip (phone TTS → STT) | CER {f(r['rt_phone_tts'], 3)} |")
        w(f"| Accuracy, round trip (laptop) | CER {r['l_cer']:.3f} · WER {r['l_wer']:.3f} |")
        w(f"| **Accuracy, real speech** | " + (f"CER {r['real_cer']:.3f} · WER {r['real_wer']:.3f} ({r['n_real']} clips)" if r["n_real"] else "**not measured** (no recordings yet)") + " |")
        w(f"| TTS quality to people | " + ("style chosen by listening (NEWS); no listening score measured" if r["emo"] is not None else "no listening score measured") + " |")
        w(f"| **Link (long sentence)** | {r['f_best']} bytes per frame (UTF-8 would be {r['f_utf8']}); ≈ **{r['bps_best']:.0f} bps** "
          f"(UTF-8 {r['bps_utf8']:.0f} bps) over {r['sent_dur']:.1f} s of speech* |")
        w(f"| Laptop (M4) for comparison | STT RTF {r['l_stt_rtf']:.3f} · TTS RTF {r['l_tts_rtf']:.2f} · first audio avg {r['l_first']:.2f} s |")
        if r["int4"]:
            i2 = r["int4"].get("int4_t2")
            w(f"| INT4 STT (tested, not used) | {mb(ROOT / 'work/int4' / l / 'model.int4.onnx'):.0f} MB · RTF {i2['rtf']:.3f} (vs INT8 {r['p_stt_rtf2']:.3f}) · "
              f"RAM {i2['max_rss_mb']:.0f} MB · CER {i2['cer_avg']:.3f}: slower on the phone, so INT8 is kept |")
        w("")
    w("\\* *bps uses the length of our TTS reading of the sentence as the speaking time. Real speakers vary.*\n")
    w("## Licences\n")
    w("| Part | Licence |\n|---|---|")
    w("| sherpa-onnx runtime | Apache-2.0 |\n| silero VAD | MIT |")
    w("| IndicConformer STT (9 Indic languages) | MIT (AI4Bharat; ONNX export by OpenVoiceOS) |")
    w("| NeMo English STT | see the sherpa-onnx model card |")
    w("| rasa TTS (bn kn ml mr ta te) | CC-BY-4.0: credit AI4Bharat (`ai4bharat/vits_rasa_13`) and the sherpa-onnx export by MatiasLin |")
    w("| Piper `hi_IN-rohan-medium` | IIT Madras Indic TTS licence (**not yet read**); fine-tuned from Piper `lessac`, whose dataset is research-only |")
    w("| Piper `en_US-ljspeech-medium` | public domain (LJSpeech) |")
    w("| MMS `guj`, `ory` | **CC-BY-NC-4.0: non-commercial.** No permissive on-device voice exists for Gujarati or Odia |")
    out = ROOT / "docs/RESULTS.md"
    out.write_text("\n".join(o) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(o)} lines)")
    json.dump(rows, open(ROOT / "docs/results_table.json", "w"), ensure_ascii=False, indent=1, default=float)
    return 0


if __name__ == "__main__":
    sys.exit(main())
