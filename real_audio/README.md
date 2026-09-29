# Real recordings: how to add them

These clips give us our **real accuracy numbers** (the 40% of the score). The round-trip test only proves our TTS and STT understand each other. Real human speech is what judges care about.

## What to record

**5 sentences per language**, spoken by a real person, ideally a native or fluent speaker.

- Say normal, useful radio-style sentences, 3–8 seconds each. For example: "Send two more people to the north gate", "The water level is rising near the bridge".
- Different sentences for each clip. Don't just read the alert phrases.
- A mix of speakers helps (male/female, different accents), but one speaker is fine.

## File format

```
real_audio/<lang>/<name>.wav    the recording
real_audio/<lang>/<name>.txt    EXACTLY what was said, in that language's own script
```

`<lang>` is one of: `hi en bn gu kn ml mr or ta te`. Example:

```
real_audio/hi/01.wav
real_audio/hi/01.txt     →  पुल के पास पानी बढ़ रहा है
```

**The `.txt` must be written by the person who spoke it, or someone who listened to the clip.** Write what was actually said, including any mistakes or repeated words, not what was meant to be said. Don't copy the model's output into the `.txt`, because then the score measures nothing. If there's no `.txt`, the benchmark still prints the transcript but gives no score.

Writing the `.txt`:
- Use the language's own script (Hindi in Devanagari, not "pul ke paas").
- Punctuation doesn't matter (it's ignored when scoring).
- Write numbers as words ("दो", not "2"), since that's how the models output them.
- English words said inside an Indic sentence: write them the way the Indic model would, in that script ("एयरपोर्ट"), since our Indic models only output their own script.

## Recording tips

- **Use a phone's microphone** if you can. That's what the real app uses.
- Quiet room, phone ~20 cm from your mouth, normal speaking volume.
- Leave ~0.5 s of silence before and after speaking.
- Any WAV works: mono, any sample rate. **16 kHz mono 16-bit WAV is ideal.** Phone voice recorders often save `.m4a`. Convert those with:
  ```bash
  ffmpeg -i clip.m4a -ac 1 -ar 16000 clip.wav
  ```
- No music, TV, or other people talking in the background.

## Running the score

```bash
python p2-models/scripts/benchmark.py run --manifest p2-models/manifest.json \
       --models models --real real_audio --out bench_out --tag int8 --threads 2
```

Results: `bench_out/results_int8.json`, entries with `"kind": "real"`, showing CER and WER for each clip.
