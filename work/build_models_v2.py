#!/usr/bin/env python3
"""
Build the smaller model set (docs/SIZE_REDUCTION.md changes 2-4) from models/:

  2. voices:  every float32 weight stored as float16, run as float32 (fp16_storage.py)
  3. STT:     the same for the INT8 models' remaining float32 weights (Conv etc.)
  4. espeak-ng-data trimmed to the Hindi + English dictionaries

Same file names and paths as models/, so manifest.json and the app need no changes.

    python work/build_models_v2.py --src models --out models_v2
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ESPEAK_KEEP = ["hi_dict", "en_dict", "phondata", "phonindex", "phontab", "intonations", "lang", "voices"]


def fp16(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(ROOT / "work/fp16_storage.py"), "--model", str(src), "--out", str(dst)],
                   check=True, capture_output=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(ROOT / "models"))
    ap.add_argument("--out", default=str(ROOT / "models_v2"))
    a = ap.parse_args()
    src, out = Path(a.src), Path(a.out)
    man = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)

    for code, L in man["languages"].items():
        m, t = L["stt"]["model"], L["stt"]["tokens"]
        fp16(src / m, out / m)
        shutil.copy2(src / t, out / t)
        print(f"STT {code}: {(src / m).stat().st_size / 1e6:6.1f} -> {(out / m).stat().st_size / 1e6:6.1f} MB", flush=True)

    for name, e in man["tts_engines"].items():
        fp16(src / e["model"], out / e["model"])
        shutil.copy2(src / e["tokens"], out / e["tokens"])
        print(f"TTS {name}: {(src / e['model']).stat().st_size / 1e6:6.1f} -> {(out / e['model']).stat().st_size / 1e6:6.1f} MB", flush=True)

    esp_src, esp_out = src / "tts/espeak-ng-data", out / "tts/espeak-ng-data"
    shutil.rmtree(esp_out, ignore_errors=True)
    esp_out.mkdir(parents=True)
    for item in ESPEAK_KEEP:
        (shutil.copytree if (esp_src / item).is_dir() else shutil.copy2)(esp_src / item, esp_out / item)

    (out / man["vad"]["model"]).parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src / man["vad"]["model"], out / man["vad"]["model"])

    man["_storage"] = ("models_v2: float32 weights stored as float16 and cast back to float32 when ONNX Runtime "
                       "loads the model (same speed); espeak-ng-data trimmed to hi + en. Built by work/build_models_v2.py.")
    (out / "manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1), encoding="utf-8")
    size = lambda p: sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / 1e6  # noqa: E731
    print(f"\nmodels: {size(src):.0f} MB -> models_v2: {size(out):.0f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
