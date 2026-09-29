# P1 app: on-device test (2026-09-30)

**Build:** commit `303ed26` (P1 "Implement P1 Android voice transceiver app"), debug APK.
**Phone:** realme RMX3031, MediaTek Dimensity 1200 (MT6893), 12 GB RAM, Android 13.
**Peer:** the laptop acted as "the other phone". The phone tapped **Host**, `adb forward tcp:26173 tcp:26173` carried the link over USB, and `work/fake_peer.py` joined using the reference wire format (`p2-models/scripts/frame.py`). So the link latency below is USB, not Wi-Fi.
**Models:** `models/` from P2 (manifest with rasa style 10), plus **draft** alert WAVs (translations not native-checked, testing only).

## Build

| Check | Result |
|---|---|
| `./scripts/fetch-runtime.sh` | AAR downloaded, **checksum OK** |
| `gradle-wrapper.jar` | matches Gradle 8.14.3's published SHA-256 |
| `assembleDebug` | **BUILD SUCCESSFUL** (JDK 21 from Android Studio; the installed JDK 25 is too new for Gradle 8.14) |
| Unit tests | **7/7 pass** (CoreTest 6, LinkServiceTest 1) |
| Lint | **0 errors**, 8 minor warnings (string resources, newer versions available) |
| **APK size** | **33.0 MB** debug, arm64 only. 22 MB of it is `libonnxruntime.so` |

## What works on the phone

