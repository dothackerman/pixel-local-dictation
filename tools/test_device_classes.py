"""Device-class check on planted trees; the last tests read this checkout. No phone, no build."""
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import device_classes

ROOT = Path(__file__).resolve().parents[1]
SOURCES = 'app/src/androidTest/kotlin/ch/og/dictation/'
TEST = 'package ch.og.dictation\nclass %s {\n    @Test fun holds() {}\n}\n'
LAB_RUN = ('package ch.og.dictation.labrun\nclass %s {\n    @Test fun collects() = LabRun.measure("x") { lab ->\n'
           '        val takes = LabRun.inputs("benchmark")\n    }\n}\n')
HARNESS = ('package ch.og.dictation.labrun\nobject LabRun {\n    const val INPUTS = "labrun-inputs"\n'
           '    const val COMPLETED = "LAB RUN completed"\n    const val INCOMPLETE = "LAB RUN incomplete"\n'
           '    fun lab() = context.wiring.storage.labFolder\n}\n')


class DeviceClassTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / 'tools').mkdir()
        shutil.copy2(ROOT / 'tools/phone', self.root / 'tools/phone')
        self.listing = {
            'labRunPackage': 'ch.og.dictation.labrun',
            'classes': {'ch.og.dictation.ReadyTest': 'test', 'ch.og.dictation.ColdTest': 'test',
                        'ch.og.dictation.OwnerScript': 'guided_check', 'ch.og.dictation.Keys': 'helper',
                        'ch.og.dictation.ReplayKt': 'helper', 'ch.og.dictation.labrun.PipelineRun': 'lab_run',
                        'ch.og.dictation.labrun.LabRun': 'helper'},
            'retired': {'ch.og.dictation.SpikeTest': 'wrote into owner recordings'},
            'phoneBatch': {'batch': ['ch.og.dictation.ReadyTest'], 'freshProcess': ['ch.og.dictation.ColdTest']}}
        self.write('ReadyTest.kt', TEST % 'ReadyTest')
        self.write('ColdTest.kt', TEST % 'ColdTest')
        self.write('OwnerScript.kt', TEST % 'OwnerScript')
        self.write('Keys.kt', 'package ch.og.dictation\nfun isOurs() = true\n\nclass Keys {\n    private class Inner\n}\n')
        self.write('Replay.kt', 'package ch.og.dictation\nfun replay() {}\n')
        self.write('labrun/PipelineRun.kt', LAB_RUN % 'PipelineRun')
        self.write('labrun/LabRun.kt', HARNESS)

    def write(self, name, text):
        path = self.root / SOURCES / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def problems(self):
        (self.root / device_classes.CLASSIFICATION).write_text(json.dumps(self.listing))
        return device_classes.problems(self.root)

    def assert_one_problem(self, *words):
        found = self.problems()
        self.assertEqual(len(found), 1, found)
        for word in words:
            self.assertIn(word, found[0])

    def test_a_tree_where_every_class_has_its_place_passes(self):
        self.assertEqual(self.problems(), [])

    def test_an_unclassified_class_fails(self):
        self.write('NewTest.kt', TEST % 'NewTest')
        self.assert_one_problem('ch.og.dictation.NewTest', 'not classified')

    def test_an_unclassified_file_without_a_class_fails_under_its_file_name(self):
        self.write('Feed.kt', 'package ch.og.dictation\nfun feed() {}\n')
        self.assert_one_problem('ch.og.dictation.FeedKt', 'not classified')

    def test_a_classified_class_that_is_gone_and_an_unknown_kind_fail(self):
        self.listing['classes']['ch.og.dictation.GoneTest'] = 'test'
        self.listing['phoneBatch']['batch'].append('ch.og.dictation.GoneTest')
        self.assert_one_problem('ch.og.dictation.GoneTest', 'no such class')
        del self.listing['classes']['ch.og.dictation.GoneTest']
        self.listing['phoneBatch']['batch'].pop()
        self.listing['classes']['ch.og.dictation.Keys'] = 'probe'
        self.assert_one_problem('ch.og.dictation.Keys', 'unknown kind')

    def test_a_lab_run_class_outside_the_lab_run_package_fails(self):
        self.write('StopWaitRun.kt', (LAB_RUN % 'StopWaitRun').replace('package ch.og.dictation.labrun',
                                                                       'package ch.og.dictation\nimport ch.og.dictation.labrun.LabRun'))
        self.listing['classes']['ch.og.dictation.StopWaitRun'] = 'lab_run'
        self.assert_one_problem('ch.og.dictation.StopWaitRun', 'outside the Lab run package')

    def test_a_test_or_guided_check_in_the_lab_run_package_fails(self):
        for kind in ('test', 'guided_check'):
            with self.subTest(kind):
                self.write('labrun/Misplaced.kt', (TEST % 'Misplaced').replace('package ch.og.dictation', 'package ch.og.dictation.labrun'))
                self.listing['classes']['ch.og.dictation.labrun.Misplaced'] = kind
                self.listing['phoneBatch']['batch'] = ['ch.og.dictation.ReadyTest'] + (
                    ['ch.og.dictation.labrun.Misplaced'] if kind == 'test' else [])
                self.assert_one_problem('ch.og.dictation.labrun.Misplaced', 'sits in the Lab run package')

    def test_a_lab_run_in_the_phone_batch_fails(self):
        for part in ('batch', 'freshProcess'):
            with self.subTest(part):
                self.listing['phoneBatch'][part].append('ch.og.dictation.labrun.PipelineRun')
                self.assert_one_problem('ch.og.dictation.labrun.PipelineRun', 'phone batch', 'lab_run')
                self.listing['phoneBatch'][part].pop()

    def test_guided_checks_and_helpers_stay_out_of_the_batch_and_every_test_is_in_it_once(self):
        self.listing['phoneBatch']['batch'].append('ch.og.dictation.OwnerScript')
        self.assert_one_problem('ch.og.dictation.OwnerScript', 'guided_check')
        self.listing['phoneBatch']['batch'] = []
        self.assert_one_problem('ch.og.dictation.ReadyTest', 'not in the phone batch')
        self.listing['phoneBatch'] = {'batch': ['ch.og.dictation.ReadyTest', 'ch.og.dictation.ColdTest'],
                                      'freshProcess': ['ch.og.dictation.ColdTest']}
        self.assert_one_problem('ch.og.dictation.ColdTest', 'more than once')

    def test_a_helper_with_tests_and_a_test_without_any_fail(self):
        self.listing['classes']['ch.og.dictation.OwnerScript'] = 'helper'
        self.assert_one_problem('ch.og.dictation.OwnerScript', 'helper but holds tests')
        self.listing['classes']['ch.og.dictation.OwnerScript'] = 'guided_check'
        self.write('ReadyTest.kt', 'package ch.og.dictation\nclass ReadyTest\n')
        self.assert_one_problem('ch.og.dictation.ReadyTest', 'no test method')

    def test_a_retired_class_must_be_gone_and_have_a_reason(self):
        self.listing['retired']['ch.og.dictation.OldTest'] = ' '
        self.assert_one_problem('ch.og.dictation.OldTest', 'without a reason')
        del self.listing['retired']['ch.og.dictation.OldTest']
        self.write('SpikeTest.kt', TEST % 'SpikeTest')
        self.listing['classes']['ch.og.dictation.SpikeTest'] = 'test'
        self.listing['phoneBatch']['batch'].append('ch.og.dictation.SpikeTest')
        self.assert_one_problem('ch.og.dictation.SpikeTest', 'retired but still')

    def test_lab_run_code_that_reads_another_folder_than_the_lab_folder_fails(self):
        for planted in ('val takes = context.wiring.storage.recordingsRoot', 'val history = ctx.wiring.storage\n            .historyRoot',
                        'val all = context.wiring.storage', 'import ch.og.dictation.storage.scratchFolder'):
            with self.subTest(planted):
                self.write('labrun/PipelineRun.kt', (LAB_RUN % 'PipelineRun').replace('val takes = LabRun.inputs("benchmark")', planted)
                           if not planted.startswith('import') else (LAB_RUN % 'PipelineRun').replace('\nclass', '\n' + planted + '\nclass'))
                self.assert_one_problem('labrun/PipelineRun.kt', 'storage.labFolder')
        self.write('labrun/PipelineRun.kt', (LAB_RUN % 'PipelineRun').replace(
            'val takes = LabRun.inputs("benchmark")', 'val lab = context.wiring.storage.labFolder // not storage.recordingsRoot'))
        self.assertEqual(self.problems(), [])

    def test_a_lab_run_that_does_not_go_through_the_harness_fails(self):
        self.write('labrun/PipelineRun.kt', 'package ch.og.dictation.labrun\nclass PipelineRun {\n'
                   '    @Test fun collects() = LabRun.measure("x") {}\n    @Test fun asserts() { assertTrue(false) }\n}\n')
        self.assert_one_problem('labrun/PipelineRun.kt', 'LabRun.measure', 'never passed or failed')

    def test_the_wrapper_and_the_harness_must_name_the_same_package_folder_and_lines(self):
        for old, new, words in (
                ('"labrun-inputs"', '"inputs"', ('INPUTS', 'files/lab/labrun-inputs')),
                ('"LAB RUN completed"', '"LAB RUN done"', ('LAB RUN completed',)),
                ('"LAB RUN incomplete"', '"LAB RUN failed"', ('LAB RUN incomplete',))):
            with self.subTest(new):
                self.write('labrun/LabRun.kt', HARNESS.replace(old, new))
                self.assert_one_problem('labrun/LabRun.kt', *words)
        self.write('labrun/LabRun.kt', HARNESS)
        wrapper = self.root / 'tools/phone'
        wrapper.write_text(wrapper.read_text().replace("LAB_RUN = PKG + '.labrun'", "LAB_RUN = PKG + '.lab'"))
        self.assert_one_problem('tools/phone', 'ch.og.dictation.lab,', 'ch.og.dictation.labrun')


class ThisCheckoutTests(unittest.TestCase):
    def test_every_device_class_of_this_tree_is_classified_and_in_its_place(self):
        self.assertEqual(device_classes.problems(ROOT), [])

    def test_the_named_phone_batch_starts_with_phone_readiness_and_holds_no_lab_run(self):
        runs = device_classes.batch_arguments(ROOT)
        self.assertTrue(runs[0].startswith('ch.og.dictation.PhoneReadyTest,'))
        self.assertEqual(runs[1:], ['ch.og.dictation.ColdSettingsTest', 'ch.og.dictation.HistoryProcessTest'])
        self.assertFalse(any('.labrun.' in run for run in runs))

    def test_the_replay_spike_is_retired(self):
        listing = json.loads((ROOT / device_classes.CLASSIFICATION).read_text())
        self.assertIn('ch.og.dictation.ReplayTest', listing['retired'])
        self.assertFalse((ROOT / 'app/src/androidTest/kotlin/ch/og/dictation/ReplayTest.kt').exists())


if __name__ == '__main__':
    unittest.main()
