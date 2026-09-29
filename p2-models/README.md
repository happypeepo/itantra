# P2 — Models: your part, in depth

**Your job in one line:** every one of the 10 languages can turn speech into text and text into speech, on the phone, offline — and you can prove it.

**What you hand over, and when:**

| By | You deliver | To |
|---|---|---|
| H1 | `manifest.json` + `android/SpeechFactory.kt` + `android/TextSanitizer.kt` | P1 (they can start coding against these before models exist) |
| H1 | the `wire_id` for each language (in `manifest.json`) | P3 (it's the language byte in their message frame) |
| H6 | all 10 languages passing `verify_models.py` on the laptop | everyone (this is gate **M0**) |
| H14 | Hindi STT + TTS working inside the app | everyone (gate **M1**) |
| H24 | all 10 languages working in the app + alert WAV files | P1 |
| H38 | model size table + licence slide | P3 |

---

## 1. Three corrections to plan.md — read these first

I checked the actual model pages and source code this time. Three things in `plan.md` were wrong:

**1. Don't use `trysem/indicconformer-120m-onnx`.** Another team building this same PS found that every language folder in it contains the same (Assamese) model. Use these instead:
- **`OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx`** for all 9 Indic languages, patched by us (see §5). MIT licence. (Earlier the plan used `mobilebytesensei/betterflow-indicconformer-ctc` for 7 of them. It works, but it's ~3× slower; it's now the fallback.)

**2. The rasa TTS model covers 6 of your 9 Indic languages, not all 9.** I told you it covered all nine — it doesn't. Its language list is Assamese, Bengali, Bodo, Dogri, Kannada, Maithili, Malayalam, Marathi, Nepali, Punjabi, Sanskrit, Tamil, Telugu. **No Hindi, Gujarati, or Odia.** So:
- Hindi → a Piper Hindi voice
- Gujarati, Odia → Meta's MMS voices (these have a non-commercial licence — see §2)

Also: the model's settings say 1024 speakers, but the model card only lists **20 real ones (IDs 0–19)**. Other IDs may sound broken.

**3. INT8: only shrink MatMul layers.** The default `quantize_dynamic` settings also shrink Conv layers, and for this model family that can make it output **empty text with no error**. `patch_stt.py` does it correctly.

---

## 2. Final model list

| Lang | STT (speech → text) | TTS (text → speech) | TTS licence |
|---|---|---|---|
| Hindi | OpenVoiceOS IndicConformer, patched | Piper `hi_IN-rohan-medium` | IITM licence — **read it** |
| English | sherpa-onnx NeMo English conformer | Piper `en_US-ljspeech-medium` | public domain |
| Bengali | OpenVoiceOS IndicConformer, patched | rasa, voice 2 (BEN_F) | CC-BY-4.0 |
| Gujarati | OpenVoiceOS IndicConformer, patched | MMS `guj` | **CC-BY-NC-4.0** |
| Kannada | OpenVoiceOS IndicConformer, patched | rasa, voice 8 (KAN_F) | CC-BY-4.0 |
| Malayalam | OpenVoiceOS IndicConformer, patched | rasa, voice 11 (MAL_F) | CC-BY-4.0 |
| Marathi | OpenVoiceOS IndicConformer, patched | rasa, voice 12 (MAR_F) | CC-BY-4.0 |
| Odia | OpenVoiceOS IndicConformer, patched | MMS `ory` | **CC-BY-NC-4.0** |
| Tamil | OpenVoiceOS IndicConformer, patched | rasa, voice 18 (TAM_F) | CC-BY-4.0 |
| Telugu | OpenVoiceOS IndicConformer, patched | rasa, voice 19 (TEL_F) | CC-BY-4.0 |

All STT models are MIT (IndicConformer). The runtime (sherpa-onnx) is Apache-2.0. Silero VAD is MIT.

**About the licences, in plain words:**
- **CC-BY-4.0** means free to use, including commercially, as long as you credit the creator. Put the credits on the licence slide and in an "About" screen in the app.
- **CC-BY-NC** means free to use, but **not for commercial purposes**. Gujarati and Odia are the only two languages with no permissive voice available that runs on a phone. Two options:
  - **Keep MMS** (default): sounds natural. Say it honestly on the licence slide.
  - **Switch to eSpeak NG** (fallback F2 in plan.md): fully open source (GPL), but robotic.
- **Hindi:** the other two Piper Hindi voices (`priyamvada` female, `pratham` male) are CC-BY-NC-SA. `rohan` uses IIT Madras's Indic TTS licence — read it at the link in `manifest.json` before you decide. If it's non-commercial too, pick whichever Hindi voice sounds best.

I checked the English voice: the `lessac` voice plan.md suggested has a research-only dataset licence. `ljspeech` is public domain, and it passed the round-trip test below, so use it.

---

## 3. What's in this folder

```
p2-models/
  README.md              this file
  manifest.json          which model each language uses — the single source of truth
  alerts.json            5 alert phrases x 10 languages (DRAFT translations)
  test_sentences.json    one longer test sentence per language (DRAFT translations, testing only)
  scripts/
    common.py            shared helpers
    patch_stt.py         makes Hindi/Odia STT loadable + makes the INT8 copy
    quantize_int4.py     makes an INT4 copy (MatMul only) from a full-size model
    verify_models.py     tests every language on the laptop (text → speech → text)
    benchmark.py         measures size, RAM, speed, latency, accuracy per model
    pipeline_demo.py     the whole two-phone pipeline on one laptop, over a real socket
    frame.py             the link message format + compact Indic text encoding
    render_alerts.py     makes the 50 alert WAV files
  android/
    SpeechFactory.kt     reads manifest.json, creates STT/TTS in the app
    TextSanitizer.kt     cleans text right before TTS
```

The measuring and demo tools (`benchmark.py`, `pipeline_demo.py`, `frame.py`, `quantize_int4.py`) are explained in §15.

**What's been tested:** `patch_stt.py` was run on a test model with the same shape as IndicConformer; sherpa-onnx refused the unpatched version and loaded the patched one. `verify_models.py` and `render_alerts.py` were run end to end on the real English STT and TTS models (round-trip error 0.03 — a pass). Both Kotlin files compile against the real sherpa-onnx v1.13.8 Kotlin API, and `TextSanitizer.kt` gives identical output to the Python version. **Not tested:** the Indic models themselves (I couldn't download from Hugging Face in my environment). That's what your H1–H6 is for.

