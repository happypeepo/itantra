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
