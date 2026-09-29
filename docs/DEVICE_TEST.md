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

## New build: Material 3 UI (commit `6362974`), both phones, two-phone link over Wi-Fi

**Build:** 7/7 unit tests pass and lint has 0 errors. **APK 38.7 MB** (was 33.0). Note: `LinkServiceTest` binds the fixed port 26173 on the build machine, so it fails if anything else (e.g. an `adb forward`) is using that port. A random free port would make it robust.

**Phones:** realme RMX3031 (192.168.1.13) and Nothing Phone (3a) (192.168.1.6), both over wireless adb, on the same home Wi-Fi. **The router allowed phone-to-phone traffic.**

| Test | Result |
|---|---|
| Install as an update (models kept), launch | ✅ both phones, "English ready" |
| **Two real phones connect over Wi-Fi** (realme Host, Nothing Join by IP) | ✅ both show Connected |
| **Ping over Wi-Fi** | ✅ Nothing → realme 8–20 ms; realme → Nothing 20–270 ms (first ping slowest) |
| **Cross-language alert over Wi-Fi:** Nothing set to Hindi taps Evacuate → realme set to Tamil | ✅ realme: "Alert: evacuate [ta]" |
| **Speech A → B over Wi-Fi, over the air:** realme plays its test alert, the Nothing's mic picks it up (PTT), and the realme speaks it | ✅ Nothing TX "emergency alert" (STT 214 ms); realme RX, first playback 509 ms |
| **Speech B → A** (realme in continuous mode) | ✅ realme TX "emergency alert" (STT 96 ms); Nothing RX, first playback 347 ms |
| Disconnect, then reconnect (Host + Join again) | ✅ both sides; ping OK afterwards |
| Other phone's app force-stopped mid-connection | ✅ the Nothing shows Disconnected, no crash |
| Wrong IP (192.168.1.250) | ✅ fails cleanly after the 5 s timeout, then a correct Join works |
| Receive test on the new UI (laptop peer): 10 languages, CRC drop, alerts in the receiver's language | ✅ all pass |
| Alarm volume during an alert | ✅ 6 → 16 → back to 6 |
| Language switching × 20 (realme) | ✅ memory flat: Indic 466–500 MB, Hindi ~430, English ~277, no growth in round 2 |

Latencies between the two phones can't be combined across devices, because their clocks aren't synced. Each phone's own numbers are valid (STT time, receive → first playback).

**Small UX issues:** a normal disconnect shows "Disconnected: null", and a wrong IP shows the raw Java error (`EHOSTUNREACH (No route to host)…`). Suggest friendly text.

**Test-harness notes** (not app bugs, but they cost time):
- The language dropdown opens in a popup that the plain `uiautomator dump` can't see. `work/devui.py` uses `uiautomator dump --windows` (Android 14+) or the keyboard (older Android).
- A scroll gesture that crosses the "Continuous listening" switch toggles it. `devui.scroll` now swipes along the page margin.

## Not verified yet

- **PTT in all 10 languages over the air:** the first attempt played the test sentences into the Mac's Bluetooth earbuds instead of its speakers, so the phone heard silence. Re-run `work/ptt_langs_test.py` with the Mac's built-in speakers selected. (English PTT on the realme was already exact.)
- Two phones over a **phone hotspot** (only home Wi-Fi was tested; the demo plan uses a hotspot).
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


## P1 follow-up implementation (after `56b0e04`)

The measurements above remain results for `303ed26`; they are not measurements of these changes.

| Suggestion | Change made | Verification / remaining check |
|---|---|---|
| 1. Cold voice switching | Cache up to two TTS engines and retain them across selected-language changes; keep selected-voice warm-up. | Revisiting either cached engine avoids reloading. First-ever loads and evicted engines remain cold. Measure memory and alternating-language latency again. |
| 2. Playback startup | Build AudioTrack with `PERFORMANCE_MODE_LOW_LATENCY`; log the actual granted mode and sample rate. | Compiles for API 26+. Re-measure ready-to-playback delay; the OS may decline the requested mode. |
| 3. STT behind TTS | Separate bounded STT and TTS executors, with each native object confined to its owner. | A regression test blocks TTS and verifies STT still completes while the next TTS task remains queued. CPU contention still needs device measurement. |
| 4. Unused libraries | Exclude `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so`. | Inspected ELF `DT_NEEDED` in both the AAR and resulting APK: JNI needs ONNX Runtime, not either removed library. APK contains only those two required native libraries. Removes 4,905,856 uncompressed native bytes. Device startup/STT/TTS/VAD smoke test still required. |
| 5. Permission recovery | Check permission before model/link readiness; request on user action, provide rationale/retry and an app Settings shortcut when requests are blocked. | Retest deny once, deny again, grant in Settings, return and press PTT. No automatic recording after permission grant. |
| 6. Continuous switch | Immediately return the switch to off if permission is missing and explain how to grant it. | Retest first launch, denial and later grant. |
| 7. Local mic test | Add start/stop RMS meter independent of models and link. No transcription, stored audio or network sending. Stop on playback/background/language switch. | Unit-tested silence, -60/-30 dBFS, full scale, clipping and invalid input. Device capture/UI still needs a check. |

Validation here: `assembleDebug`, `testDebugUnitTest` (9 tests), and `lintDebug` all pass; lint has 0 errors. Debug APK is approximately 38.7 MB including the newer Material 3 UI dependencies, so it is not directly comparable to the old 33 MB pre-Material build. No phone was attached during this follow-up. Retest rotation and the two real phones over hotspot as already listed above.


### Device picker follow-up

Manual address entry is replaced by Android DNS-SD discovery (`_itantra._tcp`). Tap **Host** on phone A, then **Scan for devices** and select A on phone B. The app resolves the address and port; no user entry is needed. The existing fixed host port remains compatible with laptop test tools.

Additional regression checklist (pending real phones): verify discovery both directions over hotspot; identify two hosts with the same model by their distinct suffixes; remove a host while scanning; select a host that has gone away; rescan; background/foreground both peers; confirm a connected host is no longer advertised. On isolated/multicast-blocked Wi-Fi the app should show the empty/error guidance. A JVM socket test verifies that joining uses the discovered port instead of assuming 26173. Build, lint (0 errors), and all 10 JVM tests pass for this update; radio discovery still requires the real-phone checks above.


### Bluetooth option follow-up

A Wi-Fi/Bluetooth selector now routes the same speech, alert and ping frames over either TCP or secure RFCOMM. Bluetooth has paired/nearby-device selection, Nearby devices permission handling (legacy Location permission for scanning), enable/discoverability system prompts, and a 30-second connection timeout. The host advertises the iTantra RFCOMM UUID; Android handles pairing. Switching transports closes the old link and cancels pending normal speech. No phone is attached, so Bluetooth radio/pairing behavior is not claimed as verified.

Required two-phone regression: select Bluetooth on both, Host/approve discoverability, Scan/select/pair; exchange speech and alerts both ways; ping; repeat with already-paired devices; cancel pairing; deny/regrant permissions; turn Bluetooth off during scan/connect/playback; select a non-iTantra device; let discoverability expire then re-host; switch back to Wi-Fi while inference is queued. Check Android 8–11 scan behavior with Location services off and on, and Android 12+ Nearby devices denial. Shared stream tests cover fragmented speech/alert/ping packets, truncated input and CRC rejection followed by a valid message; these do not substitute for Bluetooth hardware tests.

Bluetooth follow-up automated validation: debug APK build succeeds, all 13 JVM tests pass, and lint reports 0 errors. Physical Bluetooth and Wi-Fi discovery tests remain pending.
