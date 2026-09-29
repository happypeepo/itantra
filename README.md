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
  models/              built model folder, NOT in git (~2.1 GB; see models/README.md)
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
| T4 rasa style | measured; **waiting for a listening pick** (0 / 4 / 10) |
| T5 INT8 vs INT4 | done: keep MatMul-only INT8; INT4 pending an on-phone check |
| T6 real recordings | **waiting for recordings** in `real_audio/` |
| T7 benchmark | done on laptop (Apple M4, 2 threads); phone numbers pending |
| T8 alerts | **waiting for native-speaker check** of `alerts.json` |
| T9 end-to-end · T10 `docs/RESULTS.md` · T11 plan update | to do |

Headline laptop numbers (Apple M4, 2 threads, **not phone numbers**):
- **STT:** RTF ~0.03 for all 10 languages.
- **TTS time to first audio:** Piper ~0.15 s, MMS 0.9–1.1 s, rasa 1.4–1.7 s (up to ~4 s for a long sentence).
- **Idle VAD:** 0.46% of one core.
- **Round-trip CER:** 0.00–0.13 across the 10 languages. Real-speech accuracy isn't measured yet.
