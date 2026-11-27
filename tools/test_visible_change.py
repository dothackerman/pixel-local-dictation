"""BEFORE/AFTER images of the panel gallery (#77): a differing image or frame sequence yields pairs, a list and the stop rule."""
import io
from pathlib import Path
import re
import shutil
import tempfile
import unittest

import visible_change

STATES = {'states/portrait-light/idle.png': b'idle', 'states/portrait-light/recording.png': b'recording',
          'states/wide-landscape-dark/idle.png': b'wide idle'}


class VisibleChangeTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.gallery = Path(directory.name).resolve() / 'panel-gallery'
        self.rendered = dict(STATES)
        self.sequences = {}
        self.renders = 0

    def render(self, gallery):
        """Stands in for the Gradle run: AFTER holds the state images; a differing sequence leaves both sides."""
        self.renders += 1
        shutil.rmtree(gallery / 'after', ignore_errors=True)
        for side in ('before', 'after'):
            shutil.rmtree(gallery / side / 'sequences', ignore_errors=True)
        for name, content in self.rendered.items():
            self.write('after/' + name, content)
        for name, (before, after) in self.sequences.items():
            for side, content in (('before', before), ('after', after)):
                if content is not None:
                    self.write(f'{side}/sequences/{name}', content)

    def write(self, name, content):
        path = self.gallery / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def run_tool(self, verb, render=True):
        out = io.StringIO()
        code = visible_change.run(verb, self.gallery, out, self.render if render else None)
        return code, out.getvalue()

    def pairs(self):
        folder = self.gallery / 'pairs'
        return sorted(str(path.relative_to(folder)) for path in folder.rglob('*.png'))

    def test_before_renders_and_stores_and_an_identical_render_passes(self):
        self.assertEqual((0, f'BEFORE: 3 images stored in {self.gallery / "before"}\n'), self.run_tool('before'))
        self.assertEqual((0, '3 images, none differs from BEFORE\n'), self.run_tool('compare'))
        self.assertEqual(2, self.renders)
        self.assertFalse((self.gallery / 'pairs').exists())

    def test_changed_image_fails_with_its_pair_the_list_and_the_rule(self):
        self.run_tool('before')
        self.rendered['states/portrait-light/recording.png'] = b'RECORDING'
        code, text = self.run_tool('compare')
        self.assertEqual(1, code)
        pairs = self.gallery / 'pairs'
        self.assertEqual(['states/portrait-light/recording.AFTER.png', 'states/portrait-light/recording.BEFORE.png'], self.pairs())
        self.assertEqual(b'recording', (pairs / 'states/portrait-light/recording.BEFORE.png').read_bytes())
        self.assertEqual(b'RECORDING', (pairs / 'states/portrait-light/recording.AFTER.png').read_bytes())
        self.assertEqual('changed states/portrait-light/recording.png\n', (pairs / 'list.txt').read_text())
        self.assertEqual('changed states/portrait-light/recording.png\n'
                         f'1 of 3 images differs from BEFORE; pairs and list.txt in {pairs}\n'
                         + visible_change.RULE + '\n', text)

    def test_image_only_in_before_or_only_in_after_is_a_difference(self):
        self.run_tool('before')
        del self.rendered['states/portrait-light/recording.png']
        self.rendered['states/portrait-light/held.png'] = b'held'
        code, text = self.run_tool('compare')
        self.assertEqual(1, code)
        self.assertEqual('added states/portrait-light/held.png\nremoved states/portrait-light/recording.png\n',
                         (self.gallery / 'pairs/list.txt').read_text())
        self.assertEqual(['states/portrait-light/held.AFTER.png', 'states/portrait-light/recording.BEFORE.png'], self.pairs())
        self.assertIn(visible_change.RULE, text)

    def test_every_frame_of_a_differing_sequence_is_a_pair_even_when_its_image_is_the_same(self):
        self.run_tool('before')
        self.sequences = {'back/portrait-light/01.png': (b'opening', b'opening'),
                          'back/portrait-light/02.png': (b'recording', b'paused'),
                          'back/portrait-light/03.png': (None, b'idle')}
        code, text = self.run_tool('compare')
        self.assertEqual(1, code)
        self.assertEqual('same sequences/back/portrait-light/01.png\n'
                         'changed sequences/back/portrait-light/02.png\n'
                         'added sequences/back/portrait-light/03.png\n',
                         (self.gallery / 'pairs/list.txt').read_text())
        self.assertEqual(['sequences/back/portrait-light/01.AFTER.png', 'sequences/back/portrait-light/01.BEFORE.png',
                          'sequences/back/portrait-light/02.AFTER.png', 'sequences/back/portrait-light/02.BEFORE.png',
                          'sequences/back/portrait-light/03.AFTER.png'], self.pairs())
        self.assertIn('golden frame sequence differs: back\n', text)
        self.assertIn('2 of 6 images differ from BEFORE', text)
        self.assertTrue(text.endswith(visible_change.RULE + '\n'))

    def test_before_is_not_stored_while_a_frame_sequence_differs(self):
        self.sequences = {'back/portrait-light/01.png': (b'opening', b'idle')}
        code, text = self.run_tool('before')
        self.assertEqual(1, code)
        self.assertIn('golden frame sequence differs: back', text)
        self.assertFalse((self.gallery / 'before/states').exists())

    def test_pairs_of_an_earlier_comparison_do_not_survive(self):
        self.run_tool('before')
        self.rendered['states/portrait-light/idle.png'] = b'IDLE'
        self.assertEqual(1, self.run_tool('compare')[0])
        self.rendered['states/portrait-light/idle.png'] = b'idle'
        self.assertEqual(0, self.run_tool('compare')[0])
        self.assertFalse((self.gallery / 'pairs').exists())

    def test_before_replaces_the_stored_images(self):
        self.run_tool('before')
        self.rendered = {'states/portrait-light/idle.png': b'idle'}
        self.assertEqual((0, f'BEFORE: 1 image stored in {self.gallery / "before"}\n'), self.run_tool('before'))
        self.assertEqual((0, '1 image, none differs from BEFORE\n'), self.run_tool('compare'))

    def test_without_before_or_without_a_render_nothing_is_compared(self):
        self.assertEqual((1, f'no BEFORE images in {self.gallery / "before"}: store them at the ticket\'s start '
                             'with: python3 tools/visible_change.py before\n'), self.run_tool('compare'))
        self.rendered = {}
        self.assertEqual((1, f'no images in {self.gallery / "after" / "states"}: the gallery render failed\n'),
                         self.run_tool('before'))

    def test_the_render_can_be_skipped_to_compare_what_the_gate_just_rendered(self):
        self.run_tool('before')
        self.write('after/states/portrait-light/idle.png', b'IDLE')
        code, text = self.run_tool('compare', render=False)
        self.assertEqual(1, code)
        self.assertEqual(1, self.renders)
        self.assertIn('changed states/portrait-light/idle.png\n', text)


