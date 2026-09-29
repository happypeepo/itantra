# work/: P2 measurement runs (T4, T5, STT re-make)

Scripts that produced the numbers quoted in `p2-models/README.md` §16 and the P2 reports. Machine for all of them: **Apple M4 (4 performance + 6 efficiency cores), arm64, 24 GB, macOS 26.6.2, sherpa-onnx 1.13.8, onnxruntime 1.30.0, 2 threads.** These are laptop numbers, not phone numbers.

| File | What |
|---|---|
| `t4_rasa.py` | T4: rasa bn/kn/ml/mr/ta/te × styles 0/4/10, vs MMS kn/mr/ta/te on the same machine. Results in `../t4_out/t4_results.json`, WAVs in `../t4_out/listen/` (not in git). |
| `t5_run.sh` | T5: IndicConformer hi+or as fp32 / MatMul-INT8 / Conv-INT8 / INT4 (+ betterflow hi). Results in `t5/bench/results_*.json`. |
| `remake_stt.sh` | Re-makes bn gu kn ml mr ta te from OpenVoiceOS fp32 (MatMul-only INT8 → `models/`, INT4 → `work/int4/`, not in git). |

Main findings:
- **Conv INT8 (betterflow) is ~3× slower than MatMul-only INT8**: RTF ~0.09 vs ~0.03, with the same accuracy. That's why all 9 Indic languages now use OpenVoiceOS + `patch_stt.py`.
- **INT4 is 20% smaller** (149 vs 186 MB) and uses ~70 MB less RAM, with the same round-trip CER. It's slightly slower (RTF 0.040 vs 0.030). Adopt it only after an on-phone check.
- **rasa is not faster than MMS**: RTF ~0.6 vs ~0.5. TTS (not STT) dominates latency for the 8 non-Piper languages.

## Phone benchmark (`phone_bench.py`)

This runs the models **on a real Android phone** over USB. It uses sherpa-onnx's official arm64 command-line tools, so it needs no app and no Termux. Each run records the device, chip, sherpa-onnx/ONNX Runtime versions and battery temperature.

Setup (once per phone):

```bash
# 1. official tools, same version as the app (156 MB)
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2
tar xjf sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2
P=/data/local/tmp/itantra
adb shell mkdir -p $P/bin $P/int4 $P/audio
adb push sherpa-onnx-v1.13.8-android-aarch64-termux-static/bin/sherpa-onnx-{version,offline,offline-tts,vad} $P/bin/
# 2. the tools need the C++ runtime: take it from the Android NDK
adb push $ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so $P/bin/
adb shell chmod 755 $P/bin/*
# 3. models, INT4 copies, and the laptop benchmark's test clips
adb push models $P/
for l in hi ta; do adb push work/int4/$l $P/int4/; done
for d in bench_out/tts/*/; do adb shell mkdir -p $P/audio/$(basename $d); adb push $d*.wav $P/audio/$(basename $d)/; done
```

Run with `python work/phone_bench.py --threads 2` (edit `ADB` at the top if adb isn't at that path). Results go to `phone_out/phone_results.json`. **Clean up afterwards:** `adb shell rm -rf /data/local/tmp/itantra`.

The phone runs these tools reporting **ONNX Runtime 1.28.2** (the laptop has 1.30.0).
