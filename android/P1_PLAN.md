# Person 1 implementation plan

Scope: PDF §6A and the current repository handoffs. The checked-in P2 manifest supersedes the older PDF model table. P2 still owns model preparation and native-speaker-approved alert WAVs; P3 owns demo measurements and slides.

1. Build an arm64 Android app with sherpa-onnx 1.13.8; load models from external app storage.
2. Integrate manifest-driven language switching, one STT and one TTS engine, serialized inference and explicit cleanup.
3. Add 16 kHz hold-to-talk and continuous Silero VAD with 300 ms of actual microphone pre-roll.
4. Add sample-rate-aware FIFO playback, mic suppression, priority alarm WAV playback, exclusive focus and volume restoration.
5. Add language/mode/alert controls, host/join integration, message logs and per-message timings.
6. Verify frame parity, malformed inputs, audio buffer/WAV behavior, build and lint; document hardware acceptance checks.
7. Present the result and request permission before committing.

## Implementation outcome

- Steps 1-5 implemented, including minimal P3 transport integration.
- Build and lint pass; seven JVM tests cover protocol/audio and real loopback transport.
- No phones/emulators attached. Model binaries and approved alert WAVs are absent, so device gates and measured audio performance remain unverified. See the README acceptance checklist.
- User approved committing and pushing the implementation after review.
