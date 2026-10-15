"""Synthetic continuation regressions; no inference or device access."""
import copy
from datetime import timedelta
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import allocation_laptop_continuation as continuation
import allocation_report
import test_allocation


class LaptopTests(unittest.TestCase):
    fixture = test_allocation.ContinuationTests.fixture
    save = test_allocation.ContinuationTests.save
    complete = test_allocation.ContinuationTests.complete
    validate = test_allocation.ContinuationTests.validate

    def setUp(self):
        test_allocation.ContinuationTests.setUp(self)
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run['stage'] = 3
        self.manifest['execution_steps'] = [dict(step, stage=3) for step in self.manifest['execution_steps']]
        self.save(self.path, self.manifest)
        self.amendment = self.root / 'amendment.json'
        self.save(self.amendment, {'fixture': True})
        self.pin = continuation.identity.sha(self.amendment)
        self.ready_patch = patch.object(continuation.Execution, 'ready', return_value={})
        self.ready_patch.start()
        self.addCleanup(self.ready_patch.stop)
        gate = patch.object(allocation_report, 'stage_gate', return_value={'evidence': 'complete'})
        gate.start()
        self.addCleanup(gate.stop)

    def act(self, action, run=None, outcome=None, owner='owner', platform=None, **kwargs):
        return continuation.Execution(self.path, owner, self.amendment, self.pin, self.now).transition(action, run, outcome, platform, **kwargs)

    def begin(self):
        # Original executor owns this same log before the amendment.
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        self.act('begin-work', '3', platform='laptop')

    def test_laptop_barriers_and_pending_phone(self):
        self.begin()
        self.complete('one')
        self.assertEqual(self.act('next')['kind'], 'validate')
        self.validate('one')
        self.complete('two')
        self.validate('two')
        step = self.act('next')
        self.assertEqual(step['kind'], 'compare')
        result = allocation_report.compare(self.manifest, self.manifest['comparisons'][0])
        self.save(self.root / 'pair.json', result)
        self.assertEqual(self.act('next')['kind'], 'laptop-complete')
        self.assertEqual(self.act('next')['overall_status'], 'partial')
        changed = copy.deepcopy(result)
        changed['evidence'] = 'incomplete'
        self.save(self.root / 'pair.json', changed)
        with self.assertRaisesRegex(ValueError, 'comparison barrier'):
            self.act('next')

    def test_no_phone_other_stage_or_retry(self):
        self.begin()
        self.act('pause-work')
        for stage, platform in [('2', 'laptop'), ('3', 'phone')]:
            with self.assertRaisesRegex(ValueError, 'only stage-3 laptop'):
                self.act('begin-work', stage, platform=platform)
        with self.assertRaisesRegex(ValueError, 'outside laptop'):
            self.act('retry', 'retry')

    def test_consumed_time_and_processes_survive_pause(self):
        self.begin()
        self.complete('one')
        self.validate('one')
        self.now += timedelta(seconds=930)
        self.act('pause-work')
        self.now += timedelta(days=1)
        self.act('begin-work', '3', platform='laptop')
        self.assertEqual(self.act('next')['kind'], 'run')
        self.now += timedelta(seconds=10)
        self.assertEqual(self.act('next')['kind'], 'handback')
        with self.assertRaisesRegex(ValueError, 'not the permitted'):
            self.act('start', 'two')

    def test_prior_laptop_evidence_is_required(self):
        self.manifest['runs'][0]['stage'] = 2
        self.manifest['execution_steps'][0]['stage'] = 2
        self.save(self.path, self.manifest)
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        with self.assertRaisesRegex(ValueError, 'prior required run'):
            self.act('begin-work', '3', platform='laptop')

    def test_filters_phone_commands_without_changing_laptop_order(self):
        phone = dict(self.manifest['runs'][0], id='phone', platform='phone')
        self.manifest['runs'].append(phone)
        self.manifest['execution_steps'].insert(1, {'run': 'phone', 'stage': 3})
        self.save(self.path, self.manifest)
        executor = continuation.Execution(self.path, 'owner', self.amendment, self.pin, self.now)
        self.assertEqual(list(executor.selected_steps(3)), [s for s in self.manifest['execution_steps'] if s.get('run') != 'phone'])

    def test_appendix_identity_is_strict(self):
        plan = self.root / continuation.PLAN
        plan.parent.mkdir(parents=True)
        original, appendix = b'frozen plan\n', b'approved appendix\n'
        plan.write_bytes(original + appendix)
        hashes = {}
        for name in continuation.ADMIN_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists():
                path.write_bytes(b'synthetic')
            hashes[name] = continuation.identity.sha(path)
        manifest = dict(source_files={str(plan): hashlib.sha256(original).hexdigest()})
        record = dict(authorized_by='Oriol', session_id='owner', scope='stage-3-laptop-only',
                      manifest=str(self.path), manifest_sha256=continuation.identity.sha(self.path),
                      original_plan_bytes=len(original), appendix_sha256=hashlib.sha256(appendix).hexdigest(),
                      administrative_files=hashes)
        continuation.verify_amendment(manifest, record, 'owner', self.root)
        for content in (b'changed plan\n' + appendix, original + b'changed appendix\n'):
            plan.write_bytes(content)
            with self.assertRaisesRegex(ValueError, 'plan or approved appendix'):
                continuation.verify_amendment(manifest, record, 'owner', self.root)


if __name__ == '__main__':
    unittest.main()