Later test runs on a public mirror of IndicConformer, plus a comparison with Meta's Omnilingual ASR, are summarized in §16.

---

## 4. Setup (H0–1)

**Python on the laptop:**

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install "sherpa-onnx==1.13.8" onnx onnxruntime onnx_ir soundfile numpy huggingface_hub
python -c "import sherpa_onnx; print(sherpa_onnx.__version__)"
```

The team uses **sherpa-onnx 1.13.8** in both Python and the Android AAR, so what you test is what ships. (1.13.5 is the minimum, because the rasa model needs it.) `onnx_ir` is only needed by `quantize_int4.py`.

**On Windows:** the commands here are for bash. Use Git Bash or WSL, or adapt them to PowerShell.

**Downloads** (do these before the 48 hours if the rules allow — it's a few GB):

```bash
mkdir -p dl && cd dl

# STT  (newer huggingface_hub only has the `hf` command; older ones call it `huggingface-cli`)
hf download mobilebytesensei/betterflow-indicconformer-ctc --local-dir betterflow
hf download OpenVoiceOS/ai4bharat-indicconformer-hi-onnx  --local-dir ovos-hi
hf download OpenVoiceOS/ai4bharat-indicconformer-or-onnx  --local-dir ovos-or

# TTS
hf download MatiasLin/sherpa-onnx-vits-rasa-13 --local-dir rasa
hf download willwade/mms-tts-multilingual-models-onnx --include "guj/*" "ory/*" --local-dir mms

