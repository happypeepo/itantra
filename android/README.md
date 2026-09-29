# Android app - Person 1

Material 3 interface with a teal light/dark theme, grouped connection/talk/alert/activity cards, an exposed language dropdown, and a large push-to-talk control.

Native Kotlin app, package **`org.itantra.app`**, Android 8+ (API 26), arm64.
The implementation plan is [P1_PLAN.md](P1_PLAN.md).

## Build

Requires JDK 17, Android SDK platform 36 and a network connection for the initial dependency download. Runtime operation is offline.

```sh
cd android
./scripts/fetch-runtime.sh
# Set ANDROID_HOME to your SDK, or sdk.dir in local.properties.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. The pinned sherpa-onnx 1.13.8 AAR is checksum-verified, ignored by Git, and must be downloaded on each new checkout. The Gradle wrapper is checked in. Models and WAVs are not bundled in the repository.

## Install and provide P2 assets

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.itantra.app/.MainActivity
# From the repository root, after P2 has prepared the model tree:
adb push models/. /sdcard/Android/data/org.itantra.app/files/models/
adb push p2-models/manifest.json /sdcard/Android/data/org.itantra.app/files/models/manifest.json
# After P2 renders and checks the alerts:
adb push alerts /sdcard/Android/data/org.itantra.app/files/
```

Open the app once before pushing files so Android creates its external files directory. Grant microphone access, select a language, and tap **Reload selected language** after copying its models. The app uses the current P2 manifest bundled as a fallback; an external `models/manifest.json` takes precedence on launch.

Alerts use `files/alerts/<language>/<name>.wav`, or the same `alerts/` tree in APK assets if provided at build time. Names and IDs come from P2's `alerts.json` (1-5). The WAV reader requires mono PCM16 and preserves the file's sample rate. Missing files are logged; there is no synthesized substitute for an alert. Native-speaker review and rendering are P2's outstanding work.

## Use

1. Connect both phones to one phone's hotspot with mobile data off.
2. Tap **Host** on one phone; its local IPv4 addresses and port 26173 appear in the status/log. Enter the appropriate address on the other and tap **Join**.
3. Wait for the selected language to be ready. Hold **HOLD TO TALK**, speak, release to transcribe and send. Cancelled touches discard the utterance; recordings are limited to 30 seconds.
4. Enable **Continuous listening** for Silero VAD segmentation, with 300 ms of recorded pre-roll. Capture stops during audio playback and when the activity leaves the foreground.
5. Incoming speech uses the sender's language. Alerts use the receiver's selected language. Alerts preempt normal speech and clear pending speech; another app message cannot interrupt an active alert. Alerts request exclusive focus, raise the alarm stream to maximum, and restore the prior volume afterward.
6. **Ping** logs RTT. The message log shows STT duration/RTF, speech-end-to-STT, frame bytes, TTS duration/RTF and receive-to-first-playback timing.

“First playback” uses the AudioTrack playback-head counter, not an acoustic measurement at the speaker. Continuous-mode end-to-STT starts when VAD emits a segment; it excludes the silence-detection interval. Combine matched TX/RX logs and half RTT for the end-to-end estimate; it is not transmitted in the speech frame.

## Integration boundaries

- `speech/`: adapted P2 loaders/sanitizer; one STT model and at most one TTS engine, one serialized inference executor. Warm-up generation is discarded, not played or measured.
- `audio/`: AudioRecord capture and sherpa Silero VAD on a separate bounded executor, absolute-index pre-roll, sample-rate-aware playback and WAV alerts. Normal TTS is split at commas/semicolons so generation can overlap playback.
- `link/`: minimal TCP integration for P3, CRC and packed text byte-compatible with `link/test_vectors.json`. Host-originated ping IDs are even; joiner IDs are odd, avoiding response echo loops without changing the wire format. P3 must preserve that convention or replace ping on both peers.
- Models remain external to the APK. No training, model downloads in the app, cloud APIs, discovery, Bluetooth, background service, slides or model changes are included.
- UI language is currently English; speech supports all ten manifest languages when their files are installed.

## Validation

Automated: build, Android lint, JVM tests for all Python wire vectors, every single-byte frame corruption, malformed lengths/types, Unicode fallback, real pre-roll wrap/reset, WAV format/rates, sanitizer, and real TCP fragmentation/CRC rejection/send/disconnect.

Hardware acceptance is still required (no phone/emulator was attached during implementation):

- English first, then all ten languages: hold/release/cancel; switch languages repeatedly; confirm native memory is released.
- Continuous mode: first word preserved, long speech split, several utterances, no self-triggering during playback.
- Two-phone offline exchange both ways; disconnect/reconnect; wrong IP; permission denial and later grant.
- Test each of the 50 approved WAVs, including a Hindi sender/Tamil receiver. Set receiver silent and low volume, send an alert during speech, send another alert, verify maximum alarm output and subsequent volume restoration.
- Check DND/device audio policy and another app's focus request on the actual phones. Android controls system-level interruptions; the app cannot guarantee suppression of calls, OS policy, power loss or force-stop.
- Background/foreground and rotate while recording, generating speech and playing alerts; verify no lingering mic or crash. Destruction releases playback; no background service is provided.
- Collect phone latency, RAM, idle CPU and perceptual clarity using P3's measurement plan. No device measurements are claimed by this implementation.

## Upstream attribution

The capture and AudioTrack API patterns follow the Apache-2.0 sherpa-onnx v1.13.8 [VAD+ASR example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt) and [TTS example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt). Model licences and preparation remain documented in `p2-models/README.md`.
