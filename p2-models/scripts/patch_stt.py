#!/usr/bin/env python3
"""
Make a NeMo Conformer-CTC ONNX model (IndicConformer) loadable by sherpa-onnx,
then make a smaller INT8 copy.

Use it for the Hindi and Odia models from OpenVoiceOS, and for any language
where the ready-made export doesn't work.

    python patch_stt.py --model ovos-hi/model.onnx --tokens betterflow/gu/tokens.txt \
                        --out models/stt/hi --lang hi

Writes into --out (this is what goes on the phone):
    model.int8.onnx   INT8 (about 3x smaller), with sherpa-onnx metadata
    tokens.txt        copied from --tokens

Also writes a full-size copy with metadata NEXT TO THE INPUT FILE
(<input>.sherpa.onnx), kept off the phone. It's your fallback if INT8 ever
gives wrong text. With --skip-int8, the full-size model goes into --out as
model.onnx instead.

Why each step exists (these are the two traps that fail SILENTLY):

  1. sherpa-onnx needs metadata inside the .onnx file. Without 'vocab_size' or
     'subsampling_factor' the app crashes at load with a native crash you can't
     catch. Without normalize_type='per_feature' it loads fine but returns EMPTY
     text, with no error at all.

  2. Only MatMul layers are made INT8. If Conv layers are made INT8 too, the
     model can load, run, and output only blanks (empty text).

The script also runs the model once on fake input to measure the real vocab
size and subsampling factor, so the metadata can't be wrong.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

sys.path.insert(0, str(Path(__file__).parent))
from common import count_token_lines, sha256  # noqa: E402

FEATURE_DIM = 80
PROBE_FRAMES = 400


def probe(model_path: Path) -> tuple[int, int]:
    """Run the model on fake features. Returns (vocab_size, subsampling_factor)."""
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    feeds = {}
    for inp in sess.get_inputs():
        if "float" in inp.type:
            feeds[inp.name] = np.random.randn(1, FEATURE_DIM, PROBE_FRAMES).astype(np.float32) * 0.1
        elif "int64" in inp.type:
            feeds[inp.name] = np.array([PROBE_FRAMES], dtype=np.int64)
        elif "int32" in inp.type:
            feeds[inp.name] = np.array([PROBE_FRAMES], dtype=np.int32)
        else:
            raise RuntimeError(f"unexpected input {inp.name} of type {inp.type}")
    outs = sess.run(None, feeds)
    logits = next((o for o in outs if getattr(o, "ndim", 0) == 3), None)
    if logits is None:
        raise RuntimeError("no 3-D output found; is this a CTC model?")
    t_out, vocab = logits.shape[1], logits.shape[2]
    factor = int(round(PROBE_FRAMES / t_out))
    return vocab, factor


def set_meta(model: onnx.ModelProto, meta: dict) -> None:
    kept = [(p.key, p.value) for p in model.metadata_props if p.key not in meta]
    del model.metadata_props[:]
    for k, v in kept:
        model.metadata_props.add(key=k, value=v)
    for k, v in meta.items():
        model.metadata_props.add(key=k, value=str(v))


def stamp(path: Path, meta: dict) -> None:
    m = onnx.load(str(path))
    set_meta(m, meta)
    onnx.save(m, str(path))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="input CTC .onnx")
    ap.add_argument("--tokens", required=True, help="tokens.txt (one 'token id' per line, blank last)")
    ap.add_argument("--out", required=True, help="output folder, e.g. models/stt/hi")
    ap.add_argument("--lang", default="", help="language code, only used in the comment")
    ap.add_argument("--skip-int8", action="store_true")
    args = ap.parse_args()

    src, tokens, out = Path(args.model), Path(args.tokens), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    n_tokens = count_token_lines(tokens)
    print(f"tokens.txt lines: {n_tokens}")

    print("probing model on fake input ...")
    vocab, factor = probe(src)
    print(f"  model output vocab size: {vocab}")
    print(f"  measured subsampling factor: {factor}")
    if vocab != n_tokens:
        print(f"ERROR: model outputs {vocab} classes but tokens.txt has {n_tokens} lines.")
        print("       This tokens.txt doesn't belong to this model. Use the model's own vocab.")
        return 1
    if factor not in (4, 8):
        print(f"WARNING: unusual subsampling factor {factor}; double-check the model.")

    meta = {
        "vocab_size": vocab,
        "subsampling_factor": factor,
        "normalize_type": "per_feature",
        "feature_dim": FEATURE_DIM,
        "model_type": "EncDecCTCModelBPE",
        "version": "1",
        "model_author": "ai4bharat",
        "comment": f"IndicConformer CTC {args.lang}, metadata added for sherpa-onnx",
    }

    fp32 = (out / "model.onnx") if args.skip_int8 else src.with_suffix(".sherpa.onnx")
    if src.resolve() != fp32.resolve():
        shutil.copyfile(src, fp32)
    stamp(fp32, meta)
    print(f"wrote {fp32}  ({fp32.stat().st_size / 1e6:.0f} MB)")

    if not args.skip_int8:
        int8 = out / "model.int8.onnx"
        print("making INT8 copy (MatMul only) ...")
        quantize_dynamic(
            model_input=str(fp32),
            model_output=str(int8),
            op_types_to_quantize=["MatMul"],  # NOT Conv: Conv INT8 can give empty output
            weight_type=QuantType.QInt8,
        )
        stamp(int8, meta)  # quantization can drop metadata, so stamp again
        print(f"wrote {int8}  ({int8.stat().st_size / 1e6:.0f} MB)  sha256 {sha256(int8)[:16]}")

    if tokens.resolve() != (out / "tokens.txt").resolve():
        shutil.copyfile(tokens, out / "tokens.txt")

    print("\nDone. Now run verify_models.py - a model that loads is NOT proven to work.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
