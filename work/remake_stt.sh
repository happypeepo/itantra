#!/bin/bash
# Re-make the 7 betterflow languages from OpenVoiceOS fp32: MatMul-only INT8 (-> models/) + INT4 (-> work/int4/).
set -e
cd "$(dirname "$0")/.."
. .venv/bin/activate
S=p2-models/scripts
for l in bn gu kn ml mr ta te; do
  echo "=== $l download"
  python -c "
from huggingface_hub import snapshot_download as dl
dl('OpenVoiceOS/ai4bharat-indicconformer-$l-onnx', local_dir='dl/ovos-$l', allow_patterns=['model.onnx','model.onnx_data','vocab.txt','config.json','README.md'])" 2>&1 | tail -1
  cp dl/ovos-$l/vocab.txt dl/ovos-$l/tokens.txt
  echo "=== $l patch (INT8 MatMul-only)"
  python $S/patch_stt.py --model dl/ovos-$l/model.onnx --tokens dl/ovos-$l/tokens.txt --out models/stt/$l --lang $l 2>&1 | grep -E 'vocab|factor|wrote|ERROR'
  echo "=== $l INT4"
  mkdir -p work/int4/$l && cp dl/ovos-$l/tokens.txt work/int4/$l/
  python $S/quantize_int4.py --model dl/ovos-$l/model.sherpa.onnx --out work/int4/$l/model.int4.onnx 2>&1 | grep -E '^wrote'
done
for l in hi or; do mkdir -p work/int4/$l && cp work/t5/int4/$l/* work/int4/$l/; done
echo REMAKE DONE