| Feature | Result |
|---|---|
| Mic permission prompt | ✅ shown on first launch |
| Models from external storage, external `manifest.json` takes precedence | ✅ English ready ~3 s after launch |
| Host / join / disconnect | ✅ |
| Ping echo (odd/even convention) | ✅ RTT 2–9 ms over USB |
| **Receive speech in all 10 languages** (TTS in the sender's language) | ✅ all 10 spoke |
| **Comma splitting**: part 2 generated while part 1 plays | ✅ estimated gap between parts ~10–90 ms (from the logged TTS times and RTF) |
| Corrupted frame (1 bit flipped) | ✅ "CRC mismatch - dropped", not spoken |
| **Alert played in the receiver's language** (Hindi sender → English phone) | ✅ |
| **Alarm volume raised to max, then restored** | ✅ 5 → 16 during the alert → back to 5 |
| **Push-to-talk, English**: Mac speaker plays a known sentence, phone mic → STT → frame → laptop | ✅ **exact transcript**, STT 285 ms (RTF 0.042), release → frame sent 330 ms, 83 bytes |
| Continuous mode | ✅ detects and sends speech (it picked up people talking in the room) |
| Memory with English loaded | 320 MB PSS (453 MB RSS). **0% CPU** when idle |

## Latency: receive → first sound (`RX … first playback`, phone log)

| Voice | First message after a voice switch (cold) | Same voice again (warm) |
|---|---|---|
| Piper en | 2.08 s | **0.44–0.54 s** (TTS ~185 ms) |
| Piper hi | 1.31–1.45 s | **0.48 s** |
| rasa (bn kn ml mr ta te) | 2.05–2.49 s | **1.27–1.61 s** (TTS RTF ~0.55 at 4 threads) |
| MMS gu / or | 1.95–2.08 s | **0.97 s** |

On this phone rasa runs at RTF ~0.55 (4 threads), vs ~0.8 on the Nothing Phone (3a).

## Suggested fixes for P1 (in order of impact)

1. **Cold voice switch costs ~1 s on the first message.** `TtsPool(maxLoaded = 1)` unloads the old voice every time the incoming language changes, and the first run after a load is slow. Options: keep `maxLoaded = 2` (a voice is ~240–350 MB, and these phones have 12 GB), and/or do a throwaway warm-up right after loading any voice, not only the selected language's.
2. **~280 ms from "audio ready" to "playback head moves"** on every message (e.g. TTS done at 185 ms, first playback at 440–540 ms). Try `AudioTrack.Builder().setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)`, or keep one `AudioTrack` per sample rate open instead of creating one per part.
3. **STT waits behind TTS** (one serialized inference executor). In continuous mode, an outgoing message was delayed by 1.0–1.7 s ("end→STT") while an incoming message was being spoken. Acceptable for a walkie-talkie, but worth knowing for the latency slide.
4. **APK: drop unused native libs.** `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so` (~4.9 MB) aren't used by the Kotlin/JNI path. Exclude them with `packaging { jniLibs { excludes += ... } }`, then re-check that it still loads.

## Second phone: Nothing Phone (3a), over wireless debugging (same build `303ed26`)

**Phone:** Nothing A059, Snapdragon 7s Gen 3, 12 GB, Android 16, connected with `adb pair` / `adb connect` over Wi-Fi. The laptop peer used `adb forward` over that wireless link.

| Test | Result |
|---|---|
| **Language switching**, all 10 languages × 2 rounds (`work/lang_switch_test.py`) | ✅ **Memory is released:** PSS stays flat (Indic 460–484 MB, Hindi 428 MB, English 307 MB) and doesn't grow in round 2. Ready 2.9–3.9 s after each switch |
| **Mic permission denied** | ✅ "Microphone permission is required for sending speech", no crash |
| **Permission granted later** (as the user would in Settings) | ✅ mic opens (16 kHz mono) without restarting the app |
| **Background / foreground** during continuous listening and during a PTT hold | ✅ mic released on Home both times, restarted on return, connection kept, 0 crashes |
| **Alert cuts off ongoing speech** (Android audio-focus log) | ✅ speech focus abandoned and the alarm's **exclusive** focus taken **30 ms** later. The rest of the interrupted message was dropped |
| **Speech arriving during an alert** | ✅ waited, then played **3 ms** after the alert ended |
| **Self-triggering in continuous mode** (4 messages with distinct words; frames checked for those words) | ✅ **0 frames** back. The mic paused during each of the 4 playbacks and resumed afterwards |

### Issues found (for P1)

5. **No second permission request.** After "Don't allow", pressing HOLD TO TALK only logs a message. It never re-asks, so the user must find the Settings page. Suggest calling `requestPermissions` again on the next PTT/continuous press (Android allows one more request), then pointing to Settings with a button.
6. **The Continuous listening switch turns on with the mic denied**, with no warning. Suggest keeping it off and showing the permission message.
7. **Recording needs a connection** ("Wait for models and connection"). That's reasonable, but it means you can't test the mic alone. Consider a local "mic test" meter.

## Not verified yet

- **PTT in all 10 languages over the air:** the first attempt played the test sentences into the Mac's Bluetooth earbuds instead of its speakers, so the phone heard silence. Re-run `work/ptt_langs_test.py` with the Mac's built-in speakers selected. (English PTT on the realme was already exact.)
- **Two real phones over Wi-Fi/hotspot** (host/join, both directions, ping over Wi-Fi, disconnect/reconnect, wrong IP): the realme's Wi-Fi was off.
- Rotation while recording, speaking, or playing an alert (not attempted).
- Alerts with native-checked text (the WAVs used here are drafts).

## Reproduce

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb push models/. /sdcard/Android/data/org.itantra.app/files/models/
python work/tap.py HOST                       # tap a button by its label
adb forward tcp:26173 tcp:26173
python work/fake_peer.py rx-test              # pings, 10 languages, bad CRC, alerts
python work/fake_peer.py speech --langs en,en,hi,hi,ta,ta
python work/fake_peer.py listen --secs 15     # print what the phone sends (PTT)
SERIAL=<device> python work/lang_switch_test.py   # 10 languages x2, memory after each switch
SERIAL=<device> python work/ptt_langs_test.py     # PTT per language, laptop speaker -> phone mic
```

`SERIAL` picks the phone when more than one is connected (`adb devices`).

Phone timings are in `adb logcat -s iTantra:I`. **Keep those logs out of git:** in continuous mode they contain transcripts of whatever the phone heard (`device_test/` is gitignored).
