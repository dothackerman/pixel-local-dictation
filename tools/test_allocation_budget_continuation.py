"""Exact-cap continuation regressions; synthetic only, no device or inference."""
import copy
from datetime import timedelta
import hashlib
import json
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

import allocation_budget_continuation as budget
import test_allocation
import test_allocation_phone_continuation as phone_tests


class BudgetTests(unittest.TestCase):
    fixture = phone_tests.PhoneTests.fixture
    save = phone_tests.PhoneTests.save

    def setUp(self):
        phone_tests.PhoneTests.setUp(self)
        self.manifest['stages']['2']['limits']['phone_seconds'] = 14400
        self.manifest['stages']['3']['limits']['laptop_seconds'] = 28800
        self.manifest['limits'].update(phone_seconds=43200, laptop_seconds=86400)
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run['timeout_seconds'] = 2400
            run['commands'] = [['synthetic-stage'], ['synthetic-inference'], ['synthetic-pull']]
        self.save(self.path, self.manifest)
        window = json.loads(self.window.read_text())
        window['thermal_continuation'] = dict(path=str(self.record.resolve()), sha256=self.pin)
        window['phone_window']['end'] = (self.now + timedelta(days=1)).astimezone(ZoneInfo('Europe/Zurich')).isoformat()
        self.save(self.window, window)
        ready = patch.object(budget.Execution, 'ready', side_effect=lambda: json.loads(self.window.read_text()))
        ready.start(); self.addCleanup(ready.stop)
        verified = patch.object(budget.Execution, 'verified', return_value=({}, {}))
        verified.start(); self.addCleanup(verified.stop)

    def executor(self, platform='phone'):
        return budget.Execution(self.path, 'owner', self.record, self.pin, platform, lambda: self.now)

    def begin_phone(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        self.executor().transition('begin-work', '2', platform='phone', launch_path=self.window)

    def test_phone_approved_cap_preserves_manifest_and_stops_at_new_boundary(self):
        self.begin_phone()
        original_bytes = self.path.read_bytes()
        self.now += timedelta(seconds=13000)
        self.assertEqual(self.executor().transition('next')['id'], 'one')
        self.assertEqual(self.executor().manifest['stages']['2']['limits']['phone_seconds'], 14400)
        self.assertEqual(self.path.read_bytes(), original_bytes)
        self.now += timedelta(seconds=2601)
        self.assertEqual(self.executor().transition('next')['reason'], 'active-work budget cannot fit full timeout')

    def test_whole_cap_still_overrides_stage_exception(self):
        self.manifest['limits']['phone_seconds'] = 14000
        self.save(self.path, self.manifest)
        self.begin_phone()
        self.now += timedelta(seconds=13000)
        self.assertEqual(self.executor().transition('next')['reason'], 'active-work budget cannot fit full timeout')

    def test_phone_full_timeout_plus_restoration_margin_still_required(self):
        window = json.loads(self.window.read_text())
        window['phone_window']['end'] = (self.now + timedelta(seconds=3299)).astimezone(ZoneInfo('Europe/Zurich')).isoformat()
        self.save(self.window, window)
        self.begin_phone()
        self.assertEqual(self.executor().transition('next')['reason'], 'outside authorized window or restoration margin')

    def test_laptop_only_order_and_new_cap_do_not_require_phone_completion(self):
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run.update(stage=3, platform='laptop', timeout_seconds=3600)
        self.manifest['execution_steps'] = [dict(step, stage=3) for step in self.manifest['execution_steps']]
        self.save(self.path, self.manifest)
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        with patch.object(budget.Execution, 'prerequisites'):
            self.executor('laptop').transition('begin-work', '3', platform='laptop')
            self.now += timedelta(seconds=25400)
            self.assertEqual(self.executor('laptop').transition('next')['id'], 'one')
            self.now += timedelta(seconds=122)
            self.assertEqual(self.executor('laptop').transition('next')['reason'], 'active-work budget cannot fit full timeout')

    def test_platform_and_stage_boundaries_are_explicit(self):
        with self.assertRaisesRegex(ValueError, 'mode/platform'):
            self.executor().transition('begin-work', '3', platform='laptop')
        with self.assertRaisesRegex(ValueError, 'outside approved'):
            self.executor('laptop').transition('start', 'one')
        with self.assertRaisesRegex(ValueError, 'only original'):
            self.executor('laptop').transition('begin-work', '2', platform='laptop')
        with self.assertRaisesRegex(ValueError, 'outside approved'):
            self.executor('laptop').transition('retry', 'one')

    def test_phone_preserves_original_stage_transition_barrier(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        with patch('allocation_report.stage_gate', return_value={'evidence': 'incomplete'}):
            with self.assertRaisesRegex(ValueError, 'previous stage'):
                self.executor().transition('begin-work', '2', platform='phone', launch_path=self.window)

    def test_phone_requires_new_budget_pinned_window(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        with self.assertRaisesRegex(ValueError, 'pin reviewed'):
            self.executor().transition('begin-work', '2', platform='phone', launch_path=self.root / 'launch.json')

    def test_unapproved_or_changed_budget_record_stops_checker(self):
        self.begin_phone()
        with patch.object(budget.Execution, 'verified', side_effect=ValueError('budget record changed')):
            with self.assertRaisesRegex(ValueError, 'budget record changed'):
                self.executor().budget(self.manifest['runs'][0], [], {})


class ApprovalTests(unittest.TestCase):
    fixture = phone_tests.PhoneTests.fixture
    save = phone_tests.PhoneTests.save

    def setUp(self):
        phone_tests.PhoneTests.setUp(self)
        for key, cap in budget.CAPS.items():
            stage, field = key.split(':')
            self.manifest['stages'][stage]['limits'][field] = cap['previous']
        self.save(self.path, self.manifest)
        self.old_plan = b'frozen predecessor plan\n'
        sources = {}
        for name in budget.NEW_FILES | {budget.PLAN}:
            path = self.root / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_bytes(self.old_plan + b'approved appendix\n' if name == budget.PLAN else b'admin source')
            sources[name] = budget.identity.sha(path)
        previous_laptop = self.root / 'old-laptop.json'
        self.save(previous_laptop, dict(original_plan_bytes=7))
        self.save(self.record, dict(source_checkpoint='old-head', administrative_files={budget.PLAN:hashlib.sha256(self.old_plan).hexdigest()},
                                    laptop_amendment=dict(path=str(previous_laptop))))
        self.approval = dict(schema=1, scope=budget.SCOPE, authorized_by='Oriol', session_id='owner',
            authorization='Explicitly approved both exact proposals', manifest=str(self.path),
            manifest_sha256=budget.identity.sha(self.path), caps=copy.deepcopy(budget.CAPS),
            administrative_files=sources,
            thermal_predecessor=dict(path=str(self.record), sha256=budget.identity.sha(self.record)))

    def verify(self, record=None):
        with patch.object(budget.subprocess, 'check_output', return_value=self.old_plan), \
                patch.object(budget.thermal, 'verify_record') as chain:
            result = budget.verify_record(self.path, record or self.approval, 'owner', self.root)
            chain.assert_called_once()
            return result

    def test_exact_approval_preserves_predecessor_record(self):
        before = self.record.read_bytes()
        self.assertEqual(self.verify()['source_checkpoint'], 'old-head')
        self.assertEqual(self.record.read_bytes(), before)

    def test_extra_or_changed_cap_and_wrong_owner_fail_closed(self):
        for mutate in (lambda r:r['caps']['2:phone_seconds'].update(approved=18001),
                       lambda r:r['caps'].update({'1:phone_seconds':{'previous':14400,'approved':18000}}),
                       lambda r:r.update(session_id='other')):
            changed = copy.deepcopy(self.approval); mutate(changed)
            with self.assertRaisesRegex(ValueError, 'invalid exact'):
                self.verify(changed)

    def test_predecessor_plan_record_and_source_drift_fail_closed(self):
        path = self.root / budget.PLAN
        path.write_bytes(b'changed predecessor plan\n')
        changed = copy.deepcopy(self.approval)
        changed['administrative_files'][budget.PLAN] = budget.identity.sha(path)
        with self.assertRaisesRegex(ValueError, 'prefix changed'):
            self.verify(changed)
        path.write_bytes(self.old_plan + b'approved appendix\n')
        self.record.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
