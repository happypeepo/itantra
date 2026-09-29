#!/usr/bin/env python3
"""
Make an INT4 copy of an ONNX speech model (weights of MatMul layers only).

INT4 stores each weight in 4 bits instead of 8, so MatMul-heavy models shrink
a lot more than with INT8. Only MatMul layers are changed; Conv layers stay as
they are (the same reason as in patch_stt.py: shrinking Conv can break output).

    python quantize_int4.py --model model.onnx --out model.int4.onnx

Needs the FULL-SIZE (fp32) model as input, not an INT8 one.
Needs: pip install onnx onnxruntime onnx_ir

The output uses the ONNX Runtime 'MatMulNBits' operator. It runs on the normal
ONNX Runtime CPU engine, including the Android build sherpa-onnx uses. ALWAYS
compare accuracy against INT8 with verify/benchmark before switching - INT4 is
smaller but can be less accurate.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import onnx
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="full-size fp32 .onnx")
    ap.add_argument("--out", required=True, help="output .onnx")
    ap.add_argument("--block-size", type=int, default=32,
                    help="weights per scale; smaller = more accurate, slightly bigger (default 32)")
    ap.add_argument("--asymmetric", action="store_true", help="asymmetric quantization (slightly more accurate)")
    ap.add_argument("--accuracy-level", type=int, default=4,
                    help="4 = compute with int8 activations (fastest on phones); 0 = default")
    args = ap.parse_args()

    src, out = Path(args.model), Path(args.out)
    print(f"loading {src} ({src.stat().st_size / 1e6:.0f} MB) ...")
    model = onnx.load(str(src))
    meta = [(p.key, p.value) for p in model.metadata_props]

    q = MatMulNBitsQuantizer(
        model,
        bits=4,
        block_size=args.block_size,
        is_symmetric=not args.asymmetric,
        accuracy_level=args.accuracy_level or None,
        op_types_to_quantize=("MatMul",),
    )
    print("quantizing MatMul weights to 4 bits ...")
    q.process()
    qm = q.model.model

    # keep sherpa-onnx metadata (vocab_size, model_type, ...)
    have = {p.key for p in qm.metadata_props}
    for k, v in meta:
        if k not in have:
            qm.metadata_props.add(key=k, value=v)

    n4 = sum(1 for n in qm.graph.node if n.op_type == "MatMulNBits")
    left = sum(1 for n in qm.graph.node if n.op_type == "MatMul")
    onnx.save(qm, str(out))
    print(f"wrote {out} ({out.stat().st_size / 1e6:.0f} MB): {n4} MatMul layers now INT4, {left} left as-is")
    print("Now compare accuracy against INT8 before using it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
