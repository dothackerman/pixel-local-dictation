"""Synthetic stream seam only: never loads Moonshine models or personal recordings."""
from types import SimpleNamespace
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave

import numpy as np

from moonshine_streaming_lab import ROOT, replay, run


class FakeStream:
    def __init__(self, fail=False):
        self.packets = []
        self.calls = []
        self.fail = fail

    def add_listener(self, listener):
        self.listener = listener

    def start(self):
        self.calls.append("start")

    def add_audio(self, packet, rate):
        self.calls.append("audio")
        assert rate == 16000
        self.packets.append(packet.tolist())
        event = type("LineTextChanged", (), {})()
        event.line = SimpleNamespace(line_id=4, start_time=0.0, text="draft")
        self.listener(event)

    def stop(self):
        self.calls.append("stop")
        if self.fail:
            return None
        return SimpleNamespace(lines=[
            SimpleNamespace(line_id=9, start_time=2.0, text="Straße", is_complete=True),
            SimpleNamespace(line_id=4, start_time=0.0, text="First.", is_complete=True)])

    def close(self):
        self.calls.append("close")


class FakeTranscriber:
    def __init__(self, fail=False):
        self.streams = []
        self.fail = fail

    def create_stream(self, update_interval):
        assert update_interval == 0.5
        stream = FakeStream(self.fail)
        self.streams.append(stream)
        return stream


class ReplayTests(unittest.TestCase):
    def test_partial_packet_once_in_order_and_events_track_submitted_audio(self):
        model = FakeTranscriber()
        clock = iter([10.0, 10.1, 10.2, 10.3, 10.4, 10.5])
        result = replay(model, np.arange(1283, dtype=np.float32), lambda: next(clock))
        stream = model.streams[0]
        self.assertEqual([len(p) for p in stream.packets], [640, 640, 3])
        self.assertEqual(sum(stream.packets, []), list(range(1283)))
        self.assertEqual(stream.calls, ["start", "audio", "audio", "audio", "stop", "close"])
        self.assertEqual([e["submittedSamples"] for e in result["events"]], [640, 1280, 1283])
        self.assertEqual([e["type"] for e in result["events"]], ["LineTextChanged"] * 3)
        self.assertEqual(result["text"], "First. Strasse")
        self.assertEqual(result["nativeText"], "First. Straße")
        self.assertEqual(result["submittedSamples"], 1283)
        self.assertAlmostEqual(result["batchStopToFinalSeconds"], 0.1)

    def test_next_input_gets_fresh_stream_and_no_audio_leaks(self):
        model = FakeTranscriber()
        replay(model, np.array([3, 4], dtype=np.float32))
        replay(model, np.array([9], dtype=np.float32))
        self.assertEqual(len(model.streams), 2)
        self.assertEqual(model.streams[0].packets, [[3, 4]])
        self.assertEqual(model.streams[1].packets, [[9]])

    def test_native_stop_failure_closes_stream_and_does_not_succeed(self):
        model = FakeTranscriber(fail=True)
        with self.assertRaisesRegex(RuntimeError, "native stream failed"):
            replay(model, np.array([1], dtype=np.float32))
        self.assertEqual(model.streams[0].calls[-1], "close")

    def test_failed_run_preserves_completed_take_and_current_events_without_overwrite(self):
        class FailingStream(FakeStream):
            def add_audio(self, packet, rate):
                super().add_audio(packet, rate)
                raise ValueError("native add failed")

            def close(self):
                super().close()
                raise OSError("stream cleanup failed")

        class ScreenTranscriber(FakeTranscriber):
            def __init__(self, **kwargs):
                super().__init__()

            def create_stream(self, update_interval):
                if not self.streams:
                    return super().create_stream(update_interval)
                stream = FailingStream()
                self.streams.append(stream)
                return stream

            def close(self):
                raise OSError("model cleanup failed")

        def read_synthetic_wav(path):
            with wave.open(str(path), "rb") as reader:
                return np.frombuffer(reader.readframes(reader.getnframes()),
                                     dtype=np.int16).astype(np.float32) / 32768

        # Isolate the WAV seam: importing pipeline also imports native Sherpa.
        pipeline = SimpleNamespace(read_wav=read_synthetic_wav)
        native = SimpleNamespace(Transcriber=ScreenTranscriber)
        api = SimpleNamespace(ModelArch=SimpleNamespace(SMALL_STREAMING=4))
        with tempfile.TemporaryDirectory(dir=ROOT / "corpus") as folder:
            folder = Path(folder)
            wav = folder / "synthetic.wav"
            with wave.open(str(wav), "wb") as writer:
                writer.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                writer.writeframes(b"\x00\x00" * 3)
            inputs = [dict(kind="take", file=name, lang="en", path=str(wav), samples=3)
                      for name in ("completed", "failed")]
            manifest = folder / "manifest.json"
            manifest.write_text(json.dumps(dict(schema=1, modelArch=4, feedSamples=640,
                repetitions=1, files=[], runtime={}, mode="synthetic-test",
                models={"en": {"path": "unused"}}, inputs=inputs)))
            output = folder / "failure.json"
            with patch.dict(sys.modules, {"pipeline": pipeline, "moonshine_voice": native,
                                          "moonshine_voice.moonshine_api": api}):
                with self.assertRaisesRegex(RuntimeError, "native add failed") as caught:
                    run(manifest, "en", output)
                saved = output.read_bytes()
                with self.assertRaises(FileExistsError):
                    run(manifest, "en", output)
            self.assertEqual(output.read_bytes(), saved)
            report = json.loads(saved)
            self.assertEqual(report["status"], "failed")
            self.assertNotIn("scores", report)
            self.assertEqual([take["file"] for take in report["takes"]], ["completed"])
            failure = report["failure"]
            self.assertEqual(failure["input"]["file"], "failed")
            self.assertEqual(failure["stream"]["errorType"], "ValueError")
            self.assertEqual(failure["stream"]["message"], "native add failed")
            self.assertEqual(failure["stream"]["submittedSamples"], 3)
            self.assertEqual(failure["stream"]["events"][0]["text"], "draft")
            self.assertEqual(failure["stream"]["closeError"]["message"], "stream cleanup failed")
            self.assertEqual(report["closeError"]["message"], "model cleanup failed")
            self.assertIsInstance(caught.exception.__cause__, ValueError)


if __name__ == "__main__":
    unittest.main()
