import math
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import numpy as np
from dictionary_boost import PhraseTree, compile_dictionary
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
        self.assertEqual(tree.step(suffix, 7)[0], 0)

    def consume(self, tree, tokens):
        state, bonuses = 0, []
        for token in tokens:
            bonuses.append(float(tree.scores(state, 10)[token]))
            score, state = tree.step(state, token)
            self.assertAlmostEqual(bonuses[-1], score, places=6)
        return bonuses

    def test_completed_prefix_survives_failed_extension(self):
        bonuses = self.consume(PhraseTree([[1], [1, 2, 3]]), [1, 2, 9])
        np.testing.assert_allclose(bonuses, [1, 2.69314718, -2.69314718], atol=1e-6)
        self.assertAlmostEqual(sum(bonuses), 1, places=6)

    def test_nested_completed_prefix_keeps_latest_completion(self):
        tree = PhraseTree([[1], [1, 2], [1, 2, 3, 4]])
        bonuses = self.consume(tree, [1, 2, 3, 9])
        np.testing.assert_allclose(bonuses, [1, 2.69314718, 3.09861229, -3.09861229], atol=1e-6)
        self.assertAlmostEqual(sum(bonuses), 3.69314718, places=6)
        completed = self.consume(tree, [1, 2, 3, 4, 9])
        self.assertAlmostEqual(sum(completed), 10.17805383, places=6)
        self.assertEqual(completed[-1], 0)

    def test_failure_suffix_refunds_only_unfinished_credit(self):
        tree = PhraseTree([[1], [1, 2, 3], [2, 4, 5]])
        bonuses = self.consume(tree, [1, 2, 4, 9])
        np.testing.assert_allclose(bonuses, [1, 2.69314718, 0, -2.69314718], atol=1e-6)
        self.assertAlmostEqual(sum(bonuses), 1, places=6)

    def test_completed_failure_suffix_keeps_its_credit(self):
        tree = PhraseTree([[1, 2, 3], [2], [2, 4, 5]])
        failed = self.consume(tree, [1, 2, 9])
        np.testing.assert_allclose(failed, [1, 2.69314718, -2.69314718], atol=1e-6)
        extended = self.consume(tree, [1, 2, 4, 9])
        np.testing.assert_allclose(extended, [1, 2.69314718, 0, -2.69314718], atol=1e-6)
        self.assertAlmostEqual(sum(extended), 1, places=6)

    def test_overlap_keeps_shared_token_credit_without_double_award(self):
        tree = PhraseTree([[1, 2], [2, 3, 4]])
        failed = self.consume(tree, [1, 2, 3, 9])
        np.testing.assert_allclose(failed, [1, 2.69314718, 2.69314718, -2.69314718], atol=1e-6)
        self.assertAlmostEqual(sum(failed), 3.69314718, places=6)
        completed = self.consume(tree, [1, 2, 3, 4, 9])
        np.testing.assert_allclose(completed, [1, 2.69314718, 2.69314718, 3.09861229, 0], atol=1e-6)
        self.assertEqual(self.consume(tree, [1, 2, 9])[-1], 0)

    def test_duplicate_phrases_and_repeated_matches_do_not_multiply_credit(self):
        tree = PhraseTree([[1], [1], [1, 2, 3], [1, 2, 3]])
        bonuses = self.consume(tree, [1, 2, 9] * 100)
        np.testing.assert_allclose(bonuses, [1, 2.69314718, -2.69314718] * 100, atol=1e-6)
        self.assertAlmostEqual(sum(bonuses), 100, places=5)

    def test_zero_alpha_dictionary_is_exact_for_decoder_results(self):
        def run(tree):
            logits = iter([[0, 4, 1, 0, 0, 8], [0, 0, 5, 0, 0, 8], [1, 0, 0, 9, 0, 8]])
            return decode_logits(np.zeros((3, 3)), 4,
                                 lambda token, state: (token, state),
                                 lambda frame, pred: np.array(next(logits), np.float32),
                                 None, tree=tree, alpha=0)
        plain = run(None)
        self.assertEqual(plain, run(PhraseTree([[1], [1, 2, 0]])))
        self.assertEqual(plain.tokens, [1, 2])
        self.assertEqual(plain.durations, [1, 1])

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

    def test_compile_keeps_literal_ampersand_and_adds_emitted_paths_once(self):
        pieces = {'Fern & Footpath': ['▁Fern', '▁&', '▁Foot', 'path'], 'Zürich': ['▁Zürich']}
        amp = [1, 2, 3, 4]

        class Tokenizer:
            def __init__(self, *args, **kwargs):
                pass

            def encode(self, text, out_type):
                return pieces[text]
        symbols = ['<blk>', '▁Fern', '▁and', '▁Foot', 'path', '▁Zürich', '▁Z', 'ü', 'rich', '<unk>']
        with tempfile.TemporaryDirectory() as tmp:
            tokens = Path(tmp) / 'tokens.txt'
            tokens.write_text(''.join(f'{s} {i}\n' for i, s in enumerate(symbols)))
            with patch.dict(sys.modules, {'ssentencepiece': types.SimpleNamespace(Ssentencepiece=Tokenizer)}):
                rows = compile_dictionary(['Fern & Footpath', 'Zürich'], Path(tmp) / 'bpe.vocab', tokens, emitted=[
                    ('Zürich', [6, 7, 8]), ('Zürich', [6, 7, 8]), ('Zürich', [5]), ('Fern & Footpath', amp)])
                with self.assertRaisesRegex(ValueError, 'no encodable'):
                    compile_dictionary(['Fern & Footpath'], Path(tmp) / 'bpe.vocab', tokens)
                with self.assertRaisesRegex(ValueError, 'listed term'):
                    compile_dictionary(['Zürich'], Path(tmp) / 'bpe.vocab', tokens, emitted=[('Bern', [5])])
                with self.assertRaisesRegex(ValueError, 'vocabulary'):
                    compile_dictionary(['Zürich'], Path(tmp) / 'bpe.vocab', tokens, emitted=[('Zürich', [9])])
        self.assertEqual([(r['term'], r['form'], r['tokens'], r['source']) for r in rows], [
            ('Zürich', 'Zürich', [5], 'canonical'),
            ('Zürich', 'Zürich', [6, 7, 8], 'emitted'),
            ('Fern & Footpath', 'Fern and Footpath', amp, 'emitted')])
