#!/usr/bin/env python3
"""
Size experiment: can the TTS voices be stored smaller without hurting them?

For each voice (rasa, Piper hi, Piper en, MMS gu, MMS or) make:
    int8_matmul   dynamic INT8, MatMul only
    int8_all      dynamic INT8, default op set (also Conv -> ConvInteger)
and compare with fp32: file size, laptop RTF, round-trip CER through our STT
(same texts as verify_models: the 5 alerts + the test sentence). WAVs are written
to size_out/tts/<voice>/<variant>/ for listening - CER alone can't hear noise.

    python work/size_tts_quant.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import onnx
import soundfile as sf
from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "p2-models/scripts"))
from common import cer, load_json, make_stt, make_tts, read_char_vocab, sanitize, transcribe, tts_generate  # noqa: E402

MODELS = ROOT / "models"
OUT = ROOT / "size_out"
VOICES = {"rasa": "ta", "piper_hi": "hi", "piper_en": "en", "mms_gu": "gu", "mms_or": "or"}


def quantize(src: Path, dst: Path, ops: list[str] | None) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    kw = {"op_types_to_quantize": ops} if ops else {}
    quantize_dynamic(model_input=str(src), model_output=str(dst), weight_type=QuantType.QInt8, **kw)
    meta = {p.key: p.value for p in onnx.load(str(src), load_external_data=False).metadata_props}
    m = onnx.load(str(dst))
    have = {p.key for p in m.metadata_props}
    for k, v in meta.items():
        if k not in have:
            m.metadata_props.add(key=k, value=v)
    onnx.save(m, str(dst))


def main() -> int:
    man = load_json(MODELS / "manifest.json")
    alerts = load_json(ROOT / "p2-models/alerts.json")["alerts"]
    sents = load_json(ROOT / "p2-models/test_sentences.json")["sentences"]
    res = {"machine": "Apple M4, 2 threads, onnxruntime 1.30.0", "voices": {}}
    for eng_name, lang in VOICES.items():
        eng = dict(man["tts_engines"][eng_name])
        L = man["languages"][lang]
        src = MODELS / eng["model"]
        texts = [a["texts"][lang] for a in alerts.values()] + [sents["road_blocked"][lang]]
        vocab = read_char_vocab(MODELS / eng["tokens"]) if eng["frontend"] == "characters" else None
        stt = make_stt(L["stt"], MODELS)
        variants = {"fp32": src}
        for name, ops in (("int8_matmul", ["MatMul"]), ("int8_all", None)):
            dst = OUT / "models" / eng_name / f"{name}.onnx"
            if not dst.exists():
                print(f"{eng_name}: quantizing {name} ...", flush=True)
                quantize(src, dst, ops)
            variants[name] = dst
        res["voices"][eng_name] = {}
        for vname, path in variants.items():
            # make_tts joins paths onto its `models` argument, so pass absolute paths and "/"
            e_abs = {"model": str(path), "tokens": str(MODELS / eng["tokens"]),
                     "data_dir": str(MODELS / eng["data_dir"]) if eng.get("data_dir") else ""}
            tts = make_tts(e_abs, Path("/"), 2)
            wav_dir = OUT / "tts" / eng_name / vname
            wav_dir.mkdir(parents=True, exist_ok=True)
            tts_generate(tts, sanitize(texts[0], vocab), L["tts"].get("sid", 0), 1.0,
                         L["tts"].get("emotion_id") if eng_name == "rasa" else None)  # warm-up
            cers, rtfs = [], []
            for i, text in enumerate(texts):
                t0 = time.perf_counter()
                x, sr = tts_generate(tts, sanitize(text, vocab), L["tts"].get("sid", 0), 1.0,
                                     L["tts"].get("emotion_id") if eng_name == "rasa" else None)
                gen = time.perf_counter() - t0
                rtfs.append(gen / max(len(x) / sr, 1e-6))
                sf.write(wav_dir / f"{lang}_{i:02d}.wav", x, sr)
                cers.append(cer(text, transcribe(stt, x, sr)))
            ops = [n.op_type for n in onnx.load(str(path), load_external_data=False).graph.node]
            row = {"size_mb": round(path.stat().st_size / 1e6, 1), "rtf": round(float(np.mean(rtfs)), 3),
                   "cer": round(float(np.mean(cers)), 3), "ConvInteger": ops.count("ConvInteger"),
                   "MatMulInteger": ops.count("MatMulInteger"), "Conv_left": ops.count("Conv")}
            res["voices"][eng_name][vname] = row
            print(f"{eng_name:9} {vname:12} {row['size_mb']:6.1f} MB  RTF {row['rtf']:.3f}  CER {row['cer']:.3f}  "
                  f"ConvInteger {row['ConvInteger']} MatMulInteger {row['MatMulInteger']} Conv(fp32) {row['Conv_left']}", flush=True)
            del tts
    (OUT / "tts_quant.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
    print("wrote", OUT / "tts_quant.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
