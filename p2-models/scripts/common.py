"""
Shared helpers for the P2 model scripts.

Nothing here downloads anything. Everything runs offline on the laptop.
Requires: pip install "sherpa-onnx>=1.13.5" onnx onnxruntime soundfile numpy
"""
from __future__ import annotations

import hashlib
import json
import unicodedata
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Scripts. Used to check that STT output and TTS vocab are in the right script.
# ---------------------------------------------------------------------------
SCRIPT_BLOCKS = {
    "Devanagari": (0x0900, 0x097F),  # Hindi, Marathi
    "Bengali": (0x0980, 0x09FF),
    "Gujarati": (0x0A80, 0x0AFF),
    "Oriya": (0x0B00, 0x0B7F),       # Odia
    "Tamil": (0x0B80, 0x0BFF),
    "Telugu": (0x0C00, 0x0C7F),
    "Kannada": (0x0C80, 0x0CFF),
    "Malayalam": (0x0D00, 0x0D7F),
}

# Sentence-ending marks. sherpa-onnx splits on . ? ! and a trailing one can
# produce a tiny empty extra sentence that plays as noise. The danda marks
# are stripped too because they end sentences in several Indic scripts.
END_MARKS = set(".?!\u0964\u0965")  # . ? ! । ॥

ZERO_WIDTH = {"\u200c", "\u200d"}  # ZWNJ / ZWJ vary between systems


def load_json(path: str | Path) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def sha256(path: str | Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# tokens.txt helpers
# ---------------------------------------------------------------------------
def count_token_lines(path: str | Path) -> int:
    with open(path, encoding="utf-8") as f:
        return sum(1 for line in f if line.rstrip("\n") != "")


def read_char_vocab(path: str | Path) -> set[int]:
    """For character-frontend TTS (rasa, MMS): the set of code points the
    voice knows. Each line of tokens.txt is '<char> <id>'."""
    vocab: set[int] = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            idx = line.rfind(" ")
            if idx <= 0:
                continue
            tok = line[:idx]
            if len(tok) == 1:
                vocab.add(ord(tok))
    return vocab


# ---------------------------------------------------------------------------
# Text cleanup before TTS (the Kotlin TextSanitizer does the same thing)
# ---------------------------------------------------------------------------
def _keep(ch: str, vocab: set[int]) -> str:
    """Keep a character if the voice knows it. If not, try its decomposed
    parts (e.g. a letter with a nukta), and keep whichever parts it knows."""
    if ch.isspace() or ord(ch) in vocab:
        return ch
    parts = unicodedata.normalize("NFD", ch)
    if parts != ch:
        return "".join(p for p in parts if ord(p) in vocab)
    return ""


def sanitize(text: str, vocab: set[int] | None = None) -> str:
    t = text.strip()
    while t and (t[-1] in END_MARKS or t[-1].isspace()):
        t = t[:-1]
    if vocab:
        t = "".join(_keep(c, vocab) for c in t)
    return " ".join(t.split())


def dropped_chars(text: str, vocab: set[int]) -> set[str]:
    """Characters that sanitize() would throw away for this voice."""
    out = set()
    for c in text:
        if c.isspace() or c in END_MARKS:
            continue
        if _keep(c, vocab) == "":
            out.add(c)
    return out


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------
def norm_for_cer(text: str) -> str:
    t = unicodedata.normalize("NFC", text)
    t = "".join(c for c in t if c not in ZERO_WIDTH)
    t = "".join(c for c in t if not unicodedata.category(c).startswith("P"))
    return " ".join(t.lower().split())


def cer(ref: str, hyp: str) -> float:
    """Character error rate. 0.0 = identical, 1.0 = everything wrong."""
    r, h = norm_for_cer(ref), norm_for_cer(hyp)
    if not r:
        return 0.0 if not h else 1.0
    prev = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, hc in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc))
        prev = cur
    return prev[-1] / len(r)


def wer(ref: str, hyp: str) -> float:
    """Word error rate. 0.0 = identical."""
    r, h = norm_for_cer(ref).split(), norm_for_cer(hyp).split()
    if not r:
        return 0.0 if not h else 1.0
    prev = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, hw in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rw != hw))
        prev = cur
    return prev[-1] / len(r)


def script_share(text: str, script: str) -> float:
    """Fraction of letters in `text` that belong to `script`."""
    letters = [c for c in text if c.isalpha() or unicodedata.category(c).startswith("M")]
    if not letters:
        return 0.0
    if script == "Latin":
        return sum(c.isascii() for c in letters) / len(letters)
    lo, hi = SCRIPT_BLOCKS[script]
    return sum(lo <= ord(c) <= hi for c in letters) / len(letters)


# ---------------------------------------------------------------------------
# sherpa-onnx wrappers
# ---------------------------------------------------------------------------
def make_tts(engine: dict, models: Path, num_threads: int = 2):
    import sherpa_onnx

    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(models / engine["model"]),
        tokens=str(models / engine["tokens"]),
        lexicon="",
        data_dir=str(models / engine["data_dir"]) if engine.get("data_dir") else "",
    )
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=num_threads, provider="cpu"),
        max_num_sentences=1,
    )
    if not cfg.validate():
        raise RuntimeError(f"invalid TTS config for {engine['model']}")
    return sherpa_onnx.OfflineTts(cfg)


def tts_generate(tts, text: str, sid: int = 0, speed: float = 1.0, emotion_id: int | None = None):
    """Returns (samples float32, sample_rate). emotion_id is only for rasa.
    It is passed through GenerationConfig.extra, exactly like the Kotlin API."""
    import sherpa_onnx

    g = sherpa_onnx.GenerationConfig()
    g.sid = int(sid)
    g.speed = float(speed)
    if emotion_id is not None:
        g.extra = {"emotion_id": str(int(emotion_id))}
    audio = tts.generate(text, g)
    return np.asarray(audio.samples, dtype=np.float32), int(audio.sample_rate)


def make_stt(stt: dict, models: Path, num_threads: int = 2):
    import sherpa_onnx

    if stt["type"] == "nemo_ctc":
        return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=str(models / stt["model"]),
            tokens=str(models / stt["tokens"]),
            num_threads=num_threads,
            sample_rate=16000,
            feature_dim=80,
            decoding_method="greedy_search",
        )
    if stt["type"] == "omnilingual":
        # One model for all languages (Meta Omnilingual ASR, Apache-2.0). No language hint needed.
        return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
            model=str(models / stt["model"]),
            tokens=str(models / stt["tokens"]),
            num_threads=num_threads,
        )
    if stt["type"] == "whisper":
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(models / stt["encoder"]),
            decoder=str(models / stt["decoder"]),
            tokens=str(models / stt["tokens"]),
            language=stt.get("language", "en"),
            task="transcribe",
            num_threads=num_threads,
        )
    raise ValueError(f"unknown stt type {stt['type']}")


def to_16k(samples: np.ndarray, sr: int) -> np.ndarray:
    if sr == 16000:
        return samples
    n_out = int(round(len(samples) * 16000 / sr))
    x_old = np.linspace(0.0, 1.0, num=len(samples), endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=n_out, endpoint=False)
    return np.interp(x_new, x_old, samples).astype(np.float32)


def transcribe(rec, samples: np.ndarray, sr: int) -> str:
    s = rec.create_stream()
    s.accept_waveform(16000, to_16k(samples, sr))
    rec.decode_stream(s)
    return s.result.text.strip()