class RuleTests(unittest.TestCase):
    """One sentence, in the tool, the golden-sequence failure and ADR-029."""
    SENTENCE = ('A differing frame or image stops the work: the owner decides from the BEFORE/AFTER pairs; '
                'text alone is not accepted.')

    def test_the_tool_prints_the_recorded_rule(self):
        self.assertEqual(self.SENTENCE, visible_change.RULE)

    def test_adr_029_records_it(self):
        adr = (visible_change.ROOT / 'docs/adr/029-owner-data-by-build-type.md').read_text()
        self.assertIn(self.SENTENCE, ' '.join(adr.split()))

    def test_a_differing_golden_sequence_prints_it(self):
        source = (visible_change.ROOT / 'app/src/test/kotlin/ch/og/dictation/panel/GoldenFrames.kt').read_text()
        self.assertIn(self.SENTENCE, ''.join(re.findall(r'"([^"\n]*)"', source.split('const val RULE')[1].split('\n    private')[0])))

    def test_the_gallery_is_the_folder_the_app_tests_write(self):
        source = (visible_change.ROOT / 'app/src/test/kotlin/ch/og/dictation/panel/PanelImages.kt').read_text()
        self.assertIn('File("build/panel-gallery")', source)
        self.assertEqual(visible_change.ROOT / 'app/build/panel-gallery', visible_change.GALLERY)


if __name__ == '__main__':
    unittest.main()
