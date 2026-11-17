"""The one host rule for rotated lab receipts; synthetic files only."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from lab_receipts import generations


class GenerationsTests(unittest.TestCase):
    def test_a_pulled_previous_generation_comes_first(self):
        with TemporaryDirectory() as directory:
            current = Path(directory) / "rotation-probe.jsonl"
            previous = Path(directory) / "rotation-probe.prev.jsonl"
            current.write_text("")
            self.assertEqual([current], generations(current))
            previous.write_text("")
            self.assertEqual([previous, current], generations(str(current)))


if __name__ == "__main__":
    unittest.main()
