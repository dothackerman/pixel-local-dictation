import tempfile
import unittest
from pathlib import Path
import runtime_config
import variant

class RuntimeTests(unittest.TestCase):
    def test_stock_and_independent_sessions(self):
        v = variant.validate({"runtime": {"model_arena": False}})
        config = runtime_config.provider(v)
        self.assertIn('EnableCpuMemArena=0', Path(config[4:]).read_text())
        self.assertIn('EnableMemPattern=1', Path(config[4:]).read_text())
        self.assertEqual(runtime_config.provider(v, 'lid'), 'cpu')

    def test_profile_requires_destination_and_uses_distinct_roles(self):
        v = variant.validate({"runtime": {"profile": True}})
        with tempfile.TemporaryDirectory() as directory:
            model = Path(runtime_config.provider(v, profile_dir=directory)[4:]).read_text()
            lid = Path(runtime_config.provider(v, 'lid', directory)[4:]).read_text()
        self.assertIn('/model', model)
        self.assertIn('/lid', lid)

    def test_closed_schema_and_strict_booleans(self):
        for setting in ({'model_arena': 0}, {'unknown': False}):
            with self.assertRaises(variant.VariantError):
                variant.validate({'runtime': setting})

    def test_encoder_provider_is_optional_parakeet_only_and_phone_only(self):
        self.assertNotIn('encoder_provider', variant.validate({})['runtime'])
        v = variant.validate({'runtime': {'encoder_provider': 'webgpu'}})
        with self.assertRaises(ValueError):
            runtime_config.provider(v)
        for bad in ({'runtime': {'encoder_provider': 'gpu'}}, {'runtime': {'encoder_provider': True}},
                    {'family': 'whisper', 'cutting': {'max_chunk_s': 28.0}, 'runtime': {'encoder_provider': 'webgpu'}}):
            with self.assertRaises(variant.VariantError):
                variant.validate(bad)

if __name__ == '__main__':
    unittest.main()
