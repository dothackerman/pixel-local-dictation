"""Analyzer contract tests use metadata-only synthetic receipts."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.analyze_recovery_timing import analyze


class RecoveryTimingTests(unittest.TestCase):
    def test_reports_source_lag_backlog_stop_wait_and_zero_checkpoint_truth(self):
        rows = [
            dict(phase="scenario", ns=0, fixtureId="fixture-c", sampleRate=16000),
            dict(phase="captureSampleAnchor", ns=1_000_000_000, sampleEnd=16000),
            dict(phase="chunkReady", ns=1_100_000_000, chunkId=0, sampleEnd=16000),
            dict(phase="chunkEnqueue", ns=1_110_000_000, queued=2),
            dict(phase="checkpointQueued", ns=1_200_000_000, revision=1, sampleEnd=16000),
            dict(phase="checkpointDurable", ns=1_300_000_000, revision=1, sampleEnd=16000),
            dict(phase="captureSampleAnchor", ns=2_000_000_000, sampleEnd=32000),
            dict(phase="stopRequested", ns=2_050_000_000, sampleEnd=32000),
            dict(phase="producerStop", ns=2_051_000_000, active=True, queued=1),
            dict(phase="captureStopAnchor", ns=2_060_000_000, sampleEnd=32000),
            dict(phase="checkpointDurable", ns=2_200_000_000, revision=2, sampleEnd=32000),
            dict(phase="checkpointDurable", ns=2_300_000_000, revision=3, sampleEnd=32000),
            dict(phase="finalReady", ns=2_400_000_000),
            dict(phase="takeEnded", ns=2_500_000_000),
        ]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "timing.jsonl"
            path.write_text("\n".join(json.dumps(dict(protocol="recovery-timing-v1", clock="CLOCK_MONOTONIC",
                processId="opaque-p", takeId="opaque-t", **row)) for row in rows))
            result = analyze(path)["takes"][0]
        self.assertEqual("complete", result["status"])
        self.assertEqual("fixture-c", result["fixtureId"])
        self.assertFalse(result["zeroCheckpoint"])
        self.assertEqual(2, result["oldestNewlyDurableAudioLagMs"]["count"])
        self.assertEqual(1300.0, result["initialZeroCheckpointIntervalMs"])
        self.assertEqual(300.0, result["newestNewlyDurableAudioLagMs"]["max"])
        self.assertEqual(1.0, result["atRiskBacklogAtStopSec"])
        self.assertEqual(1.0, result["atRiskBacklogMaxSec"])
        self.assertEqual(350.0, result["decodeStopWaitMs"])
        self.assertEqual(450.0, result["terminalStopWaitMs"])
        self.assertEqual(2, result["maxDecodeQueue"])

    def test_missing_terminal_events_are_incomplete_not_zero_checkpoint(self):
        rows = [dict(phase="captureSampleAnchor", ns=1, sampleEnd=1),
                dict(phase="captureStopAnchor", ns=2, sampleEnd=1),
                dict(phase="stopRequested", ns=3)]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "timing.jsonl"
            path.write_text("\n".join(json.dumps(dict(protocol="recovery-timing-v1", clock="CLOCK_MONOTONIC",
                processId="p", takeId="t", **row)) for row in rows))
            result = analyze(path)["takes"][0]
        self.assertEqual("incomplete_receipts", result["status"])
        self.assertIsNone(result["zeroCheckpoint"])
        self.assertIsNone(result["atRiskBacklogAtStopSec"])

    def test_transcript_or_audio_fields_are_rejected(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "timing.jsonl"
            path.write_text(json.dumps(dict(protocol="recovery-timing-v1", clock="CLOCK_MONOTONIC",
                processId="p", takeId="t", phase="checkpointDurable", ns=1, transcript="private")))
            with self.assertRaisesRegex(ValueError, "forbidden content field"):
                analyze(path)

    def test_truncated_jsonl_is_not_silently_treated_as_missing_checkpoint(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "timing.jsonl"
            path.write_text('{"protocol":"recovery-timing-v1"')
            with self.assertRaisesRegex(ValueError, "invalid receipt"):
                analyze(path)


if __name__ == "__main__":
    unittest.main()
