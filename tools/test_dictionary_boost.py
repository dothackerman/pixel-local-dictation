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
