import json
import tempfile
import unittest
from pathlib import Path

from variant import VariantError, normalize, validate


class VariantTest(unittest.TestCase):
    def test_old_qwen_variants_get_bounded_audio_and_generation_headroom(self):
        for schema in (1, 2):
            v = validate({"schema": schema, "family": "qwen3"})
            self.assertEqual(1024, v["qwen3"]["max_total_len"])
            self.assertEqual(256, v["qwen3"]["max_new_tokens"])
            self.assertEqual(40, v["cutting"]["max_chunk_s"])

    def test_qwen_rejects_unbounded_or_underbudgeted_audio(self):
        for extra in ({}, {"cutting": {"max_chunk_s": 41}},
                      {"cutting": {"max_chunk_s": 40}, "qwen3": {"max_total_len": 512}},
                      {"cutting": {"max_chunk_s": 40}, "qwen3": {"max_new_tokens": 128}},
                      {"cutting": {"max_chunk_s": 40}, "qwen3": {"max_total_len": "1024"}}):
            with self.subTest(extra=extra), self.assertRaises(VariantError):
                validate({"schema": 3, "family": "qwen3", **extra})

    def test_explicit_qwen_budgets_and_other_family_defaults(self):
        v = validate({"schema": 3, "family": "qwen3", "cutting": {"max_chunk_s": 25},
                      "qwen3": {"max_total_len": 2048, "max_new_tokens": 512}})
        self.assertEqual(2048, v["qwen3"]["max_total_len"])
        self.assertEqual(512, v["qwen3"]["max_new_tokens"])
        for schema in (1, 2, 3):
            self.assertEqual(0, validate({"schema": schema})["cutting"]["max_chunk_s"])

    def test_normalize_writes_every_default_explicitly_for_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            source, target = Path(temp) / "in.json", Path(temp) / "out.json"
            source.write_text(json.dumps({"schema": 3, "name": "gate", "model": "parakeet-v3-fp32"}))
            normalize(source, target)
            staged = json.loads(target.read_text())
            self.assertIs(False, staged["runtime"]["profile"])
            self.assertEqual("parakeet-v3-fp32", staged["model"])


if __name__ == "__main__":
    unittest.main()
