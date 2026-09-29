# iTantra

**SIH 2026 · PS 26173 · ISRO / Department of Space**
*Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links*

An offline Android app that lets two phones talk over a weak link. Speech is turned into text on phone A, only the tiny text is sent, and phone B speaks it out loud. It covers 10 languages: Hindi, English, Bengali, Gujarati, Kannada, Malayalam, Marathi, Odia, Tamil and Telugu. It has push-to-talk, continuous and alert modes.

```
Phone A: mic → VAD → STT → ~20–60-byte frame ──Wi-Fi──→ Phone B: frame → TTS → speaker
```

The full plan (roles, timeline, fallbacks, demo script) is in **[`plan.md`](plan.md)**.

## Repository layout

```
itantra/
  plan.md              the 48-hour plan
  p2-models/           P2: which model each language uses, scripts, Kotlin loaders (start with its README)
    manifest.json      single source of truth: STT/TTS model + wire_id per language
    alerts.json        5 alert phrases × 10 languages (DRAFT, needs native check)
    scripts/           patch, verify, benchmark, pipeline demo, frame format, alert rendering
    android/           SpeechFactory.kt + TextSanitizer.kt for P1
  android/             P1: the Android app (Kotlin, sherpa-onnx 1.13.8)
  link/                P3: link frame spec + test vectors for the Kotlin implementation
  docs/                results, licences, slides, demo script
  models/              built model folder, NOT in git (1.7 GB for all 10; per-phone packs ~400-460 MB; see models/README.md)
  real_audio/          real recordings for accuracy (WAVs stay local; see its README)
  work/                P2 measurement scripts (T4 rasa, T5 INT8 vs INT4, STT re-make)
  verify_out*/ bench_out/ t4_out/   result JSONs (audio not in git)
```

| Who | Owns | Start here |
|---|---|---|
| **P1: App** | Android app, mic, VAD, PTT, continuous mode, playback, alerts, UI | [`android/README.md`](android/README.md) |
| **P2: Models** | every model working in every language, measured | [`p2-models/README.md`](p2-models/README.md) |
| **P3: Link + Demo** | two-phone connection, frame format, metrics, slides, demo | [`link/README.md`](link/README.md) |

## Quick start (laptop, P2)

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install "sherpa-onnx==1.13.8" onnx onnxruntime onnx_ir soundfile numpy huggingface_hub
# download + build models/ : p2-models/README.md §4–§5
python p2-models/scripts/verify_models.py --models models        # all 10 should PASS
```

## Model stack (final, measured)

| | Choice | Licence |
|---|---|---|
| Runtime | sherpa-onnx 1.13.8 (Python and Android) | Apache-2.0 |
| VAD | silero-VAD | MIT |
| STT, 9 Indic languages | AI4Bharat IndicConformer 120M CTC, from `OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx`, MatMul-only INT8 via `patch_stt.py` | MIT |
| STT, English | NeMo English conformer CTC medium (sherpa-onnx export) | see model card |
| TTS, bn kn ml mr ta te | rasa (`MatiasLin/sherpa-onnx-vits-rasa-13`, weights `ai4bharat/vits_rasa_13`) | CC-BY-4.0 |
| TTS, hi | Piper `hi_IN-rohan-medium` | IIT Madras Indic TTS licence: **to be read**; fine-tuned from `lessac` |
| TTS, en | Piper `en_US-ljspeech-medium` | public domain |
| TTS, gu or | MMS `guj`, `ory` | **CC-BY-NC-4.0 (non-commercial)** |

No models are redistributed in this repo. Scripts download them from the original sources.

## Status (P2)

| Task | Status |
|---|---|
| T0 environment · T1 kit docs · T2 `models/` built · T3 all 10 languages pass `verify_models.py` | ✅ done |
| T4 rasa style | done: **style 10 (NEWS)** for all six rasa languages, picked by listening |
| T5 INT8 vs INT4 | done, **checked on the phone**: keep MatMul-only INT8 (INT4 was ~50% slower on the phone, only ~50 MB less RAM) |
| T6 real recordings | **waiting for recordings** in `real_audio/` |
| T7 benchmark | done on laptop (Apple M4) **and on a Nothing Phone (3a)** (Snapdragon 7s Gen 3): `phone_out/phone_results.json` |
| T8 alerts | **waiting for native-speaker check** of `alerts.json` |
| T10 results | **[`docs/RESULTS.md`](docs/RESULTS.md)**: every number per language (phone + laptop); real-speech accuracy still missing |
| T9 end-to-end · T11 plan update | to do |

Headline **phone** numbers (Nothing Phone (3a), Snapdragon 7s Gen 3; STT 2 threads, TTS 4 threads). **Per-language details are in [`docs/RESULTS.md`](docs/RESULTS.md).**
- **STT:** RTF 0.10–0.20. A 4–5 s message is recognized in 0.2–0.5 s. RAM ~430–480 MB (English 270 MB).
- **TTS time to first audio (avg):** Piper 0.25–0.31 s · MMS 1.1–1.5 s · rasa 1.5–1.9 s.
- **Estimated end-to-end (push-to-talk, ~4–5 s sentence):** hi 0.83 s · en 0.71 s · gu 2.62 s · or 3.27 s · rasa languages 3.0–4.2 s. Splitting at commas brought Tamil's first audio down to ~1.6 s at 6 threads.
- **Idle VAD:** ~0.45% of one core. **Size:** 435 MB per phone (one-language pack + APK), 1.72 GB with all 10 languages ([`docs/SIZE_REDUCTION.md`](docs/SIZE_REDUCTION.md)).
- **Round-trip CER:** 0.00–0.12. Real-speech accuracy isn't measured yet.
