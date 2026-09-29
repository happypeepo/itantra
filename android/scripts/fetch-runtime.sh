#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
artifact=app/libs/sherpa-onnx-1.13.8.aar
expected=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
if [[ ! -f "$artifact" ]]; then
  curl --fail --location --retry 3 https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar -o "$artifact.part"
  mv "$artifact.part" "$artifact"
fi
printf '%s  %s\n' "$expected" "$artifact" | shasum -a 256 -c -
