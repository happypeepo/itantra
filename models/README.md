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

## Sizes (measured 2026-09-29)

| Part | Size |
|---|---|
| STT, 9 Indic languages (OpenVoiceOS IndicConformer, MatMul-only INT8) | 186 MB each |
| STT, English (NeMo conformer medium INT8) | 68 MB |
| TTS rasa / Piper hi / Piper en / MMS gu / MMS or | 123 / 63 / 64 / 114 / 114 MB |
| espeak-ng-data | 18 MB |
| VAD (silero) | 0.6 MB |
| **Total** | **~2.1 GB** |

## Putting them on a phone

```bash
PKG=com.your.app.package        # from P1
adb shell mkdir -p /sdcard/Android/data/$PKG/files/models
adb push models/. /sdcard/Android/data/$PKG/files/models/
```

Then check them with `python p2-models/scripts/verify_models.py --models models`. All 10 languages should PASS.
