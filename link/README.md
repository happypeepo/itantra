# link/: phone-to-phone link, metrics, demo (owner: P3)

The spec is in `plan.md` §6C. The reference implementation is `p2-models/scripts/frame.py` (Python). The Kotlin version (`android/app/.../link/Frame.kt`) **must match it byte for byte**. Check it against `test_vectors.json` in this folder.

## Connection

- One phone taps **Host**: it opens a TCP server on a fixed port and shows its IP.
- The other taps **Join** and types that IP.
- Use one phone's **hotspot with mobile data off**. A shared router can silently block phones from talking to each other. If the phones can't connect, swap which phone hosts.

## Frame format

```
[type 1B][lang 1B][seq 2B][len 2B][payload][CRC16 2B]      8 bytes of overhead, big-endian

type 0x01  speech, payload = UTF-8 text
type 0x11  speech, payload = packed text (1 byte per letter, see below)
type 0x02  alert,  payload = 1-byte alert ID (alerts/index.json)
type 0x03  ping,   empty payload
lang       wire_id from manifest.json: hi 0, en 1, bn 2, gu 3, kn 4, ml 5, mr 6, or 7, ta 8, te 9
CRC        CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over every byte before it
```

**Packed text:** each Indic script sits in its own 128-character Unicode block, and `lang` says which one. So `0x00–0x7F` is plain ASCII, and `0x80–0xFF` is `(code point − script block start) + 0x80`. Block starts: Devanagari 0x0900 (hi, mr), Bengali 0x0980, Gujarati 0x0A80, Oriya 0x0B00, Tamil 0x0B80, Telugu 0x0C00, Kannada 0x0C80, Malayalam 0x0D00. If any character doesn't fit, send that message as UTF-8 (type 0x01). The sender picks whichever is smaller.

**Bad CRC or bad length → drop the frame and show it in the log. Never speak it.**

**Alerts send only the 1-byte ID.** The receiver plays that alert in **its own** chosen language, from the pre-rendered WAVs.

## Test vectors (`test_vectors.json`)

Generated from `frame.py`. Your Kotlin encoder must produce these exact hex strings, and your decoder must return the same text. One example is a frame with a flipped bit, which must be rejected. Example: "तुरंत इलाका खाली करें" is 57 bytes in UTF-8 but only 21 bytes packed (a 29-byte frame).

## Metrics to log per message (plan.md §6C)

| Where | What |
|---|---|
| Sender | speech end → STT done (ms), STT RTF, frame bytes |
| Receiver | frame received → first sound (ms), TTS RTF |
| Both | ping round-trip time |

**End-to-end** = sender time + ½ ping RTT + receiver time-to-first-sound. That way the two phone clocks never need to agree.
