# iTantra — 48-Hour Build Plan

**SIH 2026 · PS 26173 · ISRO / Department of Space**
*Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links*

PS reference: https://sih2026.vuce.in/ps/SIH26173

> **Updated:** model choices corrected in §3, §6B, §7, and §8. The old STT source was broken, and the Indic TTS model doesn't cover Hindi, Gujarati, or Odia. Full model details are in `p2-models/README.md`.

**Team:** 3 people · **Time:** 48 hours · **Rule:** no training, no research, no experiments. Only ready-made, open-source models, put together into one working app.

---

## 0. The whole plan in one sentence

Build a Kotlin Android app on **sherpa-onnx**, use **ready-made models** for speech-to-text and text-to-speech in all 10 languages, send text between two phones over **Wi-Fi**, and spend the last 10 hours measuring and rehearsing — not adding features.

---

## 1. Handoff — what the problem statement asks for

An Android app that lets two phones talk to each other **offline**, even over a weak link. Speech is turned into text on one phone, the small text is sent, and the other phone speaks it out loud.

```
Phone A                                    Phone B
  mic
   ↓
  VAD (detects when you stop talking)
   ↓
  STT (speech → text), on the phone
   ↓
  small binary message
   ↓
  Wi-Fi  ─────────────────────────────→   message received
                                              ↓
                                            TTS (text → speech), on the phone
                                              ↓
                                            speaker
```

**Languages — exactly 10:** Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English.

**Modes required:** push-to-talk (walkie-talkie), continuous (phone-like), and alerts that play at max volume and can't be interrupted.

**Hard rules:** fully offline · open-source only · must run on low/mid-range Android phones.

**How it's scored:**

| Weight | What |
|---|---|
| 40% | Accuracy — STT word error rate + how clear the TTS sounds to people |
| 20% | Latency — how fast STT, TTS, and the full phone-to-phone trip are |
| 20% | Efficiency — model size, app size, RAM, CPU while idle-listening |
| 20% | Deployment and overall system quality |

**What this means for 48 hours:** a working two-phone demo with real numbers beats anything clever. If the pipeline doesn't run end to end, most of the score is gone.

---

## 2. Scope

### P0 — must ship (the PS requires these)

- Push-to-talk, phone A → phone B, all 10 languages
- Continuous mode (VAD sends automatically when you stop talking)
- Alerts: max volume, can't be interrupted
- Works with no internet
- Basic timing numbers logged for every message

### P1 — should ship

- Metrics screen in the app (latency, RTF, bytes sent)
- Binary message format with a checksum (CRC)
- Alerts that play in the **receiver's** language (explained in §6C — this is nearly free and a great demo moment)
- INT8 (smaller) STT models

### P2 — only if everything above is done

- Smaller text encoding for Indic scripts, with a bits-per-second number on screen
- Auto-discovery of the other phone (no typing IP)
- Bluetooth as a backup link
- Round-trip test: TTS output fed back into STT, error rate measured

### Cut — do not start these

Model training or fine-tuning · distillation · SSM work · streaming student model · download-on-demand (we copy models onto the phone with a cable instead) · arithmetic coding · phrase codebook beyond alerts · auto language detection · transliteration of mixed-script text · foreground service polish.

These go on a **"Future work" slide**. They show judges you understand the problem deeply, without costing build time.

---

## 3. Final stack — no alternatives, decisions are made

