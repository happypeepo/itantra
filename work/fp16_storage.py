#!/usr/bin/env python3
"""
"FP16 storage, FP32 compute": halve an ONNX model's size on disk without changing
how it runs.

Every large float32 weight is stored as float16 and a Cast(to=float32) node is put in
front of its users. When ONNX Runtime loads the model, constant folding runs each
Cast once, so inference is plain float32 with the same speed. Only the weights lose
precision (float16 has ~3 significant digits), which is usually inaudible and
invisible in accuracy - but always compare with verify/benchmark.

INT8 weights (e.g. an STT model's MatMul weights) are left alone; only float32
initializers are converted, so it also shrinks the fp32 Conv weights of our
MatMul-only INT8 STT models.

    python work/fp16_storage.py --model in.onnx --out out.onnx
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

MIN_ELEMENTS = 256   # tiny tensors (shapes, scalars) aren't worth it and are often shape inputs


def subgraph_names(g: onnx.GraphProto) -> set[str]:
    used = set()
    for n in g.node:
        for a in n.attribute:
            for sg in ([a.g] if a.type == onnx.AttributeProto.GRAPH else list(a.graphs)):
                for sn in sg.node:
                    used.update(sn.input)
                used |= subgraph_names(sg)
    return used


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    m = onnx.load(a.model)
    g = m.graph
    in_subgraphs = subgraph_names(g)
    keep, casts, n_conv, saved = [], [], 0, 0
    for init in g.initializer:
        if (init.data_type == TensorProto.FLOAT and int(np.prod(init.dims)) >= MIN_ELEMENTS
                and init.name not in in_subgraphs):
            w = numpy_helper.to_array(init)
            h = numpy_helper.from_array(w.astype(np.float16), init.name + "__fp16")
            keep.append(h)
            casts.append(helper.make_node("Cast", [h.name], [init.name], to=TensorProto.FLOAT, name=init.name + "__cast"))
            n_conv += 1
            saved += w.nbytes // 2
        else:
            keep.append(init)
    del g.initializer[:]
    g.initializer.extend(keep)
    nodes = casts + list(g.node)          # Casts first keeps the graph topologically sorted
    del g.node[:]
    g.node.extend(nodes)
    onnx.save(m, a.out)
    print(f"{n_conv} weights stored as fp16, ~{saved / 1e6:.0f} MB saved -> {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
