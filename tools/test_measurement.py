"""Diagnostic report boundary checks; no model loading or personal inputs."""
import unittest
import evaluate_v4 as evaluator

class MeasurementEvidenceTests(unittest.TestCase):
    def report(self):
        phases = ('before-load', 'after-load', 'after-warmup', 'buffer-loaded', 'buffer-released', 'recognizer-released')
        return {'provenance': {'mode': 'paced-only', 'platform': 'phone'}, 'report': {
            'coldLoadMs': 100,
            'memory': [dict(phase=phase, rssKb=100, highWaterKb=110, pssKb=90,
                            javaUsedBytes=20, nativeAllocatedBytes=30) for phase in phases],
            'endpoints': [dict(id='prefix', text='hello world', stopToFinalMs=15,
                              inputSamples=16000, coverageSamples=16000,
                              residualSamples=1000, stopState=dict(active=True, queued=1))]}}

    def test_complete_diagnostic_requires_memory_and_all_audio(self):
        report = self.report()
        self.assertEqual(evaluator.measurement_errors(report), [])
        report['report']['endpoints'][0]['coverageSamples'] = 15999
        self.assertIn('incomplete audio coverage', evaluator.measurement_errors(report))
        report = self.report()
        report['report']['memory'].pop()
        self.assertIn('incomplete memory phases', evaluator.measurement_errors(report))

    def test_nan_wait_and_missing_decode_state_are_incomplete(self):
        report = self.report()
        report['report']['endpoints'][0]['stopToFinalMs'] = float('nan')
        report['report']['endpoints'][0].pop('stopState')
        errors = evaluator.measurement_errors(report)
        self.assertIn('missing or invalid final wait', errors)
        self.assertIn('missing Stop decoder state', errors)

if __name__ == '__main__':
    unittest.main()
