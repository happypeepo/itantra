#!/usr/bin/env python3
"""
Benchmark every model ON THE PHONE, using sherpa-onnx's official Android arm64
command-line tools (v1.13.8, android-aarch64-termux-static) run over adb.

Expects on the phone (setup: README.md, "Phone benchmark"):
    /data/local/tmp/itantra/bin/      sherpa-onnx tools + libc++_shared.so (from the NDK)
    /data/local/tmp/itantra/models/   the models/ folder
    /data/local/tmp/itantra/int4/     INT4 STT copies (hi, ta)
    /data/local/tmp/itantra/audio/    bench_out/tts/<lang>_<engine>/*.wav (made by p2-models/scripts/benchmark.py run)

Measures:
    STT  per language: batch RTF (6 clips), single-utterance decode time on the longest clip,
         peak RSS, load time; INT8 at 2 and 4 threads; INT4 for hi and ta
    TTS  per language: 6 texts, generation time (= time to first audio, one sentence each), RTF, peak RSS
    VAD  CPU seconds per second of audio while listening to quiet noise

    python work/phone_bench.py --threads 2
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
from common import cer, load_json, read_char_vocab, sanitize  # noqa: E402

ADB = [os.environ.get("ADB") or shutil.which("adb") or "/Users/bhoumiksangle/Downloads/platform-tools/adb"] + (
    ["-s", os.environ["SERIAL"]] if os.environ.get("SERIAL") else [])
P = "/data/local/tmp/itantra"


def sh(cmd: str, timeout: int = 900) -> str:
    full = f"cd {P} && export LD_LIBRARY_PATH={P}/bin && {cmd}"
    r = subprocess.run([*ADB, "shell", full], capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def timev(out: str) -> dict:
    g = lambda k: re.search(rf"{k}:\s*([\d.]+)", out)  # noqa: E731
    return {"real_s": float(g(r"Real time \(s\)").group(1)), "user_s": float(g(r"User time \(s\)").group(1)),
            "sys_s": float(g(r"System time \(s\)").group(1)), "max_rss_mb": round(int(g(r"Max RSS \(KiB\)").group(1)) / 1024, 1)}


def battery_temp_c() -> float:
    r = subprocess.run([*ADB, "shell", "dumpsys battery"], capture_output=True, text=True).stdout
    m = re.search(r"temperature:\s*(\d+)", r)
    return int(m.group(1)) / 10 if m else float("nan")


def device_info() -> dict:
    q = lambda p: subprocess.run([*ADB, "shell", f"getprop {p}"], capture_output=True, text=True).stdout.strip()  # noqa: E731
    mem = subprocess.run([*ADB, "shell", "grep MemTotal /proc/meminfo"], capture_output=True, text=True).stdout.split()
    return {"brand": q("ro.product.brand"), "model": q("ro.product.model"), "soc": f"{q('ro.soc.manufacturer')} {q('ro.soc.model')}",
            "android": q("ro.build.version.release"), "abi": q("ro.product.cpu.abi"),
            "ram_gb": round(int(mem[1]) / 1024 / 1024, 1), "sherpa_onnx": sh("./bin/sherpa-onnx-version").strip().replace("\n", " | ")}


def stt_run(model: str, tokens: str, wavs: list[str], threads: int) -> dict:
    out = sh(f"toybox time -v ./bin/sherpa-onnx-offline --nemo-ctc-model={model} --tokens={tokens} "
             f"--num-threads={threads} {' '.join(wavs)} 2>&1")
    el = re.search(r"Elapsed seconds:\s*([\d.]+)", out)
    rtf = re.search(r"Real time factor \(RTF\):\s*([\d.]+)\s*/\s*([\d.]+)\s*=\s*([\d.]+)", out)
    texts = [json.loads(l)["text"] for l in out.splitlines() if l.startswith('{"lang"')]
    if not (el and rtf) or len(texts) != len(wavs):
        raise RuntimeError(f"STT failed for {model}:\n{out[-1500:]}")
    t = timev(out)
    return {"threads": threads, "decode_s": float(el.group(1)), "audio_s": float(rtf.group(2)), "rtf": float(rtf.group(3)),
            "load_s_approx": round(t["real_s"] - float(el.group(1)), 2), "max_rss_mb": t["max_rss_mb"],
            "cpu_s": round(t["user_s"] + t["sys_s"], 2), "wall_s": t["real_s"], "texts": texts}


def tts_run(eng: dict, text: str, sid: int, emotion_id, threads: int, out_wav: str, mdir: str = "models") -> dict:
    args = [f"--vits-model={mdir}/{eng['model']}", f"--vits-tokens={mdir}/{eng['tokens']}", f"--sid={sid}",
            f"--num-threads={threads}", f"--output-filename={out_wav}"]
    if eng.get("data_dir"):
        args.append(f"--vits-data-dir={mdir}/{eng['data_dir']}")
    if emotion_id is not None:
        args.append(f"--emotion-id={int(emotion_id)}")
    out = sh(f"toybox time -v ./bin/sherpa-onnx-offline-tts {' '.join(args)} {shlex.quote(text)} 2>&1")
    m = re.search(r"Real-time factor \(RTF\):\s*([\d.]+)\s*/\s*([\d.]+)\s*=\s*([\d.]+)", out)
    if not m:
        raise RuntimeError(f"TTS failed:\n{out[-1500:]}")
    t = timev(out)
    return {"gen_s": float(m.group(1)), "audio_s": float(m.group(2)), "rtf": float(m.group(3)),
            "load_s_approx": round(t["real_s"] - float(m.group(1)), 2), "max_rss_mb": t["max_rss_mb"],
            "cpu_s": round(t["user_s"] + t["sys_s"], 2)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--only", default="")
    ap.add_argument("--tts-threads", default="2,4", help="comma-separated thread counts for TTS")
    ap.add_argument("--out", default=str(ROOT / "phone_out"))
    ap.add_argument("--phone-models", default="models", help="model folder on the phone, relative to /data/local/tmp/itantra")
    ap.add_argument("--no-int4", action="store_true", help="skip the INT4 STT variant")
    a = ap.parse_args()
    out = Path(a.out)
    (out / "tts_wav").mkdir(parents=True, exist_ok=True)
    man = load_json(ROOT / "models/manifest.json")
    res = {"device": device_info(), "threads": a.threads, "phone_models": a.phone_models, "started": time.strftime("%Y-%m-%d %H:%M:%S"),
           "battery_temp_c_start": battery_temp_c(), "languages": {}}
    print(json.dumps(res["device"], ensure_ascii=False), flush=True)

    # VAD idle cost: CPU for 65 s of quiet noise minus CPU for 5 s (removes load/startup)
    rng = np.random.default_rng(0)
    for secs in (5, 65):
        p = out / f"noise_{secs}s.wav"
        sf.write(p, (rng.standard_normal(16000 * secs) * 0.003).astype(np.float32), 16000)
        subprocess.run([*ADB, "push", str(p), f"{P}/audio/"], capture_output=True)
    cpu = {}
    for secs in (5, 65):
        t = timev(sh(f"toybox time -v ./bin/sherpa-onnx-vad --silero-vad-model={a.phone_models}/vad/silero_vad.onnx "
                     f"audio/noise_{secs}s.wav out/vad_{secs}.wav 2>&1"))
        cpu[secs] = t["user_s"] + t["sys_s"]
    res["vad"] = {"cpu_s_per_60s": round(cpu[65] - cpu[5], 3),
                  "idle_cpu_percent_one_core": round(100 * (cpu[65] - cpu[5]) / 60, 2),
                  "note": "file processed faster than real time; CPU per second of audio is what a live mic would cost"}
    print("VAD", res["vad"], flush=True)

    langs = [l for l in man["languages"] if not a.only or l in a.only.split(",")]
    for lang in langs:
        L = man["languages"][lang]
        eng_name = L["tts"]["engine"]
        eng = man["tts_engines"][eng_name]
        clip_dir = ROOT / "bench_out/tts" / f"{lang}_{eng_name}"
        rows = json.loads((clip_dir / "result.json").read_text(encoding="utf-8"))["rows"]
        refs = [r["text"] for r in rows]
        wavs = [f"audio/{lang}_{eng_name}/{Path(r['wav']).name}" for r in rows]
        longest = wavs[int(np.argmax([r["dur_s"] for r in rows]))]
        entry: dict = {"name": L["name"], "stt": {}, "tts": {}}

        variants = {"int8": (L["stt"]["model"], L["stt"]["tokens"])}
        if lang in ("hi", "ta") and not a.no_int4:
            variants["int4"] = (f"../int4/{lang}/model.int4.onnx", f"../int4/{lang}/tokens.txt")
        for vname, (mo, to) in variants.items():
            for th in sorted({a.threads, 4}):
                r = stt_run(f"{a.phone_models}/{mo}", f"{a.phone_models}/{to}", wavs, th)
                r["cer_each"] = [round(cer(ref, hyp), 3) for ref, hyp in zip(refs, r["texts"])]
                r["cer_avg"] = round(float(np.mean(r["cer_each"])), 3)
                single = stt_run(f"{a.phone_models}/{mo}", f"{a.phone_models}/{to}", [longest], th)
                r["single_longest"] = {k: single[k] for k in ("decode_s", "audio_s", "rtf")}
                entry["stt"][f"{vname}_t{th}"] = r
                print(f"{lang} STT {vname} t{th}: RTF {r['rtf']:.3f}  longest {single['audio_s']:.1f}s -> "
                      f"{single['decode_s']:.2f}s  RSS {r['max_rss_mb']:.0f} MB  load ~{r['load_s_approx']:.1f}s  "
                      f"CER {r['cer_avg']:.3f}", flush=True)

        vocab = read_char_vocab(ROOT / "models" / eng["tokens"]) if eng["frontend"] == "characters" else None
        emo = L["tts"].get("emotion_id") if eng_name == "rasa" else None
        entry["tts"] = {"engine": eng_name, "emotion_id": emo}
        for th in [int(x) for x in a.tts_threads.split(",")]:
            trows = []
            for r, ref in zip(rows, refs):
                name = Path(r["wav"]).stem
                pw = f"out/tts_{lang}_{name}_t{th}.wav"
                t = tts_run(eng, sanitize(ref, vocab), L["tts"].get("sid", 0), emo, th, pw, a.phone_models)
                subprocess.run([*ADB, "pull", f"{P}/{pw}", str(out / "tts_wav" / f"{lang}_{name}_t{th}.wav")], capture_output=True)
                trows.append({"name": name, "text": ref, **t})
            tt = {"threads": th, "rows": trows,
                  "rtf_avg": round(float(np.mean([x["rtf"] for x in trows])), 3),
                  "first_audio_avg_s": round(float(np.mean([x["gen_s"] for x in trows])), 3),
                  "first_audio_max_s": round(float(np.max([x["gen_s"] for x in trows])), 3),
                  "max_rss_mb": max(x["max_rss_mb"] for x in trows),
                  "load_s_approx": round(float(np.median([x["load_s_approx"] for x in trows])), 2)}
            entry["tts"][f"t{th}"] = tt
            print(f"{lang} TTS {eng_name} t{th}: RTF {tt['rtf_avg']:.3f}  first audio avg {tt['first_audio_avg_s']:.2f}s "
                  f"max {tt['first_audio_max_s']:.2f}s  RSS {tt['max_rss_mb']:.0f} MB  load ~{tt['load_s_approx']:.1f}s", flush=True)
        res["languages"][lang] = entry
        (out / "phone_results.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")

    res["battery_temp_c_end"] = battery_temp_c()
    res["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    (out / "phone_results.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"battery temp {res['battery_temp_c_start']} -> {res['battery_temp_c_end']} C\nwrote {out / 'phone_results.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
