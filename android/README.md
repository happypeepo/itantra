# android/: the iTantra app (owner: P1)

Native Kotlin Android app. The spec is in `plan.md` §6A. **Start from sherpa-onnx's own Android examples** (the VAD+ASR one and the TTS one) and change them, rather than writing audio handling from scratch.

Nothing is built here yet. Put the Android Studio project in this folder.

## Suggested layout

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/ ...
  app/
    libs/sherpa-onnx-1.13.8.aar        same version the Python tests use (not committed; download it)
    src/main/
      AndroidManifest.xml              RECORD_AUDIO, INTERNET, ACCESS_WIFI_STATE, WAKE_LOCK
      assets/alerts/<lang>/<name>.wav  pre-rendered alerts (~5 MB, from render_alerts.py)
      assets/alerts/index.json         alert id -> name
      java/org/itantra/app/
        MainActivity.kt                UI: language picker, PTT button, mode switch, alert buttons, log, status
        speech/SpeechFactory.kt        copy from p2-models/android/ (loads models from manifest.json)
        speech/TextSanitizer.kt        copy from p2-models/android/ (cleans text before TTS)
        audio/MicRecorder.kt           AudioRecord 16 kHz mono + 0.3 s pre-roll ring buffer
        audio/VadSegmenter.kt          silero VAD -> sentences (continuous mode)
        audio/Player.kt                playback queue; alerts jump the queue
        audio/AlertPlayer.kt           STREAM_ALARM, max volume, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        link/Frame.kt                  from link/: must match p2-models/scripts/frame.py byte for byte
        link/LinkService.kt            TCP host/join, ping
        metrics/Metrics.kt             per-message timings, bytes, RTF
```

The package name `org.itantra.app` is only a suggestion. Tell P2 and P3 the real one (it's in the `adb push` path).

## Must-knows from P2's testing

Details are in `p2-models/README.md` §10.

1. **Models live on phone storage, not in the APK.** `adb push models/. /sdcard/Android/data/<pkg>/files/models/`. That keeps the APK small, which is scored.
2. **Play each TTS result at its own `sampleRate`.** rasa is **24,000 Hz**, Piper 22,050 Hz, MMS 16,000 Hz. Never hard-code a rate.
3. **Continuous mode: prepend ~0.3 s of pre-roll** (real mic audio from before the VAD segment), or the first word gets clipped. **Never pad with zeros**: exact digital silence confuses several STT models.
4. **Turn the mic off while the phone is speaking**, or it hears itself and loops.
5. **Run STT and TTS on one background thread**, never the UI thread. Use one STT model at a time, and `release()` the old one when switching language.
6. **Warm up after connecting:** `voices.preload(lang)` and speak one throwaway word, so the first timed message isn't slow.
7. **TTS is the latency bottleneck.** On an M4 laptop, rasa and MMS take 1–4 s to first audio (Piper ~0.15 s), and a phone is slower. If it's too slow on the target phone, split long messages at commas and speak the parts one after another.
