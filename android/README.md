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

Open the app once before pushing files so Android creates its external files directory. Microphone permission is requested when you first use a recording control. Select a language, and tap **Reload selected language** after copying its models. The app uses the current P2 manifest bundled as a fallback; an external `models/manifest.json` takes precedence on launch.

Alerts use `files/alerts/<language>/<name>.wav`, or the same `alerts/` tree in APK assets if provided at build time. Names and IDs come from P2's `alerts.json` (1-5). The WAV reader requires mono PCM16 and preserves the file's sample rate. Missing files are logged; there is no synthesized substitute for an alert. Native-speaker review and rendering are P2's outstanding work.

## Use over Wi-Fi

1. Select **Wi-Fi** in the app. Connect both phones to one phone's hotspot with mobile data off.
2. Tap **Host** on one phone. On the other, tap **Scan for devices** and select its iTantra name. Its address and port are resolved automatically; there are no manual address fields.
3. Wait for the selected language to be ready. Hold **HOLD TO TALK**, speak, release to transcribe and send. Cancelled touches discard the utterance; recordings are limited to 30 seconds.
4. Enable **Continuous listening** for Silero VAD segmentation, with 300 ms of recorded pre-roll. Capture stops during audio playback and when the activity leaves the foreground.
5. Incoming speech uses the sender's language. Alerts use the receiver's selected language. Alerts preempt normal speech and clear pending speech; another app message cannot interrupt an active alert. Alerts request exclusive focus, raise the alarm stream to maximum, and restore the prior volume afterward.
6. **Ping** logs RTT. The message log shows STT duration/RTF, speech-end-to-STT, frame bytes, TTS duration/RTF and receive-to-first-playback timing.

“First playback” uses the AudioTrack playback-head counter, not an acoustic measurement at the speaker. Continuous-mode end-to-STT starts when VAD emits a segment; it excludes the silence-detection interval. Combine matched TX/RX logs and half RTT for the end-to-end estimate; it is not transmitted in the speech frame.

## Use over Bluetooth

1. Select **Bluetooth** on both phones. Grant Nearby devices access on Android 12+, or Location access for scanning on Android 8–11. Bluetooth support is optional; phones without it can still use Wi-Fi.
2. Tap **Host** on phone A. If Bluetooth is off, approve Android’s enable prompt and tap Host again. Approve making A discoverable for 120 seconds; the app then opens its secure iTantra RFCOMM service.
3. On B, tap **Scan for devices**, then select A by its Bluetooth name (a short address suffix distinguishes duplicate names). Approve Android’s pairing prompt on both phones if asked. Paired devices are shown immediately; nearby devices are added during the scan.
4. Once connected, use Talk, continuous mode, alerts and Ping exactly as over Wi-Fi. No Wi-Fi network, internet connection or typed address is needed.

Bluetooth lists can include devices that do not run iTantra. Select a phone running the app in Bluetooth Host mode; other devices will fail to connect. Connection attempts time out after 30 seconds. If a new host is not visible, tap Host again to reopen the discoverability window. On Android 8–11, system Location services may also need to be enabled for device scanning. Permission denial offers retry or an app Settings shortcut.

Switching transport, hosting again, scanning again or disconnecting closes the previous link and discards pending normal speech, so old messages do not carry into the next conversation. Bluetooth scanning stops when the activity leaves the foreground. Existing links remain until disconnect/destruction; recording still pauses in the background. Android’s approved discoverability window may remain until its 120-second timeout even if you switch transport. Bluetooth uses the same CRC-protected frames and secure RFCOMM UUID `3c83e648-23e1-44bd-9bed-596ca0d26173`; it does not use headset/SCO audio or send raw microphone audio.

## Wi-Fi device discovery

Hosts are listed by phone model plus a short app-generated ID; no hardware identifier is used. Only phones running iTantra and waiting in **Host** mode appear. DNS-SD resolves both address and port. Hosting keeps port 26173 for compatibility with the existing laptop test tools.

Scan runs for 30 seconds and updates the list as hosts appear or disappear. Selecting a device resolves its current endpoint before joining. Starting another scan ends the existing link/host session. Scanning and advertisement stop when the app goes into the background; a waiting host advertises again on return. Hosts withdraw their advertisement once connected because this app supports one peer at a time.