| Part | Choice | Notes |
|---|---|---|
| App | Kotlin, native Android | |
| Speech runtime | **sherpa-onnx ≥ v1.13.5** release AAR | Apache-2.0. Does STT, TTS, and VAD in one library. v1.13.5 includes the `emotion_id` support the Indic TTS model needs. |
| VAD | silero-VAD (through sherpa-onnx) | Detects start and end of speech |
| STT — 9 Indic languages | **IndicConformer 120M CTC ONNX**, one model per language: `mobilebytesensei/betterflow-indicconformer-ctc` for gu, bn, mr, ml, te, ta, kn (ready to use); `OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx` for hi, or (patched with P2's script) | Best Indic accuracy available. MIT. **Don't use `trysem/indicconformer-120m-onnx`** — another team found every folder holds the same Assamese model. |
| STT — English | **NeMo English conformer CTC** (official sherpa-onnx export) | Same kind of model as the Indic ones, so one code path for all 10. Whisper base.en as fallback. |
| TTS — 6 Indic languages | **`MatiasLin/sherpa-onnx-vits-rasa-13`** for bn, kn, ml, mr, ta, te | One model, ~150 MB, CC-BY-4.0. **Does not cover Hindi, Gujarati, or Odia.** |
| TTS — Hindi | **Piper `hi_IN-rohan-medium`** (official sherpa-onnx export) | Check its IIT Madras licence |
| TTS — Gujarati, Odia | **MMS** `guj`, `ory` | **CC-BY-NC-4.0** (non-commercial). No permissive on-device voice exists for these two. |
| TTS — English | **Piper `en_US-ljspeech-medium`** (official sherpa-onnx export) | Public domain |
| Link | TCP socket over one phone's hotspot | Most reliable option. A shared router can silently block phones from reaching each other. |
| Alerts | Pre-made WAV files, played on the alarm audio stream | No TTS at alert time |

**Starting point:** copy sherpa-onnx's own Android example apps (the VAD+ASR one and the TTS one) and change them. Do not write audio handling from scratch.

---

## 4. Roles

| Person | Owns | In short |
|---|---|---|
| **P1 — App** | Android app, mic, VAD, PTT, continuous mode, audio playback, alerts, UI | "Everything that runs on the phone screen and speaker" |
| **P2 — Models** | Getting every model ready and loading in the app, language switching, alert audio files | "Every model works, in every language" |
| **P3 — Link + Demo** | Two-phone connection, message format, metrics, measurements, slides, demo script | "The phones talk, and we can prove how well" |

P2 does most model prep on a laptop in the first hours, then moves into the app with P1.

---

## 5. Timeline

### Milestones and gates

| Hour | Milestone | Gate |
|---|---|---|
| **H0–2** | Setup: sherpa-onnx example app builds and runs on both phones. All models on the laptop. | — |
| **H6** | **M0.** App shows English speech as text on screen. Two phones send text to each other. All 10 STT models + TTS load and work in Python on the laptop. | If IndicConformer won't load by H6 → **use fallback F1** (§7) |
| **H14** | **M1 — the big one.** Hindi speech on phone A → Hindi audio out of phone B, push-to-talk. | If M1 is missed → everyone stops their own work and fixes the blocker. Continuous mode moves to after M2. |
| **H24** | **M2.** All 10 languages working. Alerts working at max volume. | — |
| **H32** | **M3.** Continuous mode. Metrics screen. Binary message format. | — |
| **H38** | **FEATURE FREEZE.** Nothing new after this. Measurements captured. | Hard stop |
| **H38–44** | Bug fixing, 3 full demo rehearsals, slides done | — |
| **H44–48** | Buffer. Rest. Only fix crashes. | — |

### Sleep

Plan it now, or the last 10 hours fall apart.

- Everyone awake during integration windows: **H10–14, H30–34, H40–46**.
- Main sleep, one person at a time: **P3: H15–20 · P2: H20–25 · P1: H25–30**.
- One short nap each (~2 h), staggered between H34–40.
- Whoever presents the demo gets the most sleep before it.

---

## 6. Work details

### 6A — App (P1)

1. **Start from the sherpa-onnx VAD+ASR Android example.** Get it running on both phones with its default English model before changing anything.
2. **Load models from phone storage, not from inside the APK.** Push them with `adb push` into the app's own folder (`/sdcard/Android/data/<package>/files/models/`). This keeps the APK small, and APK size is scored.
3. **Mic:** `AudioRecord`, 16 kHz, mono. VAD cuts speech into sentences.
4. **Push-to-talk:** hold the button to record; on release, run STT and send.
5. **Continuous mode:** VAD always on; each time you stop talking, the sentence is sent automatically. **Turn the mic off while the phone is speaking**, or the phone will hear itself and loop.
6. **Playback queue:** incoming speech plays in order. An alert **jumps the queue** and stops normal speech.
7. **Alerts:** play pre-made WAVs with `AudioTrack` on `STREAM_ALARM`. Request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` so nothing can lower its volume. Set the alarm volume to max in code, don't trust the user's setting. Test this with the phone on silent in H0–2.
8. **UI:** language picker, big PTT button, mode switch, alert buttons, message log, connection status. Plain is fine — judges score the system, not the look.
9. Keep the screen on during the demo. No background service work.

### 6B — Models (P2)

**Full step-by-step instructions, scripts, and Android code are in the `p2-models/` kit** (start with its README). The short version:

**Before H6, all on the laptop, using Python sherpa-onnx:**

**STT:**
1. Copy the 7 ready-made languages from `betterflow` (gu, bn, mr, ml, te, ta, kn).
2. Patch Hindi and Odia from OpenVoiceOS with `patch_stt.py`. It adds the settings sherpa-onnx needs inside the model file and makes the INT8 copy.
3. **INT8 only on MatMul layers.** The default `quantize_dynamic` settings also shrink Conv layers, which can make the model output empty text with no error. The script does this correctly.
4. English: the official sherpa-onnx NeMo English conformer.

**TTS:**
1. rasa for bn, kn, ml, mr, ta, te. **Only voice IDs 0–19 are real** (the model's settings say 1024, but the model card lists 20). Use the voice that matches the language — a Bengali voice reading Tamil has a foreign accent. The voice for each language is already set in `manifest.json`.
2. Speaking style: try 0 (ALEXA), 4 (CONV), 10 (NEWS) and pick the clearest. **Timebox: 30 minutes.** If unsure, use 0.
3. Piper for Hindi and English, MMS for Gujarati and Odia.
4. Keep TTS in **FP32**. Shrinking the audio-making part of TTS can add audible noise, and there's no time to test it properly.

**Test every language with `verify_models.py`.** It turns each alert phrase into speech and back into text and compares — no recordings or native speakers needed. It also catches the silent failures: empty STT output, wrong-script output from a mislabeled model, identical model files, and sample-rate mismatches. **This is gate M0.**

**Clean the text before TTS** (done by `TextSanitizer.kt` in the app):
- **Remove the last `.` `?` `!` `।` or `॥`**. sherpa-onnx otherwise can make a tiny empty extra sentence that plays as noise.
- **For rasa and MMS, drop any character the voice doesn't know**, so it can't garble the audio.

Note: the Indic STT models only output their own script (the Hindi model writes "एयरपोर्ट", not "airport"), so mixed Hindi-English text mostly never reaches TTS. The filter handles any leftovers.

**Alert audio files:**
1. Draft phrases for 5 alerts in all 10 languages are in `alerts.json`. **Have a native speaker check each one.**
2. Render all 50 with `render_alerts.py`: two warning beeps, then the phrase, at max loudness. Give the folder to P1 (about 5 MB, can go inside the APK).

**After H6:** join P1. `SpeechFactory.kt` loads models from `manifest.json`. **Playback must use each result's own sample rate** — rasa is 24,000 Hz, Piper 22,050 Hz, MMS 16,000 Hz.

### 6C — Link, metrics, demo (P3)

**Connection (P0):**
- One phone taps **Host**: opens a TCP server on a fixed port and shows its IP on screen.
- The other taps **Join**: types that IP.
- Test on the phone hotspot with **mobile data off** in H0–2. If the phones can't reach each other, swap which phone hosts. A shared router is a last resort — many routers silently block devices from talking to each other.

**Message format (P1):** a tiny binary frame instead of JSON — it keeps the "low bandwidth" story honest.

```
[type: 1 byte][language: 1 byte][sequence: 2 bytes][length: 2 bytes][payload][CRC16: 2 bytes]

type:     0x01 speech   0x02 alert   0x03 ping
payload:  speech → the text   alert → 1 byte alert ID
```

If the CRC doesn't match, drop the message and show it in the log.

**Cross-language alerts (P1):** an alert sends only a **1-byte ID**, not words. The receiver plays that alert **in its own chosen language**. So phone A (Hindi) sends "Evacuate", and phone B (set to Tamil) hears it in Tamil. This costs almost nothing, because the alert files are pre-made anyway — and it's the best moment in the demo.

**Metrics (P0 logging, P1 screen):**

| Where | Measure |
|---|---|
| Sender | speech end → STT done (ms), STT RTF, bytes sent |
| Receiver | message received → first sound (ms), TTS RTF |
| Both | ping round-trip time |

**End-to-end latency** = sender time + half the ping time + receiver time-to-first-sound. This avoids needing the two phone clocks to match.

**P2 — smaller Indic text encoding:** UTF-8 uses 3 bytes per Indic letter. Each Indic script block has about 128 characters, so each letter fits in 1 byte (letter code minus the script's start code). Send a script ID once, then 1 byte per letter. About 3× smaller, lossless, ~30 lines of Kotlin. Show "UTF-8 bits vs our bits" on the metrics screen.

**Example to put on a slide:** "मुझे मुंबई एयरपोर्ट जाना है" is about 82 bytes in UTF-8, about 260 bits per second when spoken. The same sentence in English is about 96 bits per second. Indic text costs about 3× more to send, only because of how it's encoded.

---

## 7. Fallbacks — decided in advance

Don't debate these at 3 a.m. If the trigger happens, switch.

| ID | Trigger | Switch to |
|---|---|---|
| **F1** | IndicConformer not loading by **H6** | Official sherpa-onnx **Whisper small** (multilingual) for all languages. Say honestly in the demo that Odia is weak. P2 retries IndicConformer only if idle later. |
| **F2** | A language's TTS voice not working by **H6** | For **that language only**, use the open-source **eSpeak NG** Android app as the phone's TTS engine, through Android's `TextToSpeech` API. Sounds robotic, but it's clear, offline, and covers all 10 languages. Also the option if you decide against non-commercial voices for Gujarati and Odia. |
| **F3** | INT8 STT gives wrong text for a language | Use that language's full-size (FP32) model — `patch_stt.py` keeps a copy on the laptop. About 490 MB per language instead of ~140 MB. |
| **F4** | Phones can't reach each other on hotspot | Swap which phone hosts. If that fails, try the spare router with **no internet cable** plugged in — but test it in H0–2, since many routers block phone-to-phone traffic. |
| **F5** | M1 not done by **H14** | Everyone works on the blocker. Continuous mode moves after M2. |
| **F6** | Continuous mode unstable at **H32** | Demo push-to-talk as the main mode; show continuous mode briefly. |

---

## 8. Before the clock starts (or H0–2 at the latest)

**Download everything ahead of time if the rules allow.** The full set is a few GB. Hackathon Wi-Fi will not handle that. Bring it on a USB drive too. Exact download commands are in `p2-models/README.md` §4.

- [ ] sherpa-onnx ≥ v1.13.5 Android AAR + source (for the example apps)
- [ ] STT: `mobilebytesensei/betterflow-indicconformer-ctc` (7 languages), `OpenVoiceOS/ai4bharat-indicconformer-hi-onnx` and `-or-onnx`, sherpa-onnx `nemo-ctc-en-conformer-medium`
- [ ] TTS: `MatiasLin/sherpa-onnx-vits-rasa-13`, Piper `hi_IN-rohan-medium` and `en_US-ljspeech-medium` (sherpa-onnx exports), MMS `guj` and `ory`
- [ ] silero-VAD
- [ ] Fallbacks: Whisper small + Whisper base.en (sherpa-onnx exports), eSpeak NG APK
- [ ] **Licence check:** rasa is CC-BY-4.0 (confirmed). MMS is CC-BY-NC-4.0 (non-commercial). Read the IIT Madras licence for the Hindi Piper voice. Decide: keep MMS for Gujarati and Odia, or use eSpeak NG. Write it all on one slide.
- [ ] Two Android phones, arm64, 4 GB+ RAM, ~6 GB free storage. **Use the weaker phone as the "target device" for all numbers.**
- [ ] USB cables for both phones, a spare Wi-Fi router, a power strip
- [ ] A native speaker to check the draft alert phrases in `alerts.json`

---

## 9. Numbers to capture (H34–38, on the weaker phone)

Record these in one table for the slides.

| Metric | How |
|---|---|
| STT latency, per language | median of 5 sentences |
| STT RTF, per language | from app logs |
| TTS time-to-first-sound + RTF | from app logs |
| End-to-end latency | formula in §6C |
| APK size | build output |
| Model sizes | `du -h` on the models folder |
| RAM while running | `adb shell dumpsys meminfo <package>` |
| Idle CPU in continuous mode | `adb shell top`, 10 minutes of silence |
| Bytes per message, bits per second | metrics screen (UTF-8, and encoded if P2 is done) |
| Accuracy | **Cite the published IndicConformer WER numbers** and say clearly they're published, not measured by us. Add the round-trip test if P2 is done. |

Be honest on the slides about what was measured versus quoted.

---

## 10. Demo script (~4 minutes)

1. **Offline proof:** mobile data off on both phones. Open a browser — it fails to load. Connect the phones.
2. **Push-to-talk, Hindi:** speak on A, B speaks it back. Show the metrics screen.
3. **Switch language** to Tamil or Bengali, repeat.
4. **Continuous mode:** talk naturally for a few sentences.
5. **Alert:** put B on low volume. A (Hindi) sends "Evacuate". B plays it **at full volume, in B's language**, cutting off normal speech.
6. **Numbers slide:** latency, sizes, RAM, CPU, bits per second.

Rehearse this 3 times. Have a screen recording of a working run as backup in case the venue Wi-Fi or a phone misbehaves.

---

## 11. Likely judge questions

**Why not Whisper?** It always processes a fixed 30-second window, so a 2-second message costs as much as a 30-second one. It's weak on Odia. It can make up text during silence.

**Why didn't you train your own model?** 48 hours. We used the strongest open Indic models available and spent the time on a complete, measured system. Our next step is on the Future work slide: shrinking a large teacher model into a small streaming one.

**Why not IndicF5, Indic Parler-TTS, or rumik-oss-1?** Too big for a phone (0.4B, 0.9B, 3B). IndicF5 has no English. rumik-oss-1 needs an NVIDIA GPU, generates audio step by step (slow), and its licence doesn't allow deployments like this.

**Is everything open source?** Every model is openly published, and the code is all open source. Two voices (Gujarati and Odia) have a non-commercial licence, because no permissively licensed voice for those languages runs on a phone. Say this plainly and show the licence slide.

**What if the link is noisy?** Every message has a checksum. Bad messages are dropped and shown in the log, never spoken wrong.

**Does it work without internet?** Show step 1 of the demo.

### Future work slide

- Shrink the 600M IndicConformer into one small **streaming** model for all 10 languages
- Test an SSM-based speech model against it
- Smaller text encoding: 260 → ~85 → ~24 bits per second, plus 2-byte standard phrases
- Download languages on demand instead of copying them by cable
- Bluetooth link, auto-discovery

---

## 12. Open questions

1. **Can we download models and set up before the 48 hours start?** Changes how H0–2 goes.
2. **Which exact phones do we have?** Fixes the "target device" for every number.
3. **Do we have native speakers** for any of Gujarati, Kannada, Malayalam, Odia, Telugu, Bengali — to check alert phrases and pick TTS voices?
4. **Is the demo room Wi-Fi-only, or can we bring our own router?**
