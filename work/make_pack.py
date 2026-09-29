#!/usr/bin/env python3
"""
Build a per-phone language pack (docs/SIZE_REDUCTION.md change 1).

A phone needs STT only for the language(s) its user speaks, but EVERY voice, because
incoming speech is spoken in the sender's language (alerts are pre-rendered WAVs in
the receiver's language). The app offers only the speech languages whose STT is present.

    python work/make_pack.py --langs hi,en            -> packs/hi-en/models
    python work/make_pack.py --langs ta --src models  (from the full-precision set)
    adb push packs/hi-en/models/. /sdcard/Android/data/org.itantra.app/files/models/

Files are hard-linked from --src when possible, so packs cost no extra disk.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def put(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_dir():
        shutil.copytree(src, dst, copy_function=_link, dirs_exist_ok=True)
    else:
        _link(src, dst)


def _link(s, d):
    try:
        if os.path.exists(d):
            os.remove(d)
        os.link(s, d)
    except OSError:
        shutil.copy2(s, d)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--langs", required=True, help="speech languages for this phone, e.g. hi,en")
    ap.add_argument("--src", default=str(ROOT / "models_v2"))
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    src = Path(a.src)
    man = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    langs = [l.strip() for l in a.langs.split(",") if l.strip()]
    bad = [l for l in langs if l not in man["languages"]]
    if bad:
        sys.exit(f"unknown language(s): {bad}; valid: {list(man['languages'])}")
    out = Path(a.out) if a.out else ROOT / "packs" / "-".join(langs) / "models"
    if out.exists():
        shutil.rmtree(out)
    for l in langs:
        for key in ("model", "tokens", "encoder", "decoder"):
            p = man["languages"][l]["stt"].get(key)
            if p:
                put(src / p, out / p)
    for e in man["tts_engines"].values():
        for key in ("model", "tokens"):
            put(src / e[key], out / e[key])
        if e.get("data_dir"):
            put(src / e["data_dir"], out / e["data_dir"])
    put(src / man["vad"]["model"], out / man["vad"]["model"])
    man["_pack"] = f"speech languages (STT) in this pack: {', '.join(langs)}; all voices included"
    (out / "manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1), encoding="utf-8")
    size = sum(f.stat().st_size for f in out.rglob("*") if f.is_file()) / 1e6
    print(f"pack {'-'.join(langs)}: {size:.0f} MB -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
