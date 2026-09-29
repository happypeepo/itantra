"""
The message that goes over the link between the two phones (plan.md §6C),
plus a lossless compact encoding for Indic text.

Frame:
    [type 1B][lang 1B][seq 2B][len 2B][payload][CRC16 2B]      (8 bytes of overhead)

    type 0x01  speech, payload is UTF-8 text
    type 0x11  speech, payload is 1 byte per character ("packed", see below)
    type 0x02  alert,  payload is 1 byte alert ID
    type 0x03  ping

Packed text: UTF-8 spends 3 bytes on every Indic letter. Each Indic script lives
in a 128-character Unicode block, and the language byte already says which one,
so every letter fits in 1 byte:
    0x00-0x7F  plain ASCII (space, digits, punctuation, English letters)
    0x80-0xFF  (letter - start of this language's script block) + 0x80
If any character doesn't fit, the sender just uses UTF-8 for that message.
The P3 Kotlin version must match this byte-for-byte.
"""
from __future__ import annotations

import struct

T_SPEECH, T_SPEECH_PACKED, T_ALERT, T_PING = 0x01, 0x11, 0x02, 0x03

SCRIPT_BASE = {
    "Devanagari": 0x0900, "Bengali": 0x0980, "Gujarati": 0x0A80, "Oriya": 0x0B00,
    "Tamil": 0x0B80, "Telugu": 0x0C00, "Kannada": 0x0C80, "Malayalam": 0x0D00,
}


def crc16(data: bytes) -> int:
    """CRC-16/CCITT-FALSE."""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def pack_text(text: str, script: str) -> bytes | None:
    base = SCRIPT_BASE.get(script)
    if base is None:
        return None
    out = bytearray()
    for ch in text:
        cp = ord(ch)
        if cp < 0x80:
            out.append(cp)
        elif base <= cp < base + 0x80:
            out.append(0x80 + cp - base)
        else:
            return None  # e.g. a danda from another block, ZWJ: fall back to UTF-8
    return bytes(out)


def unpack_text(data: bytes, script: str) -> str:
    base = SCRIPT_BASE[script]
    return "".join(chr(b) if b < 0x80 else chr(base + b - 0x80) for b in data)


def encode_speech(text: str, wire_id: int, script: str, seq: int) -> bytes:
    packed = pack_text(text, script)
    if packed is not None and len(packed) < len(text.encode("utf-8")):
        return _frame(T_SPEECH_PACKED, wire_id, seq, packed)
    return _frame(T_SPEECH, wire_id, seq, text.encode("utf-8"))


def encode_alert(alert_id: int, wire_id: int, seq: int) -> bytes:
    return _frame(T_ALERT, wire_id, seq, bytes([alert_id]))


def _frame(ftype: int, wire_id: int, seq: int, payload: bytes) -> bytes:
    head = struct.pack(">BBHH", ftype, wire_id, seq & 0xFFFF, len(payload))
    body = head + payload
    return body + struct.pack(">H", crc16(body))


def decode(frame: bytes, script_of_wire_id) -> dict:
    """Returns {type, wire_id, seq, text|alert_id}. Raises ValueError on a bad frame."""
    if len(frame) < 8:
        raise ValueError("frame too short")
    ftype, wire_id, seq, n = struct.unpack(">BBHH", frame[:6])
    if len(frame) != 8 + n:
        raise ValueError("length mismatch")
    (crc,) = struct.unpack(">H", frame[-2:])
    if crc != crc16(frame[:-2]):
        raise ValueError("CRC mismatch - dropped")
    payload = frame[6:-2]
    msg = {"type": ftype, "wire_id": wire_id, "seq": seq}
    if ftype == T_SPEECH:
        msg["text"] = payload.decode("utf-8")
    elif ftype == T_SPEECH_PACKED:
        msg["text"] = unpack_text(payload, script_of_wire_id(wire_id))
    elif ftype == T_ALERT:
        msg["alert_id"] = payload[0]
    return msg


def read_frame(sock) -> bytes | None:
    """Read exactly one frame from a TCP socket."""
    head = _recv_exact(sock, 6)
    if head is None:
        return None
    (n,) = struct.unpack(">H", head[4:6])
    rest = _recv_exact(sock, n + 2)
    return None if rest is None else head + rest


def _recv_exact(sock, n: int) -> bytes | None:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf
