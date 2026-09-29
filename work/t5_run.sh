#!/bin/bash
# T5: INT8 vs INT4 (and fp32, Conv-INT8) for IndicConformer hi + or, same TTS audio for all.
set -e
cd "$(dirname "$0")/.."
. .venv/bin/activate
S=p2-models/scripts
W=work/t5
for l in hi or; do
  mkdir -p $W/int4/$l $W/ovos_int8/$l
  [ -f $W/int4/$l/model.int4.onnx ] || python $S/quantize_int4.py --model dl/ovos-$l/model.sherpa.onnx --out $W/int4/$l/model.int4.onnx
  cp dl/ovos-$l/tokens.txt $W/int4/$l/ && cp dl/ovos-$l/tokens.txt $W/ovos_int8/$l/
  # OpenVoiceOS's own INT8 (Conv+MatMul) has no sherpa metadata: stamp a copy with the same values patch_stt.py uses
  [ -f $W/ovos_int8/$l/model.int8.onnx ] || python - "$l" <<'PY'
import sys, onnx
sys.path.insert(0, "p2-models/scripts"); from patch_stt import set_meta
l = sys.argv[1]
m = onnx.load(f"dl/ovos-{l}/model.int8.onnx")
set_meta(m, {"vocab_size": 257, "subsampling_factor": 4, "normalize_type": "per_feature", "feature_dim": 80,
             "model_type": "EncDecCTCModelBPE", "version": "1", "model_author": "ai4bharat",
             "comment": f"OpenVoiceOS own int8 {l}, metadata stamped for T5 comparison"})
onnx.save(m, f"work/t5/ovos_int8/{l}/model.int8.onnx")
PY
done
python - <<'PY'
import json
man = json.load(open("models/manifest.json"))
for e in man["tts_engines"].values():
    for k in ("model", "tokens", "data_dir"):
        if e.get(k): e[k] = "models/" + e[k]
V = {"fp32":      {l: (f"dl/ovos-{l}/model.sherpa.onnx", f"dl/ovos-{l}/tokens.txt") for l in ("hi", "or")},
     "int8_ours": {l: (f"models/stt/{l}/model.int8.onnx", f"models/stt/{l}/tokens.txt") for l in ("hi", "or")},
     "int8_conv": {l: (f"work/t5/ovos_int8/{l}/model.int8.onnx", f"work/t5/ovos_int8/{l}/tokens.txt") for l in ("hi", "or")},
     "int4":      {l: (f"work/t5/int4/{l}/model.int4.onnx", f"work/t5/int4/{l}/tokens.txt") for l in ("hi", "or")},
     "int8_betterflow": {"hi": ("dl/betterflow/hi/model.int8.onnx", "dl/betterflow/hi/tokens.txt")}}
for tag, langs in V.items():
    m = json.loads(json.dumps(man))
    m["languages"] = {l: m["languages"][l] for l in langs}
    for l, (mo, to) in langs.items():
        m["languages"][l]["stt"].update(model=mo, tokens=to)
    json.dump(m, open(f"work/t5/manifest_{tag}.json", "w"), ensure_ascii=False, indent=1)
PY
for tag in int8_ours fp32 int8_conv int4 int8_betterflow; do
  echo "=== benchmark $tag"
  python $S/benchmark.py run --manifest $W/manifest_$tag.json --models . --out $W/bench --tag $tag --threads 2 --skip-vad
done
echo T5 DONE
