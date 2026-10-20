"""Deletion boundary tests use synthetic temporary files only."""
import os
from pathlib import Path
import tempfile
import unittest
import prune_corpus as prune


class PruneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lab = self.root / 'corpus/lab'
        (self.lab / 'done').mkdir(parents=True)
        (self.lab / 'done/result').write_text('synthetic')

    def test_originals_recovery_and_escapes_refused(self):
        for name in ['phone-operations', 'baseline.json', 'dictionary.txt', '../files', '.', '/tmp', 'done/result']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                prune.inventory(self.root, [name])
        (self.lab / 'done/link').symlink_to(self.root)
        with self.assertRaises(ValueError):
            prune.inventory(self.root, ['done'])

    def test_changed_inventory_deletes_nothing(self):
        for change in ['content', 'added']:
            with self.subTest(change=change):
                plan = {'targets': ['done'], 'inventory': prune.inventory(self.root, ['done'])}
                (self.lab / 'done' / ('result' if change == 'content' else 'new')).write_text(change)
                with self.assertRaises(ValueError):
                    prune.apply(self.root, plan)
                self.assertTrue((self.lab / 'done/result').exists())

    def test_external_hardlink_survives_and_is_not_counted(self):
        outside = self.root / 'canonical'
        outside.write_text('canonical')
        os.link(outside, self.lab / 'done/model')
        (self.lab / 'done/result').unlink()
        inv = prune.inventory(self.root, ['done'])
        self.assertEqual(inv['reclaimable_bytes'], 0)
        prune.apply(self.root, {'targets': ['done'], 'inventory': inv})
        self.assertFalse((self.lab / 'done').exists())
        self.assertEqual(outside.read_text(), 'canonical')


if __name__ == '__main__':
    unittest.main()