# Official sherpa-onnx releases (all confirmed to exist)
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
curl -LO $R/tts-models/vits-piper-hi_IN-rohan-medium.tar.bz2
curl -LO $R/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2
curl -LO $R/asr-models/sherpa-onnx-nemo-ctc-en-conformer-medium.tar.bz2
curl -LO $R/asr-models/silero_vad.onnx
for f in *.tar.bz2; do tar xjf "$f"; done
```

All five Hugging Face repo names above were confirmed to exist (2026-09-29). Sizes: betterflow ~140 MB per language (it also has `hi` and `pa` folders, which the manifest doesn't use), OpenVoiceOS ~620 MB per language, rasa 123 MB, MMS 114 MB per language, the Piper voices 67 MB each, NeMo English 165 MB.

**Arrange everything into this layout** (the manifest expects exactly this):

```
models/
  manifest.json                  (copy it here too — the app reads it from here)
  vad/silero_vad.onnx
  stt/<lang>/model.int8.onnx     one folder per language: hi en bn gu kn ml mr or ta te
  stt/<lang>/tokens.txt
  tts/rasa/model.onnx, tokens.txt
  tts/piper-hi/model.onnx, tokens.txt
  tts/piper-en/model.onnx, tokens.txt
  tts/espeak-ng-data/            one copy, shared by both Piper voices
  tts/mms-gu/model.onnx, tokens.txt
  tts/mms-or/model.onnx, tokens.txt
```

Rename files to match (e.g. `hi_IN-rohan-medium.onnx` → `tts/piper-hi/model.onnx`). Keeping names uniform means the app code never has special cases.

---

## 5. STT (speech → text), step by step (H1–3)

### All 9 Indic languages: OpenVoiceOS full-size models, patched

**Changed after testing (2026-09-29):** every Indic language now comes from `OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx` (MIT), patched by us, not only Hindi and Odia. The ready-made betterflow files turned out to have their **Conv layers INT8 as well**, and on an Apple M4 that made STT **~3× slower** (RTF ~0.09 vs ~0.03) with no accuracy gain. They were also more easily thrown by silence before speech. betterflow stays in `dl/betterflow/` as a **fallback**: it's ready-made and checksum-verified, just slower.

**Use each OpenVoiceOS repo's own vocabulary, not betterflow's `tokens.txt`.** The OpenVoiceOS exports use a small **per-language vocabulary of 257 tokens**, not betterflow's shared 5,633-token one. Using betterflow's file with them would map every output to the wrong letter. `patch_stt.py` checks the model's real output size and refuses a mismatched `tokens.txt`, so a mistake here stops with an error instead of producing gibberish.

What's in each `dl/ovos-<lang>/`:
- `model.onnx` + `model.onnx_data`: the full-size model, stored as **two files** (the small `.onnx` holds the structure, `.onnx_data` holds the ~480 MB of weights). Keep them together in one folder. Pass `model.onnx` to the script.
- `model.int8.onnx` (only if you download it): OpenVoiceOS's own INT8, with Conv INT8 too. Not used.
- `vocab.txt`: the model's own vocabulary, **already in sherpa-onnx `tokens.txt` format** (`<token> <id>` per line, `<blk> 256` last). Just copy it to `tokens.txt`.

```bash
for l in hi bn gu kn ml mr or ta te; do
  hf download OpenVoiceOS/ai4bharat-indicconformer-$l-onnx model.onnx model.onnx_data vocab.txt --local-dir dl/ovos-$l
  cp dl/ovos-$l/vocab.txt dl/ovos-$l/tokens.txt
  python scripts/patch_stt.py --model dl/ovos-$l/model.onnx --tokens dl/ovos-$l/tokens.txt --out models/stt/$l --lang $l
