# iTantra

**SIH 2026 · PS 26173 · ISRO / Department of Space**
*Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links*

An offline Android app that lets two phones talk over a weak link. Phone A turns speech into text, only that small text travels (~60–100 bytes), and phone B speaks it aloud. It supports **10 languages**: Hindi, English, Bengali, Gujarati, Kannada, Malayalam, Marathi, Odia, Tamil and Telugu. It has push-to-talk, continuous and alert modes, over **Wi-Fi or Bluetooth**.

```
Phone A: mic → VAD → STT → ~60–100-byte frame ──Wi-Fi / Bluetooth──→ Phone B: frame → TTS → speaker
```

The 48-hour plan is in [`plan.md`](plan.md).

## What works (verified on two phones)

Tested on a **Nothing Phone (3a)** (Snapdragon 7s Gen 3) and a **realme RMX3031** (Dimensity 1200). Details: [`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md).

- **Push-to-talk and continuous mode:** exact transcripts over the air. The mic pauses while the phone speaks, and it didn't trigger on its own voice.
- **Receiving:** all 10 languages are spoken, even on a phone that has speech recognition for only two of them.
- **Connecting:**
  - **Wi-Fi discovery:** pick the other phone from a list, with no IP typing.
  - **Bluetooth:** pair, connect and reconnect, alerts and speech both ways.
  - Disconnect, reconnect, the peer dying and a wrong IP are all handled.
- **Alerts:** a 1-byte code, played in the **receiver's** language at full alarm volume. They cut off speech within 30 ms, and the volume is restored afterwards.
- **Corrupted frames** are dropped, never spoken (CRC-16).
- **Every language can be picked; missing ones download once.** A language that isn't installed downloads its 153 MB model from the [GitHub release](https://github.com/happypeepo/itantra/releases/tag/models-v2) with a size and SHA-256 check. That took ~26 s over Wi-Fi.
- **Size badge (top right):** total app size and live CPU and RAM. Tap it for a breakdown by component.
- **Memory** is released when switching languages, the mic is released when the app is in the background, and there were no crashes.

## Latest numbers

Measured on the **Nothing Phone (3a)**: STT at 2 threads, TTS at 4, sherpa-onnx 1.13.8, the shipped models. Generated in [`docs/RESULTS.md`](docs/RESULTS.md) (per-language details, laptop comparison and licences).

| Lang | STT RTF | TTS voice | First sound | **End-to-end (push-to-talk)** | Round-trip CER |
|---|---|---|---|---|---|
| Hindi | 0.151 | Piper | 0.28 s | **0.83 s** | 0.000 |
| English | 0.088 | Piper | 0.26 s | **0.71 s** | 0.016 |
| Bengali | 0.154 | rasa | 1.34 s | **3.02 s** | 0.011 |
| Gujarati | 0.147 | MMS | 1.14 s | **2.62 s** | 0.120 |
| Kannada | 0.161 | rasa | 1.76 s | **4.22 s** | 0.062 |
| Malayalam | 0.173 | rasa | 1.77 s | **4.21 s** | 0.008 |
| Marathi | 0.144 | rasa | 1.60 s | **3.15 s** | 0.033 |
| Odia | 0.162 | MMS | 1.39 s | **3.27 s** | 0.116 |
| Tamil | 0.161 | rasa | 1.53 s | **3.43 s** | 0.041 |
| Telugu | 0.171 | rasa | 1.52 s | **3.58 s** | 0.000 |

- **End-to-end** means from releasing push-to-talk to the other phone starting to speak, for a 4–5 s sentence. The network isn't included; it adds a few ms on Wi-Fi.
- **Round-trip CER** is our TTS feeding our STT (6 clips per language). **It's not real-speech accuracy, which hasn't been measured yet.** All 10 languages pass `verify_models.py` on the shipped models.

| | |
|---|---|
| **App size per phone** | **651 MB**, as the app's badge shows: APK 33.6 MB + models 612 MB (Hindi + English + 1 Indic language + every voice) + alert sounds 5.9 MB. It was ~2.28 GB before the size work |
| Each extra language | 153 MB download, once |
| All 10 languages | 1.72 GB |
| RAM | ~300 MB idle (English loaded); ~450–610 MB with a recognizer and voices in use |
| Idle CPU, continuous listening | 0.45% of one core |
| Link | a 4–5 s sentence is 59–95 bytes (≈120–160 bps); an alert is 9 bytes. Ping: Wi-Fi 8–111 ms, Bluetooth 43–278 ms |

## Important points

- **Offline by design.** Once a language is installed, nothing needs the internet. Only downloading a new language does, and only once.
- **No translation.** The receiver hears the **sender's** language. So every phone needs **every voice** (242 MB), but only its own user's **speech recognition** (153 MB per language). Alerts are the exception: they play in the receiver's language.
- **Every phone ships with Hindi + English + one Indic language** (`work/make_pack.py --indic <lang>`). Our phones: Nothing = Marathi, realme = Tamil. The other 7 languages download from the app.
- **The voice (TTS) is the latency bottleneck.** Speech recognition takes under 0.5 s for a 5 s sentence. Hindi and English (Piper) are under 1 s end to end. rasa and MMS take ~1.1–1.8 s just to start speaking; the app splits messages at commas so playback starts earlier.
- **Size work** ([`docs/SIZE_REDUCTION.md`](docs/SIZE_REDUCTION.md)):
  - model weights stored as FP16 and cast back to FP32 when loaded;
  - Piper's pronunciation data trimmed to Hindi and English;
  - per-phone language packs;
  - two unused native libraries dropped from the APK.
  - **Accuracy, speed and RAM are unchanged**, measured before and after on the same phone.
  - **Rejected, with measurements:** INT8 voices (4–5× slower), INT4 speech recognition (slower on the phone), Conv-INT8 speech recognition (3× slower).
- **Licences to state on the slide:**
  - The MMS Gujarati and Odia voices are **CC-BY-NC-4.0 (non-commercial)**. No permissive on-device voice exists for them.
  - The Hindi Piper voice uses the IIT Madras licence (**not yet read**) and was fine-tuned from a research-only-data voice.
  - The rest: IndicConformer (MIT), rasa (CC-BY-4.0), Piper English (public domain), sherpa-onnx (Apache-2.0), silero VAD (MIT).

## Models

| | Model | Licence |
|---|---|---|
| Runtime | sherpa-onnx 1.13.8 (Android AAR + Python) | Apache-2.0 |
| VAD | silero-VAD | MIT |
| STT, 9 Indic | AI4Bharat IndicConformer 120M CTC (OpenVoiceOS export), MatMul-only INT8 + FP16 storage, 153 MB each | MIT |
| STT, English | NeMo conformer CTC medium (sherpa-onnx export), 62 MB | see model card |
| TTS, bn kn ml mr ta te | rasa (`ai4bharat/vits_rasa_13`), style 10 NEWS, 62 MB | CC-BY-4.0 |
| TTS, hi / en | Piper `hi_IN-rohan-medium` / `en_US-ljspeech-medium`, 32 MB each | IITM licence / public domain |
| TTS, gu / or | MMS `guj` / `ory`, 58 MB each | **CC-BY-NC-4.0** |

## Repository

```
android/            the app (Kotlin, Material 3; P1). Build: android/README.md
p2-models/          manifest.json (every model, wire IDs, downloads), alerts.json, model scripts, runbook
link/               wire format spec + test vectors (used by the app's unit tests)
models/README.md    model folder layout, sizes, packs (the models themselves are not in git)
work/               build_models_v2.py · fp16_storage.py · make_pack.py · phone_bench.py · make_results.py · fake_peer.py
docs/               RESULTS.md (numbers) · DEVICE_TEST.md (what was verified) · SIZE_REDUCTION.md (size decisions)
bench_out/ phone_out/ verify_out/   the result JSONs behind the numbers above
real_audio/         how to record real-speech test clips (not done yet)
```

## Quick start

```bash
# 1. App (needs JDK 17-21 and Android SDK 36)
cd android && ./scripts/fetch-runtime.sh && ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk && cd ..

# 2. Models: download + patch per p2-models/README.md §4-5, then shrink and pack
python3 -m venv .venv && . .venv/bin/activate
pip install "sherpa-onnx==1.13.8" onnx onnxruntime soundfile numpy huggingface_hub
python work/build_models_v2.py --src <full-size models> --out models
python p2-models/scripts/verify_models.py --models models       # all 10 should PASS
python work/make_pack.py --indic ta                              # Hindi + English + Tamil

# 3. Put the pack on the phone (open the app once first)
adb push packs/hi-en-ta/models/. /sdcard/Android/data/org.itantra.app/files/models/
```

**Tools in `work/`:**
- `fake_peer.py`: the laptop plays the second phone over `adb forward tcp:26173 tcp:26173`. It sends speech in any language, a bad CRC or alerts, and prints what the phone sends back.
- `phone_bench.py`: benchmarks the models **on the phone** with sherpa-onnx's official Android CLI tools. Push `sherpa-onnx-v1.13.8-android-aarch64-termux-static` `bin/` plus the NDK's `libc++_shared.so` to `/data/local/tmp/itantra/bin`, the models to `.../models`, and the clips made by `p2-models/scripts/benchmark.py run` to `.../audio`. Then run `SERIAL=<device> python work/phone_bench.py --no-int4`.
- `make_results.py`: regenerates `docs/RESULTS.md` from the result JSONs.

## Still open

- **Real-speech accuracy:** needs recordings (`real_audio/README.md`).
- **Native-speaker check** of `p2-models/alerts.json`; then render the final alerts.
- **Listening check** of the FP16-storage voices.
- **Push-to-talk in all 10 languages over the air.**
- **Two phones over a phone hotspot.**
- **Android declines the low-latency audio mode.** Resampling to 48 kHz could save ~0.25 s per message.
