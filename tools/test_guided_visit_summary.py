"""Synthetic guided-visit file and rotation trace; no phone data."""
import json
import unittest

from guided_visit_summary import summarize


def check(i, answer, text='t'):
    return {'id': i, 'text': text, 'answer': answer}


def visit_row(step, run, answers, observation='done', trace=True, case=None):
    checks = [check(f'{step}.{n + 1}', a, f'check {n + 1}') for n, a in enumerate(answers)]
    return {'visit': 'v', 'run': run, 'step': step, 'title': f'Title {step}', 'case': case or step,
            'observation': observation, 'answers': checks, 'traceComplete': trace}


def ev(ns_ms, event, run='r2', rotations=None, flag=None, process=None, service=None):
    row = {'run': run, 'event': event, 'ns': ns_ms * 1_000_000, 'case': 'signal-capture-rotation'}
    if process is not None:
        row['process'] = process
    if service is not None:
        row['service'] = service
    if rotations is not None:
        row['rotations'] = rotations
    if flag is not None:
        row['flag'] = flag
    return row


class SummaryTests(unittest.TestCase):
    def test_one_line_per_check_with_answers_in_script_order(self):
        lines = summarize([visit_row('A1', 'r1', ['passed', 'not observed']),
                           visit_row('A2', 'r2', ['failed'])], [])['checks']
        self.assertEqual(lines, ['A1.1 passed - Title A1: check 1',
                                 'A1.2 not observed - Title A1: check 2',
                                 'A2.1 failed - Title A2: check 1'])

    def test_retry_is_replaced_by_the_later_attempt_and_skip_has_no_answers(self):
        rows = [visit_row('A1', 'r1', ['failed'], observation='retry'),
                visit_row('A1', 'r3', ['passed']),
                visit_row('A4', 'r4', ['not observed'], observation='skipped')]
        lines = summarize(rows, [])['checks']
        self.assertEqual(lines[0], 'A1.1 passed - Title A1: check 1')
        self.assertEqual(lines[1], 'A4 skipped - Title A4')

    def test_rotation_timings_late_hide_silencing_and_hold_outcome(self):
        trace = [ev(0, 'CAPTURE_STARTED'), ev(1000, 'DISPLAY_CHANGED', rotations=1),
                 ev(1005, 'HOLD_ENTERED'), ev(1040, 'WINDOW_HIDDEN'), ev(1180, 'WINDOW_SHOWN'),
                 ev(1250, 'WINDOW_HIDDEN'), ev(1300, 'WINDOW_SHOWN'), ev(2300, 'HOLD_SETTLED'),
                 ev(5000, 'DISPLAY_CHANGED', rotations=2), ev(5100, 'WINDOW_SHOWN'),
                 ev(5200, 'HOLD_SILENCED'), ev(5201, 'CAPTURE_INTERRUPTED'),
                 ev(9000, 'COMMIT_ACCEPTED', run='other')]
        out = summarize([visit_row('A2', 'r2', ['passed'], case='signal-capture-rotation')], trace)['rotations']
        self.assertEqual(out, [
            'A2 rotation 1: panel shown +180 ms, late hide +70 ms after shown, capture silenced: no, hold: settled +1300 ms',
            'A2 rotation 2: panel shown +100 ms, late hide none, capture silenced: yes, hold: interrupted (silenced)'])

    def test_missing_trace_and_untraced_steps_are_reported_plainly(self):
        out = summarize([visit_row('A2', 'r2', ['passed'], trace=False)], [])['rotations']
        self.assertEqual(out, ['A2: no trace rows (traceComplete false)'])
        self.assertEqual(summarize([visit_row('A1', 'r1', ['passed'])], [])['rotations'], [])

    def test_editor_retention_classes_seen_after_each_rotation(self):
        def start(ns, retention):
            row = ev(ns, 'INPUT_START_DONE')
            row['retention'] = retention
            return row
        trace = [ev(0, 'DISPLAY_CHANGED', rotations=1), start(2, 'ELIGIBLE'), start(170, 'NO_EDITOR'),
                 start(185, 'ELIGIBLE'), ev(190, 'WINDOW_SHOWN'), ev(1190, 'HOLD_SETTLED')]
        out = summarize([visit_row('A2', 'r2', ['passed'], case='signal-capture-rotation')], trace)['rotations']
        self.assertEqual(out, ['A2 rotation 1: panel shown +190 ms, late hide none, capture silenced: no, hold: settled +1190 ms, '
                               'editors: ELIGIBLE > NO_EDITOR > ELIGIBLE'])

    def test_gap_expiry_and_no_panel(self):
        trace = [ev(0, 'DISPLAY_CHANGED', rotations=1), ev(3000, 'HOLD_GAP_EXPIRED')]
        out = summarize([visit_row('A3', 'r2', ['passed'], case='signal-finishing-rotation')], trace)['rotations']
        self.assertEqual(out, ['A3 rotation 1: panel shown never, late hide none, capture silenced: no, hold: gap expired +3000 ms'])

    def test_attach_on_a_reused_service_is_a_baseline_not_a_rotation(self):
        trace = [ev(0, 'SERVICE_ATTACH', rotations=2, process=1, service=7), ev(5, 'WINDOW_SHOWN', process=1, service=7),
                 ev(1000, 'DISPLAY_CHANGED', rotations=3, process=1, service=7), ev(1100, 'WINDOW_SHOWN', process=1, service=7),
                 ev(2000, 'HOLD_SETTLED', process=1, service=7)]
        out = summarize([visit_row('A2', 'r2', ['passed'])], trace)['rotations']
        self.assertEqual(out, ['A2 rotation 1: panel shown +100 ms, late hide none, capture silenced: no, hold: settled +1000 ms'])

    def test_a_recreated_service_counts_its_own_rotations(self):
        trace = [ev(0, 'SERVICE_ATTACH', rotations=0, process=1, service=1), ev(1000, 'DISPLAY_CHANGED', rotations=1, process=1, service=1),
                 ev(3000, 'SERVICE_ATTACH', rotations=0, process=1, service=2), ev(5000, 'DISPLAY_CHANGED', rotations=1, process=1, service=2)]
        out = summarize([visit_row('A2', 'r2', ['passed'])], trace)['rotations']
        self.assertEqual([line.split(':')[0] for line in out], ['A2 rotation 1', 'A2 rotation 2'])

    def test_interleaved_services_are_each_counted_against_their_own_baseline(self):
        trace = [ev(0, 'SERVICE_ATTACH', rotations=4, process=1, service=1), ev(1, 'SERVICE_ATTACH', rotations=0, process=2, service=1),
                 ev(1000, 'DISPLAY_CHANGED', rotations=1, process=2, service=1), ev(2000, 'DISPLAY_CHANGED', rotations=5, process=1, service=1),
                 ev(3000, 'DISPLAY_CHANGED', rotations=2, process=2, service=1), ev(4000, 'DISPLAY_CHANGED', rotations=5, process=1, service=1)]
        out = summarize([visit_row('A2', 'r2', ['passed'])], trace)['rotations']
        self.assertEqual([line.split(':')[0] for line in out], ['A2 rotation 1', 'A2 rotation 2', 'A2 rotation 3'])


if __name__ == '__main__':
    unittest.main()