done
```

All 9 model files come out **exactly the same size** (186 MB). That's normal: same architecture, different weights. `verify_models.py` checks the checksums differ.

**Fallback (betterflow):** copy `dl/betterflow/<lang>/model.int8.onnx` and `tokens.txt` into `models/stt/<lang>/`. Its `tokens.txt` files all start with Bengali characters and are all identical. That's normal too, because betterflow uses one shared 22-language vocabulary.

What it does, simply:
1. **Runs the model once on fake input** to measure its real vocabulary size and how much it shortens the audio (the "subsampling factor"). So the settings it writes can't be guessed wrong. If the model's vocabulary doesn't match `tokens.txt`, it stops and tells you.
2. **Writes the settings sherpa-onnx needs inside the `.onnx` file** ("metadata"). Without two of them the app crashes instantly with a crash you can't catch. Without `normalize_type=per_feature` it loads fine but outputs **empty text, silently**.
3. **Makes the INT8 copy** — the same model stored with smaller numbers, about 3× smaller file, nearly the same accuracy. Only MatMul layers, for the reason in §1.

Only the INT8 file and `tokens.txt` go into `models/stt/<lang>/`. A full-size copy with the metadata is saved **next to the downloaded file** (`<name>.sherpa.onnx`), away from the phone folder. Keep it: it's your fallback if INT8 ever gives wrong text (fallback F3 in plan.md).

### English

Copy `model.int8.onnx` and `tokens.txt` from `dl/sherpa-onnx-nemo-ctc-en-conformer-medium/` into `models/stt/en/`. It's the same kind of model as the Indic ones, so the app uses **one code path for all 10 languages**.

If it struggles with your team's Indian English accents in §7, switch to the `large` version (same file names). Last resort: Whisper `base.en` — `manifest.json` and `SpeechFactory.kt` already support `"type": "whisper"`.

---

## 6. TTS (text → speech), step by step (H3–5)

### rasa (Bengali, Kannada, Malayalam, Marathi, Tamil, Telugu)

Copy `dl/rasa/model.onnx` and `tokens.txt` into `models/tts/rasa/`. One model, loaded once, speaks all six.

**Voices** (from the official model card — only these are real):

| Language | Female | Male |
|---|---|---|
| Bengali | 2 | 3 |
| Kannada | 8 | 9 |
| Malayalam | 11 | — |
| Marathi | 12 | 13 |
| Tamil | 18 | — |
| Telugu | 19 | — |

The manifest uses female for all six, since Malayalam, Tamil, and Telugu only have female voices — that keeps them consistent. **Always use the voice for the matching language.** A Bengali voice reading Tamil will have a foreign accent.

**Speaking style** (`emotion_id` in the manifest). The listed styles are: 0 ALEXA, 1 ANGER, 2 BB, 3 BOOK, 4 CONV, 5 DIGI, 6 DISGUST, 7 FEAR, 8 HAPPY, 10 NEWS, 12 SAD, 14 SURPRISE, 15 UMANG, 16 WIKI. **Don't use unlisted numbers** (9, 11, 13, 17+).

For a radio, try **0 (ALEXA)**, **4 (CONV)**, and **10 (NEWS)**. Pick the clearest one, not the prettiest. **Timebox: 30 minutes total.** If you can't decide, use 0 — it's also what the model uses when no style is given, so it's the safest choice.

### Piper (Hindi, English) and MMS (Gujarati, Odia)

Copy each model and `tokens.txt` into its folder. Copy **one** `espeak-ng-data` folder to `models/tts/espeak-ng-data/` — both Piper voices use it to turn letters into sounds. MMS doesn't need it.

**Three different sample rates: rasa speaks at 24,000 samples per second, Piper at 22,050, MMS at 16,000.** (rasa's model card says 22,050, but the model file itself says 24,000, and that's what sherpa-onnx returns.) If the app plays audio at the wrong rate, it sounds too fast or too slow. `verify_models.py` checks the real rate against the manifest.

### Cleaning text before TTS

This happens automatically in `TextSanitizer.kt` and the Python scripts:
- **The last `.` `?` `!` `।` or `॥` is removed.** sherpa-onnx otherwise can make a tiny empty extra sentence that plays as noise.
- **For rasa and MMS, characters the voice doesn't know are removed**, so they can't garble the audio. `verify_models.py` lists any characters that get dropped.

---

## 7. Verify everything (H5–6) — this is gate M0

```bash
python scripts/verify_models.py --models models
python scripts/verify_models.py --models models --only hi,or   # re-test just some languages
```

**How it works, simply:** for each language it takes the 5 alert phrases, turns each into speech with that language's TTS, then turns the speech back into text with that language's STT, and compares. If the text comes back nearly the same, **both models work** — and you didn't need a recording or a native speaker to find out.

The number it prints is **CER** — character error rate. 0.00 means perfect; 0.25 means about 1 in 4 characters were wrong.

| CER | Means |
|---|---|
| ≤ 0.25 | **ok** |
| 0.25–0.50 | **WARN** — listen to the WAV to see whether TTS or STT is the problem |
| > 0.50 | **FAIL** |

It also catches the silent failures automatically: empty STT output, STT answering in the wrong script (for example, the Hindi model outputting Bengali letters — the sign of a mislabeled model), two STT files that are secretly identical, silent TTS output, and sample-rate mismatches.

**Then listen** to the files in `verify_out/`. A low CER means a machine could understand it, not that a person will find it clear.

For English and Hindi, also say 3 sentences into the laptop mic yourself — real voices are harder than TTS voices.

---

## 8. Alerts (H14–20)

1. **Get `alerts.json` checked by a native speaker for every language.** The translations are drafts. English is the reference meaning. Wrong alert text in front of ISRO judges is worse than no alert.
2. Render:
   ```bash
   python scripts/render_alerts.py --models models --out alerts
   ```
   Each file is **two warning beeps, a short gap, then the phrase**, made as loud as possible without distortion. The beeps make an alert instantly different from normal speech. Use `--no-tone` if you don't want them.
3. **Listen to all 50 files.**
4. Give P1 the `alerts/` folder. It's about 5 MB, so it can go inside the APK's assets. `alerts/index.json` maps the 1-byte alert ID P3 sends to the file name.

---

## 9. Putting models on the phones (H6–10)

Models go on the phone's storage, **not inside the APK** — this keeps the APK small, and APK size is scored.

```bash
PKG=com.your.app.package        # ask P1
D=/sdcard/Android/data/$PKG/files/models
adb shell mkdir -p $D
adb push models/. $D/
```

With both phones plugged in, add `-s <serial>` (from `adb devices`) to each command. The full set is roughly 2 GB, so allow a few minutes per phone. Check the phones have free space first.

---

## 10. Helping P1 plug it in

`android/SpeechFactory.kt` does the loading. How P1 uses it:

```kotlin
val m = Manifest(File(context.getExternalFilesDir(null), "models"))
val stt = m.makeRecognizer("hi")                    // sender's language
val text = stt.transcribe(samples)                  // 16 kHz mono floats from the mic
val voices = TtsPool(m)                             // one per app
val audio = voices.speak("ta", incomingText)        // null if nothing speakable
// play audio.samples at audio.sampleRate
```

**Things P1 needs to know from you:**
- **Never hard-code the sample rate** for playback. Create the `AudioTrack` using `audio.sampleRate` from each result (24,000 for rasa, 22,050 for Piper, 16,000 for MMS).
- **Run STT and TTS on one background thread**, never the UI thread.
- **One STT model at a time.** When the user switches language, call `release()` on the old recognizer before making the new one.
- **`TtsPool` keeps 2 voices in memory** and loads others on first use. rasa covers 6 languages, so it usually stays loaded.
- **The first sentence after loading is slower.** Right after connecting, call `voices.preload(lang)` and speak one throwaway word, so the first real message (the one being timed) isn't slow.
- **`espeak-ng-data` must be a real folder on disk**, not inside APK assets. The adb push above already does this.
- **The rasa speaking style is passed through `GenerationConfig.extra["emotion_id"]`.** `SpeechFactory.kt` already does it; this is the confirmed API in sherpa-onnx v1.13.8.
- **Continuous mode: keep ~0.3 s of audio from before each VAD segment ("pre-roll").** The VAD only starts a segment once it's sure someone is talking, so without pre-roll **the first word gets clipped**. Keep a small ring buffer of the last 0.3 s of mic audio and put it in front of each segment before STT. `pipeline_demo.py` does exactly this (`--pre-roll 0.3`), so copy its logic.
- **Continuous mode: the VAD only closes a segment after enough silence** (`min_silence_duration`, 0.5 s in our scripts). That wait is part of the end-to-end latency, so count it.

**On-phone check:** once STT and TTS are in the app, test each language with the same alert phrase you tested on the laptop. It should sound identical.

---

## 11. Your 48 hours

| Hours | What |
|---|---|
| H0–1 | Python setup. Give P1 the manifest + Kotlin files, give P3 the `wire_id`s. |
| H1–3 | STT: download and patch the 9 Indic languages (§5), add English. |
| H3–5 | TTS: rasa, Piper, MMS. Pick the rasa style (30 min max). |
| H5–6 | `verify_models.py` on all 10. Fix failures. **Gate M0 at H6.** |
| H6–10 | Push models to both phones. Pair with P1 on `SpeechFactory.kt`. |
| H10–14 | Everyone on Hindi end to end. **Gate M1 at H14.** |
| H14–20 | All 10 languages in the app. Alerts checked, rendered, handed over. |
| H20–25 | **Sleep.** |
| H25–30 | Fix any language that sounds wrong on the phone. Check RAM. |
| H30–34 | Everyone on integration. Help P3 time each language. |
| H34–38 | Size table + licence slide. **Feature freeze at H38.** |

**If a language won't work by H14,** use the fallbacks in plan.md §7 for that language only, and keep going. Don't let one language hold up the other nine.

---

## 12. When something breaks

| You see | Most likely cause | Fix |
|---|---|---|
| App crashes the moment STT loads, no Java exception | metadata missing from the `.onnx` | run `patch_stt.py` on it |
| STT loads but always returns empty text | `normalize_type` missing, or Conv layers were INT8'd | re-run `patch_stt.py` from the original file |
| STT returns text in the wrong script | mislabeled model file | re-download from the source in the manifest |
| Hindi/Odia STT outputs random letters | betterflow's 5,633-token `tokens.txt` used with a 257-token OpenVoiceOS model | use that repo's own vocab (§5) |
| Continuous mode drops the first word | no pre-roll before the VAD segment | keep 0.3 s of audio before each segment (§10) |
| `verify` says two STT files are identical | copy-paste repo | same as above |
| `unexpected input 'emotion_id'` when loading rasa | sherpa-onnx older than 1.13.5 | update pip package and the Android library |
| Short burst of noise at the end of speech | trailing `.` `?` `!` got through | make sure text goes through `TextSanitizer` |
| Words missing from the speech | characters the voice doesn't know | check the "TTS can't say" lines in `verify` output |
| Foreign accent | voice ID from a different language | use the table in §6 |
| Broken or garbled voice | voice ID above 19, or an unlisted style | use only listed IDs |
| Piper crashes or is silent | `espeak-ng-data` path wrong | it must be a real folder on disk |
| MMS voice fast and squeaky | played at 22,050 instead of 16,000 | use `audio.sampleRate` |
| rasa speaks too fast | its default speed differs from the original | set `"speed": 0.9` for that language |
| App runs out of memory | too many models loaded | keep `TtsPool` at 2; release old STT |
| Numbers disappear from speech | digits aren't in the TTS vocabulary | only matters if STT outputs digits — check `verify` output |

---

## 13. For the slides (H34–38)

**Size table:**

```bash
du -sh models/stt/* models/tts/* models/vad
```

Report the real numbers from your phones' model folders. Don't round down.

**Licence slide:** the table in §2, plus credits for every CC-BY model (AI4Bharat for IndicConformer and rasa; NVIDIA NeMo for English STT; the Piper voice authors from each voice's `MODEL_CARD`; Meta for MMS). Say which two voices are non-commercial and why — there's no permissive on-device voice for Gujarati or Odia. Judges respect honesty about limits much more than they respect finding out themselves.

---

## 14. A note on other teams

At least three other teams have public work on this exact PS on GitHub and Hugging Face. Reading their notes is how the trysem problem came to light. But **don't copy their code or their model packs.** SIH expects original work, and judges may know those repos.

It's also useful to know what they did: one well-developed submission uses MMS (non-commercial) for **five** languages. With rasa, you have two. That's a real difference, and worth one line on your licence slide.

---

## 15. Measuring and demo tools

### `benchmark.py` — the numbers for the slides

Measures every model the way the PS scores it. **Each model runs in its own process**, so the RAM numbers aren't mixed up.

```bash
python scripts/benchmark.py run --manifest manifest.json --models models \
       --real real_audio --out bench_out --tag int8 --threads 2
python scripts/benchmark.py run ... --only hi,ta      # just some languages
python scripts/benchmark.py run ... --skip-vad        # skip the 60 s VAD test
```

Per language it reports:
- **STT:** file size, load time, RAM, and for every clip the decode time, **RTF**, **CER** and **WER**. RTF ("real-time factor") = processing time ÷ audio length, so 0.25 means 1 s of speech takes 0.25 s to process. Below 1.0 is faster than real time.
- **TTS:** size, load time, RAM, **time to first audio**, RTF. It speaks the alert phrases and the `test_sentences.json` sentence.
- **VAD:** CPU used while listening to 60 s of quiet-room noise, which is the PS's "idle listening CPU" metric.

It scores two kinds of clips. **Round-trip** clips are made by TTS from known text, so they test TTS clarity and STT together. **Real** clips are your recordings in `real_audio/<lang>/<name>.wav`, with an optional `<name>.txt` holding **exactly what was said**. With no `.txt` you get the transcript but no score. TTS audio is cached in `bench_out/tts/`, so two STT variants (e.g. INT8 vs INT4, with different `--tag`) hear exactly the same audio.

Output: `bench_out/results_<tag>.json`. **Numbers only hold for the machine they ran on.** Always write down the CPU and `--threads` next to them.

### `pipeline_demo.py` — two "phones" on one laptop

Runs the whole chain, **wav → VAD → STT → frame → real TCP socket → frame → TTS → wav**, and times every stage. Use it to prove the models work together before the app exists.

```bash
# push-to-talk: the whole file is one message
python scripts/pipeline_demo.py --models models --wav real_audio/te/clip.wav --lang te --mode ptt
# continuous: VAD cuts the file into sentences and sends each one
python scripts/pipeline_demo.py --models models --wav clip.wav --lang en --mode continuous
# alert sent from a Hindi phone, heard on a Tamil phone (run render_alerts.py first)
python scripts/pipeline_demo.py --models models --alert evacuate --lang hi --recv-lang ta --alerts-dir alerts
```

Per message it prints `endpoint` (how long the VAD waited in silence, continuous mode only), `stt`, `link` (localhost, so a real link adds its own delay), `tts_first`, and **`e2e`** = their sum = "stopped talking" → "other phone starts speaking". It also prints the frame size and bits per second, both compact and UTF-8. Received audio goes to `pipeline_out/`. Useful options: `--pre-roll` (default 0.3 s), `--min-silence` (default 0.5 s), `--threads`.

### `frame.py` — the link message format

The exact bytes P3's Kotlin code must match:

```
[type 1B][lang 1B][seq 2B][len 2B][payload][CRC16 2B]      8 bytes of overhead
type 0x01 speech (UTF-8)   0x11 speech (packed)   0x02 alert (1-byte ID)   0x03 ping
CRC = CRC-16/CCITT-FALSE over everything before it; all numbers big-endian
```

**Packed text** makes Indic text about 3× smaller, losslessly. Each Indic script sits in its own 128-character Unicode block, and the language byte already says which one, so each letter fits in 1 byte: `0x00–0x7F` is plain ASCII, and `0x80–0xFF` is `(letter − start of the script's block) + 0x80`. If any character doesn't fit (e.g. a ZWJ, or a danda from another block), the sender uses UTF-8 for that message. A frame with a bad CRC or length is dropped, never spoken.

### `quantize_int4.py` — INT4 copy

```bash
python scripts/quantize_int4.py --model dl/ovos-hi/model.sherpa.onnx --out model.int4.onnx
```

Stores MatMul weights in 4 bits instead of 8 (ONNX Runtime's `MatMulNBits` operator, which the Android ONNX Runtime also runs). Conv layers are left alone. **The input must be the full-size model with metadata** (the `.sherpa.onnx` file from `patch_stt.py`), not an INT8 one. The sherpa-onnx metadata is copied across. Always compare it against INT8 with `benchmark.py` before switching: it's smaller, but can be slower and less accurate (see §16).

### `work/build_models_v2.py` and `work/make_pack.py`: smaller models, per-phone packs

After `models/` is built as above, `python work/build_models_v2.py --src <full set> --out models` stores every float32 weight as float16 (cast back to float32 when ONNX Runtime loads the model, so it runs at the same speed with the same accuracy) and trims espeak-ng-data to Hindi + English: 2,238 → 1,685 MB. `python work/make_pack.py --langs hi,en` then builds a per-phone pack (STT for those languages + every voice, ~400–460 MB). Measurements: `docs/SIZE_REDUCTION.md`.

### `test_sentences.json`

One longer sentence per language ("the road near the bridge is blocked…"), used by `benchmark.py` next to the short alert phrases, so TTS and STT are also tested on a realistic, longer message. **Draft translations, testing only, never shipped.**

---

## 16. Earlier test results, and why Omnilingual ASR was rejected

**Conditions for all numbers here:** one Intel Xeon core at 2.8 GHz, 1 thread, x86_64, sherpa-onnx 1.13.8, onnxruntime 1.24.4. **These are laptop numbers, not phone numbers, and they came from an earlier session, not from this kit's final `models/` folder.** The IndicConformer files were a public mirror (MatMul **and** Conv INT8, 257-token vocab, no Hindi). The TTS for kn, mr, or and ta/te was MMS, because rasa couldn't be reached then.

We tested **Meta Omnilingual ASR 300M** (one model for every language, Apache-2.0) against IndicConformer. Round-trip CER (known text → TTS → STT, 6 sentences per language, 0 = perfect):

| Lang | IndicConformer INT8 | Omnilingual INT8 | Omnilingual INT4 |
|---|---|---|---|
| en | 0.023 (NeMo en medium) | 0.037 | 0.041 |
| kn | 0.011 | 0.043 | 0.067 |
| ml | 0.023 | 0.095 | 0.123 |
| mr | 0.058 | 0.253 | 0.237 |
| te | 0.068 | 0.379 | 0.371 |
| ta | 0.069 | 0.311 | 0.301 |
| or | 0.107 | 0.185 | 0.167 |
| gu | 0.115 (weak Mimic3 voice) | 0.580 | 0.567 |
| hi | not tested | 0.204 | 0.271 |

**Omnilingual is rejected.** It's a CTC model with no way to tell it the language, so it **mixes scripts**: Hindi came back with Latin and Malayalam letters, Gujarati with Devanagari and Arabic, and real Telugu speech with Kannada letters. It's also bigger (366 MB INT8 vs ~135 MB per IndicConformer language) and slower (RTF ~0.36 vs 0.26–0.28). INT4 made it 32% smaller but slower (RTF ~0.49) and slightly less accurate on that CPU. ARM phones may rank INT4 differently.

Also from those runs:
- **The Conv INT8 risk is real but not certain.** That mirror had 54 `ConvInteger` nodes (Conv quantized) and still worked. So Conv INT8 *can* break Conformer models, but doesn't always. `verify_models.py` is what tells you.
- **MMS TTS is the latency bottleneck.** RTF 0.58–0.66 and 1.2–1.9 s to first audio, vs Piper 0.10–0.13 and ~0.25 s. Telugu push-to-talk end to end was 4.77 s, 3.48 s of it MMS.
- **rasa does not fix that.** Measured later on an Apple M4 (2 threads, sherpa-onnx 1.13.8, `work/t4_rasa.py`), rasa was *slightly slower* than MMS on the same machine: RTF 0.58–0.65 vs MMS 0.45–0.53, with a similar ~1.1–1.6 s average to first audio. rasa's real advantages are the **licence** (CC-BY vs non-commercial) and **one 118 MB model for six languages** instead of six ~109 MB MMS models, not speed. Only Piper is fast (RTF ~0.1).
