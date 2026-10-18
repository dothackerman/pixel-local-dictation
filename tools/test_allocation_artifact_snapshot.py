from pathlib import Path
import json
import tempfile
import unittest

import allocation_artifact_snapshot as artifacts


class ArtifactSnapshotTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.write('environment.json', {'runtime': {str(self.root / 'runtime.bin'): 'sha'}})
        self.write('legacy.json', {'source_files': {str(self.root / 'source.py'): 'sha'}})
        self.write('variant.json', {'model': 'synthetic-model'})
        for name in ('runtime.bin', 'source.py', 'models/synthetic-model/model.onnx',
                     'corpus/files/benchmark/input.wav', 'corpus/scripts/reference.txt'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'synthetic')
        self.path = self.write('manifest.json', dict(legacy_manifest=dict(path=str(self.root / 'legacy.json')),
                                                    configuration='variant.json'))

    def write(self, name, data):
        target = self.root / name
        target.write_text(json.dumps(data))
        return target

    def test_transitive_model_runtime_input_and_contract_mutations_fail(self):
        for name in ('runtime.bin', 'source.py', 'models/synthetic-model/model.onnx',
                     'corpus/files/benchmark/input.wav', 'variant.json'):
            snapshot = artifacts.inventory(self.path, self.root)
            path = self.root / name
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'changed after readiness'):
                artifacts.verify(snapshot)
            path.write_bytes(original)

    def test_tree_membership_and_symlink_retarget_are_observed(self):
        snapshot = artifacts.inventory(self.path, self.root)
        (self.root / 'models/synthetic-model/new.bin').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'changed after readiness'):
            artifacts.verify(snapshot)
        link = self.root / 'linked.bin'
        link.symlink_to(self.root / 'runtime.bin')
        before = {str(link): artifacts.metadata(link)}
        link.unlink(); link.symlink_to(self.root / 'source.py')
        with self.assertRaisesRegex(ValueError, 'changed after readiness'):
            artifacts.verify(before)

    def test_new_reference_script_membership_fails_closed(self):
        for suffix in ('txt', 'terms'):
            snapshot = artifacts.inventory(self.path, self.root)
            (self.root / ('corpus/scripts/new.' + suffix)).write_text('new reference')
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, 'changed after readiness'):
                artifacts.verify(snapshot)

    def test_same_label_before_model_does_not_hide_model_tree(self):
        self.write('variant.json', {'label': 'synthetic-model', 'model': 'synthetic-model'})
        snapshot = artifacts.inventory(self.path, self.root)
        model = self.root / 'models/synthetic-model/model.onnx'
        self.assertIn(str(model), snapshot)
        model.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'changed after readiness'):
            artifacts.verify(snapshot)


if __name__ == '__main__':
    unittest.main()
