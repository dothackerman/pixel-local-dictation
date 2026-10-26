import math
import unittest
import numpy as np
from dictionary_boost import PhraseTree
from tdt_decoder import decode_logits


class BoostTest(unittest.TestCase):
    def test_partial_phrase_refunds_but_completed_phrase_retains_score(self):
        tree = PhraseTree([[1, 2], [2, 3]])
        score, state = tree.step(0, 1)
        self.assertEqual(score, 1)
        refund, root = tree.step(state, 7)
        self.assertEqual((refund, root), (-1, 0))
        score, complete = tree.step(state, 2)
        self.assertAlmostEqual(score, 2 + math.log(2))
        score, suffix = tree.step(complete, 3)
        self.assertAlmostEqual(score, 2 + math.log(2))
        self.assertTrue(tree.nodes[suffix]['terminal'])

    def test_boost_never_changes_unboosted_blank_or_duration(self):
        tree = PhraseTree([[1]])
        logits = iter([[0, 1, 9, 0, 8], [4, 3, 0, 0, 8]])
        result = decode_logits(np.zeros((2, 3)), 3,
                               lambda token, state: (token, state),
                               lambda frame, pred: np.array(next(logits),dtype=np.float32),
                               None, tree=tree, alpha=10)
        self.assertEqual(result.tokens, [1])
        self.assertEqual(result.timestamps, [1])
        self.assertEqual(result.durations, [1])

    def test_carried_decoder_state_avoids_reinitializing_prediction(self):
        def decoder(token, state):
            return state + 1, state + 1
        joiner = lambda frame, pred: np.array([5, 0, 0, 0, 8], np.float32)
        first = decode_logits(np.zeros((1, 3)), 3, decoder, joiner, 0)
        second = decode_logits(np.zeros((1, 3)), 3, decoder, joiner, 0, continuation=first.continuation)
        self.assertEqual(second.continuation[1], 3)

    def test_empty_dictionary_is_exact_even_with_large_alpha(self):
        joiner = lambda frame, pred: np.array([5, 0, 0, 0, 8], np.float32)
        decoder = lambda token, state: (token, state)
        plain = decode_logits(np.zeros((2, 3)), 3, decoder, joiner, None)
        empty = decode_logits(np.zeros((2, 3)), 3, decoder, joiner, None, tree=PhraseTree([]), alpha=100)
        self.assertEqual((plain.tokens,plain.durations,plain.joiner_calls), (empty.tokens,empty.durations,empty.joiner_calls))
