"""Tensor verdicts at the spec-approved encoder seam (synthetic only)."""
import json
import unittest
import numpy as np
from encoder_conversion import tensor_verdict, coverage_failure, require_frozen_environment

class TensorVerdictTest(unittest.TestCase):
    def test_shape_change_cannot_pass_by_broadcasting(self):
        result = tensor_verdict([np.zeros((1, 2), np.float32), np.array([2])],
                                [np.zeros((2,), np.float32), np.array([2])])
        self.assertFalse(result["passed"])
        self.assertEqual(result["outputs"][0]["failure"], "shape")

    def test_nonfinite_failure_can_be_preserved_as_json_evidence(self):
        result = tensor_verdict([np.zeros((1,), np.float32), np.array([1], np.int64)],
                                [np.array([np.nan], np.float32), np.array([1], np.int64)])
        self.assertFalse(result["passed"])
        self.assertEqual(result["outputs"][0]["failure"], "nonfinite")
        json.dumps(result, allow_nan=False)

    def test_reference_must_also_be_fp32(self):
        result = tensor_verdict([np.array([1.], np.float64), np.array([1], np.int64)],
                                [np.array([1.], np.float32), np.array([1], np.int64)])
        self.assertFalse(result["passed"])

    def test_converter_full_coverage_with_custom_fallback_is_rejected(self):
        report = {"graph_summary": {"coverage_ratio": 1.0, "unsupported_nodes": 0,
                                     "custom_lowered_nodes": 562},
                  "custom_lowered_nodes": [{"onnx_op": "Shape", "message": "ONNX_SHAPE"}]}
        self.assertIn("custom", coverage_failure(report))

    def test_integer_features_cannot_pass_as_encoder_output(self):
        result = tensor_verdict([np.array([1], np.int64), np.array([1], np.int64)],
                                [np.array([1], np.int64), np.array([1], np.int64)])
        self.assertFalse(result["passed"])

    def test_runtime_drift_blocks_tensor_attempt(self):
        with self.assertRaisesRegex(ValueError, "environment changed"):
            require_frozen_environment({"python": "different interpreter", "dependencies": []})

    def test_relative_tolerance_scales_original_reference(self):
        result = tensor_verdict([np.array([1.0], np.float32), np.array([1], np.int64)],
                                [np.array([1.0011005], np.float32), np.array([1], np.int64)])
        self.assertFalse(result["passed"])

if __name__ == "__main__":
    unittest.main()
