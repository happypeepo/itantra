#!/usr/bin/env python3
"""
Measure every model the way the PS scores it: size, RAM, CPU, speed, latency,
and accuracy. Each model runs in its own process so RAM numbers are clean.

    python benchmark.py run --manifest manifest.json --models ./models --real ./real_audio \
                            --out bench_out --tag int8 --threads 2

Real recordings (the important part - collect these!) go in:
    real_audio/<lang>/<name>.wav     any sample rate, mono
    real_audio/<lang>/<name>.txt     what was actually said (optional; without it
                                     you just get the transcript, no score)

What it measures, per language:
  STT   model size, load time, RAM, and for every clip: decode time, RTF, CER, WER
        - on your real recordings (true accuracy)
        - on TTS-made speech of known text (round trip: tests TTS clarity too)
  TTS   model size, load time, RAM, time-to-first-audio, RTF
  VAD   CPU used while listening to silence (the "idle listening CPU" metric)

RTF = processing time / audio length. Below 1.0 means faster than real time.
Numbers are only valid for the machine they ran on - run it on the target phone
(or the weakest laptop you have) for the numbers you report.
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
from common import (  # noqa: E402
    cer, load_json, make_stt, make_tts, read_char_vocab, sanitize, to_16k, transcribe, wer,
)

HERE = Path(__file__).resolve().parent.parent


def rss_mb() -> float:
    try:
        for line in open("/proc/self/status"):
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return peak_mb()


def peak_mb() -> float:
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 1024 if sys.platform != "darwin" else r / 1e6


def size_mb(*paths: Path) -> float:
    total = 0
    for p in paths:
        if p.is_dir():
            total += sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
        elif p.exists():
            total += p.stat().st_size
    return total / 1e6


def read_audio(path: Path) -> tuple[np.ndarray, int]:
    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return x[:, 0], sr


# ---------------------------------------------------------------------------
# child: one TTS voice
# ---------------------------------------------------------------------------
def child_tts(a) -> dict:
    import sherpa_onnx

    man = load_json(a.manifest)
    models = Path(a.models)
    eng = man["tts_engines"][a.engine]
    voice = json.loads(a.voice)
    texts = json.loads(Path(a.texts).read_text(encoding="utf-8"))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    base = rss_mb()
    t0 = time.perf_counter()
    tts = make_tts(eng, models, a.threads)
    load_s = time.perf_counter() - t0
    after_load = rss_mb()
    vocab = read_char_vocab(models / eng["tokens"]) if eng["frontend"] == "characters" else None

    rows = []
    for i, (name, text) in enumerate(texts):
        clean = sanitize(text, vocab)
        g = sherpa_onnx.GenerationConfig()
        g.sid, g.speed = int(voice.get("sid", 0)), float(voice.get("speed", 1.0))
        if voice.get("emotion_id") is not None:
            g.extra = {"emotion_id": str(voice["emotion_id"])}
        first = {}
        start = time.perf_counter()

        def cb(samples, progress, _f=first, _s=start):
            _f.setdefault("t", time.perf_counter() - _s)
            return 1

        audio = tts.generate(clean, g, cb)
        total = time.perf_counter() - start
        samples = np.asarray(audio.samples, dtype=np.float32)
        dur = len(samples) / audio.sample_rate
        wav = out / f"{i:02d}_{name}.wav"
        sf.write(str(wav), samples, audio.sample_rate)
        rows.append({"name": name, "text": text, "wav": str(wav), "dur_s": round(dur, 3),
                     "gen_s": round(total, 3), "first_audio_s": round(first.get("t", total), 3),
                     "rtf": round(total / max(dur, 1e-6), 3)})

    files = [models / eng["model"], models / eng["tokens"]]
    return {"engine": a.engine, "sample_rate": int(audio.sample_rate),
            "model_mb": round(size_mb(models / eng["model"]), 1),
            "total_mb": round(size_mb(*files), 1),
            "data_dir_mb": round(size_mb(models / eng["data_dir"]), 1) if eng.get("data_dir") else 0.0,
            "load_s": round(load_s, 2), "ram_model_mb": round(after_load - base, 1),
            "peak_rss_mb": round(peak_mb(), 1), "rows": rows}


# ---------------------------------------------------------------------------
# child: one STT model on a list of clips
# ---------------------------------------------------------------------------
def child_stt(a) -> dict:
    man = load_json(a.manifest)
    models = Path(a.models)
    stt_cfg = man["languages"][a.lang]["stt"]
    clips = json.loads(Path(a.clips).read_text(encoding="utf-8"))

    base = rss_mb()
    t0 = time.perf_counter()
    rec = make_stt(stt_cfg, models, a.threads)
    load_s = time.perf_counter() - t0
    after_load = rss_mb()

    # warm-up (first decode is always slower; phones should do this at startup too)
    transcribe(rec, np.zeros(16000, dtype=np.float32), 16000)

    rows = []
    for c in clips:
        x, sr = read_audio(Path(c["wav"]))
        x16 = to_16k(x, sr)
        dur = len(x16) / 16000
        start = time.perf_counter()
        hyp = transcribe(rec, x16, 16000)
        dt = time.perf_counter() - start
        row = {"kind": c["kind"], "wav": c["wav"], "dur_s": round(dur, 3), "decode_s": round(dt, 3),
               "rtf": round(dt / max(dur, 1e-6), 3), "hyp": hyp}
        if c.get("ref"):
            row.update(ref=c["ref"], cer=round(cer(c["ref"], hyp), 3), wer=round(wer(c["ref"], hyp), 3))
        rows.append(row)

    files = [models / stt_cfg[k] for k in ("model", "encoder", "decoder", "tokens") if stt_cfg.get(k)]
    return {"lang": a.lang, "type": stt_cfg["type"], "model_mb": round(size_mb(*files), 1),
            "load_s": round(load_s, 2), "ram_model_mb": round(after_load - base, 1),
            "peak_rss_mb": round(peak_mb(), 1), "rows": rows}


# ---------------------------------------------------------------------------
# child: VAD idle-listening cost
# ---------------------------------------------------------------------------
def child_vad(a) -> dict:
    import sherpa_onnx

    man = load_json(a.manifest)
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = str(Path(a.models) / man["vad"]["model"])
    cfg.silero_vad.min_silence_duration = 0.5
    cfg.silero_vad.threshold = 0.5
    cfg.sample_rate = 16000
    cfg.num_threads = 1
    base = rss_mb()
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)
    ram = rss_mb() - base
    win = cfg.silero_vad.window_size

    secs = 60
    rng = np.random.default_rng(0)
    noise = (rng.standard_normal(16000 * secs) * 0.003).astype(np.float32)  # quiet room
    c0 = time.process_time()
    for i in range(0, len(noise) - win, win):
        vad.accept_waveform(noise[i:i + win])
        while not vad.empty():
            vad.pop()
    cpu_s = time.process_time() - c0
    return {"model_mb": round(size_mb(Path(a.models) / man["vad"]["model"]), 2),
            "ram_model_mb": round(ram, 1), "audio_s": secs, "cpu_s": round(cpu_s, 3),
            "idle_cpu_percent_one_core": round(100 * cpu_s / secs, 2),
            "chunk_ms": round(1000 * win / 16000, 1)}


# ---------------------------------------------------------------------------
# orchestrator
# ---------------------------------------------------------------------------
def spawn(args: list[str]) -> dict:
    p = subprocess.run([sys.executable, __file__, *args], capture_output=True, text=True)
    lines = [l for l in p.stdout.splitlines() if l.startswith("{")]
    if p.returncode != 0 or not lines:
        raise RuntimeError(f"child failed: {' '.join(args[:3])}\n{p.stderr[-2000:]}")
    return json.loads(lines[-1])


def run(a) -> int:
    man = load_json(a.manifest)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    alerts = load_json(a.alerts)["alerts"]
    sents = load_json(a.sentences)["sentences"]
    langs = [l for l in man["languages"] if not a.only or l in a.only.split(",")]
    common = ["--manifest", a.manifest, "--models", a.models, "--threads", str(a.threads)]
    result = {"tag": a.tag, "threads": a.threads, "languages": {}}

    if not a.skip_vad:
        print("VAD idle cost ...", flush=True)
        result["vad"] = spawn(["vad", *common])

    for lang in langs:
        L = man["languages"][lang]
        entry = {"name": L["name"]}
        clips = []

        # TTS (cached per voice, so every STT variant hears the same audio)
        eng_name = L["tts"].get("engine")
        if eng_name and eng_name in man["tts_engines"]:
            texts = [(n, x["texts"][lang]) for n, x in alerts.items() if lang in x["texts"]]
            texts += [(n, x[lang]) for n, x in sents.items() if lang in x]
            tdir = out / "tts" / f"{lang}_{eng_name}"
            cache = tdir / "result.json"
            if cache.exists():
                tts = json.loads(cache.read_text(encoding="utf-8"))
            else:
                print(f"{lang}: TTS {eng_name} ...", flush=True)
                tf = out / f"_texts_{lang}.json"
                tf.write_text(json.dumps(texts, ensure_ascii=False), encoding="utf-8")
                tts = spawn(["tts", *common, "--engine", eng_name, "--voice", json.dumps(L["tts"]),
                             "--texts", str(tf), "--out", str(tdir)])
                cache.write_text(json.dumps(tts, ensure_ascii=False, indent=1), encoding="utf-8")
            entry["tts"] = tts
            clips += [{"kind": "roundtrip", "wav": r["wav"], "ref": r["text"]} for r in tts["rows"]]

        # real recordings
        rdir = Path(a.real) / lang if a.real else None
        if rdir and rdir.is_dir():
            for w in sorted(rdir.glob("*.wav")):
                t = w.with_suffix(".txt")
                clips.append({"kind": "real", "wav": str(w),
                              "ref": t.read_text(encoding="utf-8").strip() if t.exists() else ""})

        if clips and L.get("stt"):
            print(f"{lang}: STT {L['stt']['type']} on {len(clips)} clips ...", flush=True)
            cf = out / f"_clips_{a.tag}_{lang}.json"
            cf.write_text(json.dumps(clips, ensure_ascii=False), encoding="utf-8")
            try:
                entry["stt"] = spawn(["stt", *common, "--lang", lang, "--clips", str(cf)])
            except RuntimeError as e:
                entry["stt_error"] = str(e)[-500:]
                print("   FAILED:", str(e)[-300:])
        result["languages"][lang] = entry

    path = out / f"results_{a.tag}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nwrote {path}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "tts", "stt", "vad"):
        p = sub.add_parser(name)
        p.add_argument("--manifest", required=True)
        p.add_argument("--models", required=True)
        p.add_argument("--threads", type=int, default=2)
        if name == "run":
            p.add_argument("--out", default="bench_out")
            p.add_argument("--tag", default="default")
            p.add_argument("--real", default="")
            p.add_argument("--only", default="")
            p.add_argument("--alerts", default=str(HERE / "alerts.json"))
            p.add_argument("--sentences", default=str(HERE / "test_sentences.json"))
            p.add_argument("--skip-vad", action="store_true")
        if name == "tts":
            p.add_argument("--engine", required=True)
            p.add_argument("--voice", required=True)
            p.add_argument("--texts", required=True)
            p.add_argument("--out", required=True)
        if name == "stt":
            p.add_argument("--lang", required=True)
            p.add_argument("--clips", required=True)
    a = ap.parse_args()
    if a.cmd == "run":
        return run(a)
    res = {"tts": child_tts, "stt": child_stt, "vad": child_vad}[a.cmd](a)
    print(json.dumps(res, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
