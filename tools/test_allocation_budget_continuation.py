"""Exact-cap continuation regressions; synthetic only, no device or inference."""
import copy
from datetime import timedelta
import hashlib
import json
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

import allocation_budget_continuation as budget
import allocation_thermal_continuation as thermal
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


class CompletionTests(unittest.TestCase):
    fixture = BudgetTests.fixture
    save = BudgetTests.save

    def setUp(self):
        BudgetTests.setUp(self)
        mapping = {'one': budget.COMPLETION_RUNS[0], 'two': budget.COMPLETION_RUNS[1]}
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run['id'] = mapping.get(run['id'], run['id'])
            if 'retry_of' in run:
                run['retry_of'] = mapping[run['retry_of']]
        for step in self.manifest['execution_steps']:
            if 'run' in step:
                step['run'] = mapping[step['run']]
        for comparison in self.manifest['comparisons']:
            for key in ('candidate', 'control'):
                comparison[key] = mapping[comparison[key]]
        self.manifest['stages']['2']['runs'] = list(mapping.values())
        legacy = self.root / 'legacy.json'
        self.save(legacy, {'source_files': {}})
        self.manifest['legacy_manifest'] = {'path': str(legacy)}
        self.save(self.path, self.manifest)
        self.completion_record = {'scope': budget.COMPLETION_SCOPE, 'caps': budget.COMPLETION_CAPS}
        self.save(self.record, self.completion_record)
        self.pin = budget.identity.sha(self.record)
        window = json.loads(self.window.read_text())
        del window['phone_window']
        window.update(access={'mode': 'until-completion', 'runs': budget.COMPLETION_RUNS},
                      authorized_stages=[2, 3],
                      access_started_at=self.now.astimezone(ZoneInfo('Europe/Zurich')).isoformat(),
                      checked_at=self.now.astimezone(ZoneInfo('Europe/Zurich')).isoformat(),
                      completion_authorization={'path': str(self.record), 'sha256': self.pin},
                      thermal_continuation={'path': str(self.record), 'sha256': self.pin})
        self.save(self.window, window)
        verified = patch.object(budget.Execution, 'verified', return_value=(self.completion_record, {}))
        verified.start(); self.addCleanup(verified.stop)

    def executor(self):
        return budget.Execution(self.path, 'owner', self.record, self.pin, 'phone', lambda: self.now)

    def begin(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        self.executor().transition('begin-work', '2', platform='phone', launch_path=self.window)

    def test_true_until_completion_access_has_no_end_and_retains_stage_reserve(self):
        self.begin()
        self.now += timedelta(seconds=16499)
        self.assertEqual(self.executor().transition('next')['id'], budget.COMPLETION_RUNS[0])
        self.now += timedelta(seconds=2)
        self.assertEqual(self.executor().transition('next')['reason'], 'active-work budget cannot fit full timeout')
        self.assertNotIn('phone_window', json.loads(self.window.read_text()))

    def test_whole_reserve_count_and_storage_limits_still_stop_admission(self):
        self.begin()
        executor = self.executor()
        run = executor.runs[budget.COMPLETION_RUNS[0]]
        with executor.locked() as events:
            launch = json.loads(self.window.read_text())
            with patch.object(budget.Execution, 'work', return_value=(events[-1], {(1, 'phone'): 50000})):
                with self.assertRaisesRegex(ValueError, 'full timeout'):
                    executor.budget(run, events, launch)
            changed = copy.deepcopy(self.manifest)
            changed['limits']['phone_processes'] = 0
            executor.manifest = changed
            with self.assertRaisesRegex(ValueError, 'process count'):
                executor.budget(run, events, launch)
            executor.manifest = self.manifest
            with patch.object(budget.Execution, 'storage', return_value=10**15):
                with self.assertRaisesRegex(ValueError, 'storage budget'):
                    executor.budget(run, events, launch)

    def test_missing_check_bad_pin_invented_end_and_stale_check_fail_closed(self):
        for mutate in (lambda w:w.update(guard_tests_passed=False),
                       lambda w:w.update(completion_authorization={'path':'other','sha256':'other'}),
                       lambda w:w.update(phone_window={'end':'2099-01-01'}),
                       lambda w:w['access'].update(runs=budget.COMPLETION_RUNS + ['extra'])):
            window = json.loads(self.window.read_text()); mutate(window)
            self.assertTrue(budget.completion_launch_errors(self.manifest, window, 'owner', self.executor().pin(), self.now))
        window = json.loads(self.window.read_text())
        self.assertTrue(budget.completion_launch_errors(self.manifest, window, 'owner', self.executor().pin(), self.now + timedelta(hours=13)))

    def test_no_reuse_after_pause_or_earlier_check_and_no_stage_gate_skip(self):
        self.begin()
        self.executor().transition('pause-work', restoration={'completed': True, 'owner':'Oriol','final_state':'synthetic restored'})
        with self.assertRaisesRegex(ValueError, 'fresh immutable'):
            self.executor().transition('begin-work', '2', platform='phone', launch_path=self.window)
        self.now += timedelta(seconds=1)
        with patch('allocation_report.stage_gate', return_value={'evidence':'incomplete'}):
            with self.assertRaisesRegex(ValueError, 'previous stage'):
                self.executor().transition('begin-work', '3', platform='phone', launch_path=self.window)

    def test_finite_set_and_original_start_deadline_are_preserved(self):
        self.begin()
        result = self.executor().transition('start', budget.COMPLETION_RUNS[0])
        self.assertEqual(result['run']['commands'], [['synthetic-stage']])
        self.now += timedelta(seconds=2401)
        with self.assertRaisesRegex(ValueError, 'active process'):
            self.executor().transition('next')
        self.assertEqual(self.executor().transition('finish', budget.COMPLETION_RUNS[0], 'timeout')['outcome'], 'timeout')

    def test_existing_thermal_methods_enforce_wait_and_fresh_ready(self):
        self.begin(); self.executor().transition('start', budget.COMPLETION_RUNS[0])
        with patch.object(budget.Execution, 'release_checkpoint', return_value='synthetic'), \
             patch.object(thermal.artifacts, 'inventory', return_value={}):
            self.executor().prepare_probe(budget.COMPLETION_RUNS[0])
            def sample(status):
                path = self.root / ('thermal-' + str(status) + '.json')
                self.save(path, dict(schema='idle-thermal-observation-v1', protocol=thermal.PROTOCOL,
                    staging_completed_at=self.now.isoformat(), query_started_at=self.now.isoformat(),
                    query_completed_at=self.now.isoformat(),
                    probe=dict(schema='idle-thermal-status-v1', source='android-thermalservice', thermalStatus=status)))
                return path
            self.assertEqual(self.executor().authorize_inference(budget.COMPLETION_RUNS[0], sample(2))['kind'], 'cooldown')
            self.now += timedelta(seconds=15)
            self.assertEqual(self.executor().authorize_inference(budget.COMPLETION_RUNS[0], sample(1))['kind'], 'ready')


class CompletionApprovalTests(unittest.TestCase):
    fixture = ApprovalTests.fixture
    save = ApprovalTests.save

    def setUp(self):
        ApprovalTests.setUp(self)
        self.manifest['stages']['3']['limits']['phone_seconds'] = 14400
        self.manifest['limits']['phone_seconds'] = 43200
        self.save(self.path, self.manifest)
        self.approval['manifest_sha256'] = budget.identity.sha(self.path)
        self.approval['source_checkpoint'] = 'budget-head'
        self.budget_plan = (self.root / budget.PLAN).read_bytes()
        self.historical_sources = {name:(self.root / name).read_bytes() for name in self.approval['administrative_files']}
        self.prior_budget = self.root / 'prior-budget.json'
        self.save(self.prior_budget, self.approval)
        plan = self.root / budget.PLAN
        plan.write_bytes(self.budget_plan + b'finite completion appendix\n')
        self.policy = self.root / 'environment-policy.json'
        self.save(self.policy, {'schema':'allocation-environment-cohorts-v1'})
        self.completion = dict(copy.deepcopy(self.approval), scope=budget.COMPLETION_SCOPE,
            caps=copy.deepcopy(budget.COMPLETION_CAPS), access_mode='until-completion',
            remaining_runs=list(budget.COMPLETION_RUNS), source_checkpoint='new-head',
            budget_predecessor={'path':str(self.prior_budget),'sha256':budget.identity.sha(self.prior_budget)},
            environment_policy={'path':str(self.policy),'sha256':budget.identity.sha(self.policy)})
        self.completion['administrative_files'][budget.PLAN] = budget.identity.sha(plan)

    def verify(self, record=None):
        def git_show(args, **kwargs):
            revision, name = args[2].split(':', 1)
            return self.historical_sources[name] if revision == 'budget-head' else self.old_plan
        with patch.object(budget.subprocess, 'check_output', side_effect=git_show), \
             patch.object(budget.thermal, 'verify_record') as chain:
            result = budget.verify_record(self.path, record or self.completion, 'owner', self.root)
            chain.assert_called_once()
            return result

    def test_versioned_completion_preserves_prior_budget_and_source_snapshot(self):
        before = self.prior_budget.read_bytes()
        self.verify()
        self.assertEqual(self.prior_budget.read_bytes(), before)
        self.historical_sources['tools/allocation_budget_continuation.py'] = b'changed historical source'
        with self.assertRaisesRegex(ValueError, 'historical budget checkpoint differs'):
            self.verify()

    def test_completion_scope_caps_policy_and_plan_fail_closed(self):
        for mutate in (lambda r:r.update(remaining_runs=budget.COMPLETION_RUNS + ['extra']),
                       lambda r:r['caps']['whole:phone_seconds'].update(approved=52501),
                       lambda r:r.update(access_mode='forever')):
            changed = copy.deepcopy(self.completion); mutate(changed)
            with self.assertRaises(ValueError):
                self.verify(changed)
        self.policy.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'cohort policy changed'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
