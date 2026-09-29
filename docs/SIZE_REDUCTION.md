# Reducing the app's total size

**Today:** 2,238 MB of models + 38.7 MB APK = **~2.28 GB** for all 10 languages on every phone.
**Measured target:** **~430 MB per phone** (one speaking language + every voice), or **~1.72 GB** if every phone keeps all 10.

Everything below was measured (2026-09-30): sizes from the files, speed and accuracy on the Apple M4 laptop (2 threads, onnxruntime 1.30.0), and the key ones re-checked **on the Nothing Phone (3a)** (sherpa-onnx 1.13.8 CLI, ONNX Runtime 1.28.2). Raw numbers are in `docs/size/`.

## Where the 2.28 GB goes

| Part | Size | Share |
|---|---|---|
| STT, 9 Indic languages × 186 MB | 1,674 MB | 73% |
| STT, English | 68 MB | 3% |
| TTS voices (rasa 123, Piper hi 63, Piper en 64, MMS gu 114, MMS or 114) | 478 MB | 21% |
| espeak-ng-data (Piper support data) | 18 MB | 1% |
| APK | 38.7 MB | 2% |

## What to do, in order of impact

| # | Change | Saves | Speed | Accuracy | Effort |
|---|---|---|---|---|---|
| **1** | **Per-phone language pack:** copy STT only for the language(s) that phone's user speaks; keep **all** voices (a phone must speak whatever language arrives) | **−1.56 GB per phone** (2,277 → 721 MB) | same | same | P1: hide languages whose STT isn't installed. P2: a copy script per pack |
| **2** | **FP16 storage for the voices:** weights stored as float16, run as float32 (`work/fp16_storage.py`) | **−236 MB** (478 → 242) | **same** (phone: rasa RTF 0.60–0.65 vs 0.69–0.71) | same within run noise (Odia over 4 runs: CER 0.070 vs 0.085) | P2: convert + listen |
| **3** | **FP16 storage for STT's fp32 Conv weights** (the INT8 MatMul weights are untouched) | **−33 MB per Indic language** (186 → 153); −297 MB for all 9 | **same** (phone: RTF 0.152 vs 0.156) | **identical transcripts** on the phone (Hindi, 6 clips) | P2: convert + `verify_models.py` for all 9 |
| **4** | **Trim espeak-ng-data** to the Hindi + English dictionaries | −17 MB (18 → 1.0) | same | identical output (same durations, same CER) | P2: copy 8 files |
| **5** | **APK: drop 2 unused native libs** (`libsherpa-onnx-c-api.so`, `-cxx-api.so`) | ≈ −4.9 MB | same | same | P1: `packaging.jniLibs.excludes` (check it still loads) |
| 6 | APK: release build with R8 minify + shrinkResources (the APK grew 33.0 → 38.7 MB with the Material 3 commit) | not measured | same | same | P1 |

### Resulting sizes (models + APK)

| Scenario | Total |
|---|---|
| Today, all 10 languages | 2,277 MB |
| All 10 languages, changes 2–5 | **1,724 MB** |
| **Per-phone pack, 1 Indic language (e.g. Hindi or Tamil), changes 1–5** | **430 MB** |
| Per-phone pack, English only | 345 MB |
| Per-phone pack, Hindi + English (bilingual user) | 498 MB |
| Per-phone pack, 1 language, change 1 only (no model changes at all) | 721 MB |

## Tried and rejected (measured)

| Idea | Size | Why not |
|---|---|---|
| **INT8 voices, including Conv** (`quantize_dynamic` defaults) | 3× smaller (voices 478 → ~155 MB) | **4–5× slower**: rasa RTF 0.58 → 2.16, MMS 0.40 → 1.42, Piper 0.07 → 0.32 on the M4. INT8 Conv is slow on ARM (same as the betterflow STT finding). rasa would be far slower than real time on a phone |
| INT8 voices, MatMul only | ~0% smaller | VITS voices are almost all Conv |
| INT4 STT | 186 → 149 MB | ~50% slower on the phone (Hindi RTF 0.23 vs 0.15). FP16 storage (#3) gets close (153 MB) with no slowdown |
| Conv-INT8 STT (betterflow-style) | 140 MB | ~3× slower |

## Not tried yet

- **English `nemo-ctc-en-conformer-small`** (a 76 MB download vs 160 MB for the medium; INT8 file probably ~half of today's 68 MB). Accuracy unknown; saves little.
- **One multilingual STT model for all 9 Indic languages** (AI4Bharat's multilingual IndicConformer) instead of 9 separate ones. Potentially the biggest saving if every phone must keep all languages, but it's a larger model with a different decoding setup. That's research, and out of scope for the 48 hours, so it goes on the Future work slide.

## Before switching

1. **Listen** to the FP16-storage voices next to the originals: `size_out/tts/<voice>/fp32/` vs `size_out/tts/<voice>/fp16-store/` (not in git; regenerate with the commands below).
2. Convert all 9 Indic STT models and run `python p2-models/scripts/verify_models.py --models models` (only Hindi has been checked so far).
3. Keep the originals as the fallback (`dl/` and the current `models/`).

```bash
python work/fp16_storage.py --model models/tts/rasa/model.onnx --out rasa.fp16.onnx
python work/fp16_storage.py --model models/stt/hi/model.int8.onnx --out hi.int8.fp16.onnx
python work/size_tts_quant.py      # the INT8-voice experiment (sizes, speed, CER, WAVs)
```

**Why FP16 storage keeps the speed:** each float16 weight gets a `Cast` to float32 in front of it. When ONNX Runtime loads the model, constant folding runs those Casts once, so inference is ordinary float32. Peak memory while loading was ~10–20 MB higher on the phone. RAM once loaded is the same as today, so this saves disk and download size, not RAM.