If the list stays empty, open iTantra and tap **Host** on the other phone, confirm both devices share Wi-Fi/a hotspot, and scan again. Networks that isolate clients or block multicast can prevent discovery; switch hotspot/router. This Wi-Fi mode discovers local app services, not Wi-Fi networks; use the separate Bluetooth option for radio discovery. Old app builds and `fake_peer.py` do not advertise a discoverable service; the laptop tools can still join a hosting phone directly.

## Microphone recovery and local test

**Hold to talk**, **Continuous listening**, **Microphone access**, and **Test microphone** request access when needed, even before models or the connection are ready. After a denial, the next attempt explains the need and offers another request. If Android stops showing the permission prompt, **Open Settings** leads directly to this app’s permissions. Continuous mode stays off when access is missing; enable it after granting access.

**Test microphone** displays an RMS level meter without requiring models or a peer. It does not transcribe, save or transmit the samples. Stop it with the same button; playback, backgrounding and language changes stop it too. Returning to the app does not restart a local mic test.

Playback requests Android’s low-latency performance mode and logs the actual mode, which the device may downgrade. No latency reduction is claimed until the new build is measured. The APK excludes the standalone sherpa C/C++ API libraries; the JNI runtime and ONNX runtime remain packaged.

## Integration boundaries

- `speech/`: adapted P2 loaders/sanitizer; one STT model and up to two cached TTS engines. STT and TTS each have their own serialized executor, so synthesis cannot hold outgoing recognition in its queue. Language changes retain cached voices; cache eviction still releases the least recently used engine. Budget an additional ~240–350 MB for the second voice on the tested phones. Warm-up generation is discarded, not played or measured.
- `audio/`: AudioRecord capture and sherpa Silero VAD on a separate bounded executor, absolute-index pre-roll, sample-rate-aware playback and WAV alerts. Normal TTS is split at commas/semicolons so generation can overlap playback.
- `link/`: interchangeable Wi-Fi TCP and secure Bluetooth RFCOMM transports with a shared frame-stream reader. Android DNS-SD (`_itantra._tcp`) host advertisement and device picker, plus TCP integration for P3, CRC and packed text byte-compatible with `link/test_vectors.json`. Host-originated ping IDs are even; joiner IDs are odd, avoiding response echo loops without changing the wire format. P3 must preserve that convention or replace ping on both peers.
- Models remain external to the APK. No training, model downloads in the app, cloud APIs, background service, slides or model changes are included.
- UI language is currently English; speech supports all ten manifest languages when their files are installed.

## Validation

Automated: build, Android lint, JVM tests for all Python wire vectors, every single-byte frame corruption, malformed lengths/types, Unicode fallback, real pre-roll wrap/reset, WAV format/rates, sanitizer, and real TCP fragmentation/CRC rejection/send/disconnect.

The original build has device results in [DEVICE_TEST.md](../docs/DEVICE_TEST.md). The updated build still needs device regression checks (no phone/emulator was attached while applying the report’s fixes):

- English first, then all ten languages: hold/release/cancel; switch languages repeatedly; confirm native memory is released.
- Continuous mode: first word preserved, long speech split, several utterances, no self-triggering during playback.
- Two-phone offline exchange both ways; disconnect/reconnect; wrong IP; permission denial and later grant.
- Test each of the 50 approved WAVs, including a Hindi sender/Tamil receiver. Set receiver silent and low volume, send an alert during speech, send another alert, verify maximum alarm output and subsequent volume restoration.
- Check DND/device audio policy and another app's focus request on the actual phones. Android controls system-level interruptions; the app cannot guarantee suppression of calls, OS policy, power loss or force-stop.
- Background/foreground and rotate while recording, generating speech and playing alerts; verify no lingering mic or crash. Destruction releases playback; no background service is provided.
- Collect phone latency, RAM, idle CPU and perceptual clarity using P3's measurement plan. Do not reuse the old build’s timings as measurements of this updated build.

## Upstream attribution

The capture and AudioTrack API patterns follow the Apache-2.0 sherpa-onnx v1.13.8 [VAD+ASR example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt) and [TTS example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt). Model licences and preparation remain documented in `p2-models/README.md`.
