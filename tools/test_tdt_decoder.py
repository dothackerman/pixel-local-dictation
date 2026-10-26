import unittest
import numpy as np
from tdt_decoder import decode_logits


class DecoderTest(unittest.TestCase):
    def test_blank_zero_duration_advances_without_emitting(self):
        # Model boundary: first frame is blank, second emits token 1 and skips 2.
        logits = iter([[0, 0, 9, 8, 0, 0], [0, 9, 0, 0, 0, 8]])
        result = decode_logits(np.zeros((3, 4), np.float32), 3,
                               lambda token, state: (token, state),
                               lambda frame, prediction: np.array(next(logits)), None)
        self.assertEqual(result.tokens, [1])
        self.assertEqual(result.timestamps, [1])
        self.assertEqual(result.durations, [2])
        self.assertEqual(result.joiner_calls, 2)

    def test_five_zero_duration_tokens_force_progress(self):
        result = decode_logits(np.zeros((2, 4), np.float32), 3,
                               lambda token, state: (token, state),
                               lambda frame, prediction: np.array([8, 0, 0, 9, 0]), None)
        self.assertEqual(result.tokens, [0] * 10)
        self.assertEqual(result.timestamps, [0] * 5 + [1] * 5)

    def test_argmax_ties_choose_first_token_and_duration(self):
        result = decode_logits(np.zeros((1, 4), np.float32), 3,
                               lambda token, state: (token, state),
                               lambda frame, prediction: np.zeros(5), None)
        self.assertEqual(result.tokens, [0] * 5)
        self.assertEqual(result.durations, [0] * 5)

    def test_text_matches_sherpa_spacing_without_rewriting_words(self):
        from tdt_decoder import tokens_to_text
        self.assertEqual(tokens_to_text([0, 1, 2, 3], {0:'▁Hello', 1:'▁,', 2:'▁world', 3:'!'}), 'Hello, world!')
        self.assertEqual(tokens_to_text([0, 1], {0:'▁中', 1:'▁文'}), '中文')
