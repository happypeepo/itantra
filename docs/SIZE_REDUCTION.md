# Reducing the app's total size

**Before:** 2,238 MB of models + 38.5 MB APK = **~2.28 GB** for all 10 languages on every phone.
**Done:** every phone now ships with **Hindi + English + one Indic language** and every voice: **646 MB per phone** including the APK (−72%). A single-language pack would be 430 MB; all 10 languages would be 1.72 GB. Details below.

Everything below was measured (2026-09-30): sizes from the files, speed and accuracy on the Apple M4 laptop (2 threads, onnxruntime 1.30.0), and the key ones re-checked **on the Nothing Phone (3a)** (sherpa-onnx 1.13.8 CLI, ONNX Runtime 1.28.2). Raw numbers are in `docs/size/`.

## Done (2026-09-30): measured results

All five changes are in. **Models:** `models/` is now the smaller set (built by `work/build_models_v2.py`); the old full-size set and the original downloads were removed on 2026-09-30. To rebuild them, download the originals (`p2-models/README.md` §4–5), then run `work/build_models_v2.py`. **App:** the picker lists only installed speech languages (`MainActivity`), and the unused libraries are excluded (`app/build.gradle.kts`, already in P1's `e968ad4`). **Packs:** `work/make_pack.py`.

Before/after on the **same Nothing Phone (3a)**, same 6 clips per language, same settings (STT 2 threads, TTS 4 threads), official sherpa-onnx 1.13.8 CLI (`work/phone_bench.py`, compared by `work/compare_phone.py`). The laptop check is `work/compare_v1_v2.py`. Raw data is in `docs/size/`.

### Size

| | Before | After |
|---|---|---|
| All 10 languages, models | 2,238 MB | **1,685 MB** (−25%) |
| APK, clean builds of the same code without / with the excluded libs | 38.53 MB | **33.62 MB** (−4.9 MB) |
| **Per phone, default pack: Hindi + English + 1 Indic language, + APK** | 2,277 MB (everything installed) | **646 MB** (−72%) |
| Per phone: 1 Indic language only + APK | 2,277 MB | 430 MB (−81%) |
| Per phone: English only + APK | 2,277 MB | 339 MB (−85%) |
| On the test phones (the app's own size badge) | 2.0 GB of models | **651 MB** in total (models 612 + APK 33.6 + alert sounds 5.9) |

*APK sizes are from clean builds. Figures of 38.65–43.4 MB quoted earlier came from incremental debug builds, which leave unused space inside the APK.*

### Accuracy (no loss)

| | Before | After |
|---|---|---|
| STT transcripts identical, phone | | **56/60** |
| STT transcripts identical, laptop | | **56/60** |
| STT CER / WER, phone, mean of 10 languages | 0.043 / 0.190 | 0.041 / 0.183 |
| Voice round trip CER, laptop (18 generations per language) | 0.041 | 0.042 |
| Voice round trip CER, phone-made speech (6 per language) | 0.055 | 0.031 |

The small differences go both ways across languages and are within run-to-run noise (VITS voices are random each time). There was no systematic loss anywhere.

### Latency (unchanged)

| Phone, mean of 10 languages | Before | After |
|---|---|---|
| STT RTF (2 threads) | 0.151 | 0.151 |
| Voice RTF (4 threads) | 0.582 | 0.563 |
| Time to first audio, avg / max (4 threads) | 1.27 / 2.59 s | 1.26 / 2.55 s |
| Model load, STT / voice | 1.63 / 1.27 s | 1.61 / 1.32 s |

### Efficiency (RAM and CPU unchanged)

| | Before | After |
|---|---|---|
| STT peak RAM (phone, mean) | 433 MB | 436 MB |
| Voice peak RAM (phone, mean) | 299 MB | 308 MB |
| Idle-listening CPU (VAD) | 0.48% of one core | 0.45% |
| App memory right after start, English only (Nothing / realme) | 307 / 277 MB | 303 / 274 MB |

FP16 storage saves disk and download size, not RAM: the weights are float32 again once loaded. P1's `e968ad4` keeps **two** voices cached (for faster language switches), so after both voices have been used the app holds ~476–551 MB. That's a latency-for-RAM trade from that commit, not from these changes.

**Still to do:** listen to the FP16 voices next to the originals (the numbers say they're equivalent; a person should confirm), and choose each demo phone's pack.

## Where the 2.28 GB goes

| Part | Size | Share |
|---|---|---|
| STT, 9 Indic languages × 186 MB | 1,674 MB | 73% |
| STT, English | 68 MB | 3% |
| TTS voices (rasa 123, Piper hi 63, Piper en 64, MMS gu 114, MMS or 114) | 478 MB | 21% |
| espeak-ng-data (Piper support data) | 18 MB | 1% |
| APK | 38.5 MB | 2% |

## What to do, in order of impact

| # | Change | Saves | Speed | Accuracy | Effort |
|---|---|---|---|---|---|
| **1** | **Per-phone language pack:** copy STT only for the language(s) that phone's user speaks; keep **all** voices (a phone must speak whatever language arrives) | **−1.56 GB per phone** (2,277 → 721 MB) | same | same | P1: hide languages whose STT isn't installed. P2: a copy script per pack |
| **2** | **FP16 storage for the voices:** weights stored as float16, run as float32 (`work/fp16_storage.py`) | **−236 MB** (478 → 242) | **same** (phone: rasa RTF 0.60–0.65 vs 0.69–0.71) | same within run noise (Odia over 4 runs: CER 0.070 vs 0.085) | P2: convert + listen |
| **3** | **FP16 storage for STT's fp32 Conv weights** (the INT8 MatMul weights are untouched) | **−33 MB per Indic language** (186 → 153); −297 MB for all 9 | **same** (phone: RTF 0.152 vs 0.156) | **identical transcripts** on the phone (Hindi, 6 clips) | P2: convert + `verify_models.py` for all 9 |
| **4** | **Trim espeak-ng-data** to the Hindi + English dictionaries | −17 MB (18 → 1.0) | same | identical output (same durations, same CER) | P2: copy 8 files |
| **5** | **APK: drop 2 unused native libs** (`libsherpa-onnx-c-api.so`, `-cxx-api.so`) | ≈ −4.9 MB | same | same | P1: `packaging.jniLibs.excludes` (check it still loads) |
| 6 | APK: release build with R8 minify + shrinkResources (a clean debug build is 33.6 MB today) | not measured | same | same | P1 |

### Resulting sizes (models + APK)

| Scenario | Total |
|---|---|
| Today, all 10 languages | 2,277 MB |
| All 10 languages, changes 2–5 | **1,719 MB** |
| **Per-phone pack, 1 Indic language (e.g. Hindi or Tamil), changes 1–5** | **430 MB** |
| Per-phone pack, English only | 345 MB |
| **Per-phone default: Hindi + English + one Indic language** | **646 MB** |
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
