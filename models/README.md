# models/ (not in git)

The model files are **not committed**. There are ~2.1 GB of them, most are over GitHub's 100 MB file limit, and they're other people's published weights. Rebuild them from the original sources with `p2-models/README.md` §4–§5.

## Layout (exactly what `manifest.json` expects)

```
models/
  manifest.json                  copy of p2-models/manifest.json (the app reads it from here)
  vad/silero_vad.onnx
  stt/<lang>/model.int8.onnx     hi en bn gu kn ml mr or ta te
  stt/<lang>/tokens.txt
  tts/rasa/model.onnx, tokens.txt          bn kn ml mr ta te (24,000 Hz)
  tts/piper-hi/model.onnx, tokens.txt      hi (22,050 Hz)
  tts/piper-en/model.onnx, tokens.txt      en (22,050 Hz)
  tts/espeak-ng-data/                      shared by both Piper voices; must be a real folder on disk
  tts/mms-gu/model.onnx, tokens.txt        gu (16,000 Hz)
  tts/mms-or/model.onnx, tokens.txt        or (16,000 Hz)
```

## Sizes (measured 2026-09-30, after the size-reduction changes)

Built by `work/build_models_v2.py`: every float32 weight is stored as float16 and cast back to float32 when ONNX Runtime loads the model (same speed and accuracy, see `docs/SIZE_REDUCTION.md`), and espeak-ng-data is trimmed to Hindi + English. The previous full-size set is kept outside git as `dl/models_v1_full/`.

| Part | Size |
|---|---|
| STT, 9 Indic languages (IndicConformer, MatMul-only INT8 + FP16 storage) | 153 MB each |
| STT, English (NeMo conformer medium INT8 + FP16 storage) | 62 MB |
| TTS rasa / Piper hi / Piper en / MMS gu / MMS or | 62 / 32 / 32 / 58 / 58 MB |
| espeak-ng-data (hi + en only) | 1 MB |
| VAD (silero) | 0.6 MB |
| **All 10 languages** | **1,685 MB** (was 2,238) |

## Per-phone language packs (recommended)

A phone needs STT only for the language(s) its user speaks, but **every voice** (incoming speech is spoken in the sender's language). The app lists only the speech languages whose STT is installed.

```bash
python work/make_pack.py --langs hi,en        # -> packs/hi-en/models (459 MB)
python work/make_pack.py --langs ta           # -> packs/ta/models    (396 MB)
```

| Pack | Size |
|---|---|
| one Indic language | 396 MB |
| English only | 305 MB |
| two languages (e.g. hi + en) | 459 MB |

## Putting them on a phone

```bash
PKG=org.itantra.app
adb shell mkdir -p /sdcard/Android/data/$PKG/files/models
adb push packs/hi-en/models/. /sdcard/Android/data/$PKG/files/models/   # or models/. for all 10
```

Then check them with `python p2-models/scripts/verify_models.py --models models`. All 10 languages should PASS.
