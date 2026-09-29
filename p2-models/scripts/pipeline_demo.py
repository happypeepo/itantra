#!/usr/bin/env python3
"""
The whole iTantra pipeline on one laptop, with two "phones" talking over a real
TCP socket. Use it to prove the models work together before the app exists,
and to get per-stage latency and bitrate numbers.

  PHONE A (sender)                                   PHONE B (receiver)
  wav file -> VAD -> STT -> frame --TCP localhost--> frame -> TTS -> wav file
                                                     alert id -> pre-made WAV

Push-to-talk (the whole file is one message, released at the end):
    python pipeline_demo.py --manifest manifest.json --models models \
        --wav real_audio/te/clip.wav --lang te --mode ptt

Continuous (VAD cuts the audio into sentences and sends each one):
    python pipeline_demo.py ... --mode continuous

Alert, sent from a Hindi phone, heard on a Tamil phone (needs render_alerts.py first):
    python pipeline_demo.py --manifest manifest.json --models models \
        --alert evacuate --lang hi --recv-lang ta --alerts-dir alerts

Timing per message:
    endpoint   continuous mode: how long VAD waits in silence before deciding you stopped
    stt        speech -> text
    link       frame build + send + receive + decode (localhost; a real link adds its delay)
    tts_first  text -> first audio ready to play
    e2e        endpoint + stt + link + tts_first  = "stopped talking" -> "other phone speaks"
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
import frame as fr  # noqa: E402
from common import load_json, make_stt, make_tts, read_char_vocab, sanitize, to_16k, transcribe  # noqa: E402

HERE = Path(__file__).resolve().parent.parent


class Receiver(threading.Thread):
    """Phone B: listens on a socket, speaks what arrives."""

    def __init__(self, man, models: Path, out: Path, recv_lang: str, alerts_dir: Path | None, threads: int):
        super().__init__(daemon=True)
        self.man, self.models, self.out, self.recv_lang = man, models, out, recv_lang
        self.alerts_dir, self.threads = alerts_dir, threads
        self.by_wire = {L["wire_id"]: (code, L) for code, L in man["languages"].items()}
        self.tts_cache: dict[str, tuple] = {}
        self.results: list[dict] = []
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.done = threading.Event()

    def voice(self, code: str):
        eng_name = self.man["languages"][code]["tts"]["engine"]
        if eng_name not in self.tts_cache:
            eng = self.man["tts_engines"][eng_name]
            vocab = read_char_vocab(self.models / eng["tokens"]) if eng["frontend"] == "characters" else None
            self.tts_cache[eng_name] = (make_tts(eng, self.models, self.threads), vocab)
        return self.tts_cache[eng_name]

    def preload(self, code: str) -> None:
        tts, _ = self.voice(code)
        tts.generate("a")  # warm-up so the first real message isn't slow

    def run(self) -> None:
        import sherpa_onnx

        conn, _ = self.srv.accept()
        while True:
            raw = fr.read_frame(conn)
            if raw is None:
                break
            t_recv = time.perf_counter()
            try:
                msg = fr.decode(raw, lambda w: self.by_wire[w][1]["script"])
            except ValueError as e:
                self.results.append({"error": str(e)})
                continue
            t_decoded = time.perf_counter()
            res = {"seq": msg["seq"], "frame_bytes": len(raw), "t_recv": t_recv, "t_decoded": t_decoded}

            if msg["type"] in (fr.T_SPEECH, fr.T_SPEECH_PACKED):
                code, L = self.by_wire[msg["wire_id"]]
                tts, vocab = self.voice(code)
                v = L["tts"]
                g = sherpa_onnx.GenerationConfig()
                g.sid, g.speed = int(v.get("sid", 0)), float(v.get("speed", 1.0))
                if v.get("emotion_id") is not None:
                    g.extra = {"emotion_id": str(v["emotion_id"])}
                first = {}

                def cb(samples, progress, _f=first):
                    _f.setdefault("t", time.perf_counter())
                    return 1

                audio = tts.generate(sanitize(msg["text"], vocab), g, cb)
                t_end = time.perf_counter()
                wav = self.out / f"received_{msg['seq']:03d}_{code}.wav"
                sf.write(str(wav), np.asarray(audio.samples, dtype=np.float32), audio.sample_rate)
                res.update(kind="speech", lang=code, text=msg["text"], wav=str(wav),
                           tts_first_s=first.get("t", t_end) - t_decoded, tts_total_s=t_end - t_decoded,
                           audio_s=len(audio.samples) / audio.sample_rate)
            elif msg["type"] == fr.T_ALERT:
                index = json.loads((self.alerts_dir / "index.json").read_text())
                name = index[str(msg["alert_id"])]
                path = self.alerts_dir / self.recv_lang / f"{name}.wav"
                x, sr = sf.read(str(path), dtype="float32")  # loading = ready to play
                res.update(kind="alert", alert=name, played_in=self.recv_lang, wav=str(path),
                           ready_s=time.perf_counter() - t_decoded, audio_s=len(x) / sr)
            self.results.append(res)
        self.done.set()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", default=str(HERE / "manifest.json"))
    ap.add_argument("--models", required=True)
    ap.add_argument("--lang", required=True, help="language spoken on phone A")
    ap.add_argument("--recv-lang", default="", help="language phone B is set to (for alerts); default = --lang")
    ap.add_argument("--wav", help="recording to send")
    ap.add_argument("--mode", choices=["ptt", "continuous"], default="ptt")
    ap.add_argument("--alert", help="send this alert (name from alerts.json) instead of speech")
    ap.add_argument("--alerts-dir", default="alerts")
    ap.add_argument("--min-silence", type=float, default=0.5, help="continuous mode: seconds of silence that end a sentence")
    ap.add_argument("--pre-roll", type=float, default=0.3, help="continuous mode: seconds kept from before each detected sentence")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out", default="pipeline_out")
    a = ap.parse_args()

    man, models, out = load_json(a.manifest), Path(a.models), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    L = man["languages"][a.lang]
    recv_lang = a.recv_lang or a.lang

    rx = Receiver(man, models, out, recv_lang, Path(a.alerts_dir), a.threads)
    rx.start()
    tx = socket.create_connection(("127.0.0.1", rx.port))
    tx.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sent: list[dict] = []

    if a.alert:
        alerts = load_json(HERE / "alerts.json")["alerts"]
        t0 = time.perf_counter()
        f = fr.encode_alert(alerts[a.alert]["id"], L["wire_id"], 0)
        tx.sendall(f)
        sent.append({"seq": 0, "t_sent": t0, "frame_bytes": len(f)})
    else:
        print(f"loading STT ({a.lang}) and TTS on the receiver ...", flush=True)
        rec = make_stt(L["stt"], models, a.threads)
        transcribe(rec, np.zeros(16000, np.float32), 16000)  # warm-up
        rx.preload(a.lang)

        x, sr = sf.read(a.wav, dtype="float32", always_2d=True)
        x = to_16k(x[:, 0], sr)
        segments = []  # (samples, speech_end_s, endpoint_delay_s)
        if a.mode == "ptt":
            segments.append((x, len(x) / 16000, 0.0))  # button release = end of message
        else:
            import sherpa_onnx
            cfg = sherpa_onnx.VadModelConfig()
            cfg.silero_vad.model = str(models / man["vad"]["model"])
            cfg.silero_vad.min_silence_duration = a.min_silence
            cfg.sample_rate = 16000
            vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
            win = cfg.silero_vad.window_size
            pre = int(16000 * a.pre_roll)
            tail = np.zeros(16000 * 2, np.float32)  # speaker goes quiet (a real mic keeps streaming)
            stream = np.concatenate([x, tail])

            def take(now: float) -> None:
                while not vad.empty():
                    s = vad.front
                    # VAD only starts a segment once it's sure you're talking, which can
                    # clip the first word. Keep a little audio from just before it.
                    start = max(0, s.start - pre)
                    seg = np.concatenate([stream[start:s.start], np.asarray(s.samples, np.float32)])
                    end = (s.start + len(s.samples)) / 16000
                    segments.append((seg, end, max(0.0, now - end)))
                    vad.pop()

            for i in range(0, len(stream), win):
                vad.accept_waveform(stream[i:i + win])
                take((i + win) / 16000)  # audio clock: when this chunk arrived
            vad.flush()
            take(len(stream) / 16000)

        for seq, (seg, speech_end, endpoint) in enumerate(segments):
            t0 = time.perf_counter()
            text = transcribe(rec, seg, 16000)
            t1 = time.perf_counter()  # link timing starts here: encode + send + receive + decode
            f = fr.encode_speech(text, L["wire_id"], L["script"], seq)
            tx.sendall(f)
            utf8 = len(text.encode("utf-8")) + 8
            dur = len(seg) / 16000
            sent.append({"seq": seq, "text": text, "speech_s": round(dur, 2), "endpoint_s": endpoint,
                         "stt_s": t1 - t0, "t_sent": t1, "frame_bytes": len(f),
                         "utf8_frame_bytes": utf8, "bps": round(8 * len(f) / max(dur, 1e-6), 1),
                         "utf8_bps": round(8 * utf8 / max(dur, 1e-6), 1)})
    tx.close()
    rx.done.wait(timeout=120)

    by_seq = {r.get("seq"): r for r in rx.results}
    report = []
    for s in sent:
        r = by_seq.get(s["seq"], {})
        link = r.get("t_decoded", s["t_sent"]) - s["t_sent"]
        row = {k: v for k, v in s.items() if not k.startswith("t_")}
        row["link_s"] = link
        if r.get("kind") == "speech":
            row.update(tts_first_s=r["tts_first_s"], tts_total_s=r["tts_total_s"], heard=r["wav"],
                       e2e_s=s["endpoint_s"] + s["stt_s"] + link + r["tts_first_s"])
        elif r.get("kind") == "alert":
            row.update(alert=r["alert"], played_in=r["played_in"], ready_s=r["ready_s"], heard=r["wav"],
                       e2e_s=link + r["ready_s"])
        report.append({k: (round(v, 3) if isinstance(v, float) else v) for k, v in row.items()})

    for row in report:
        print(json.dumps(row, ensure_ascii=False))
    (out / "pipeline_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
