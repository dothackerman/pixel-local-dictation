"""NeMo nonuniform phrase-tree scores for greedy TDT (Speech d32dd550).

A completed phrase keeps its accumulated bonus on failure; an unfinished prefix
refunds its potential. Blank gating and duration remain the decoder's decisions.
"""
from collections import deque
import math
import numpy as np


class PhraseTree:
    def __init__(self, phrases):
        self.nodes = [dict(arcs={}, fail=0, potential=0., terminal=False)]
        self.alphabet = set()
        self._scores = {}
        for phrase in phrases:
            state = 0
            for depth, token in enumerate(phrase):
                if not isinstance(token, int) or token < 0:
                    raise ValueError('phrase token IDs must be nonnegative integers')
                self.alphabet.add(token)
                if token not in self.nodes[state]['arcs']:
                    target = len(self.nodes)
                    self.nodes[state]['arcs'][token] = target
                    potential = self.nodes[state]['potential'] + (1 if depth == 0 else 2 + math.log(depth + 1))
                    self.nodes.append(dict(arcs={}, fail=0, potential=potential, terminal=False))
                state = self.nodes[state]['arcs'][token]
            if phrase:
                self.nodes[state]['terminal'] = True
        queue = deque(self.nodes[0]['arcs'].values())
        while queue:
            state = queue.popleft()
            for token, target in self.nodes[state]['arcs'].items():
                fail = self.nodes[state]['fail']
                while fail and token not in self.nodes[fail]['arcs']:
                    fail = self.nodes[fail]['fail']
                self.nodes[target]['fail'] = self.nodes[fail]['arcs'].get(token, 0)
                queue.append(target)

    def step(self, state, token):
        score = 0.
        while token not in self.nodes[state]['arcs']:
            if state == 0:
                return score, 0
            node = self.nodes[state]
            fail = node['fail']
            if not node['terminal']:
                score += self.nodes[fail]['potential'] - node['potential']
            state = fail
        target = self.nodes[state]['arcs'][token]
        return score + self.nodes[target]['potential'] - self.nodes[state]['potential'], target

    def scores(self, state, size):
        key = state, size
        if key not in self._scores:
            scores = np.full(size, self.step(state, -1)[0], np.float32)
            for token in self.alphabet:
                if token >= size:
                    raise ValueError('dictionary includes blank or out-of-vocabulary token')
                scores[token] = self.step(state, token)[0]
            self._scores[key] = scores
        return self._scores[key]


def compile_dictionary(entries, vocabulary, tokens, emitted=()):
    """Canonical tokenization of bilingual spelling forms; no numeric strings.

    ``emitted`` adds (term, tokens) paths the decoder has produced for a listed
    term at zero bonus (#22): the tree must match what the model emits, not only
    the tokenizer's canonical split. Entries keep their literal "&"; no expansion.
    A spelling the vocabulary cannot encode ("&" is no token) gets no canonical
    path; its term must then come from an emitted path.
    """
    from ssentencepiece import Ssentencepiece
    tokenizer = Ssentencepiece(str(vocabulary), num_threads=1)
    symbols = {line.rsplit(' ', 1)[0]: int(line.rsplit(' ', 1)[1]) for line in tokens.read_text().splitlines()}
    forbidden = {symbols.get('<unk>'), symbols.get('<blk>')}
    rows, seen, paths = [], set(), set()
    for entry in entries:
        entry = ' '.join(entry.split())
        if not entry or any(c.isdigit() for c in entry):
            raise ValueError('dictionary entries must be nonempty nonnumeric terms')
        for form in (entry, entry.replace('ß', 'ss'), entry.replace('ss', 'ß')):
            if form in seen:
                continue
            # SentencePiece vocabulary uses ASCII apostrophes for typographic quotes.
            encoded_form = form.replace('’', "'")
            pieces = tokenizer.encode(encoded_form, out_type=str)
            seen.add(form)
            if any(piece not in symbols for piece in pieces):
                continue
            ids = [symbols[piece] for piece in pieces]
            if not ids or any(token in forbidden for token in ids):
                raise ValueError('dictionary entry has an unknown or blank token')
            rows.append(dict(term=entry, form=form, encoded_form=encoded_form, tokens=ids, source='canonical'))
            paths.add(tuple(ids))
    names = {index: symbol for symbol, index in symbols.items()}
    terms = {' '.join(entry.split()) for entry in entries}
    for term, ids in emitted:
        term = ' '.join(term.split())
        ids = list(ids)
        if term not in terms or not ids or any(token in forbidden or token not in names for token in ids):
            raise ValueError('emitted path must belong to a listed term and use vocabulary tokens')
        if tuple(ids) in paths:
            continue
        form = ''.join(names[token].replace('▁', ' ') for token in ids).strip()
        rows.append(dict(term=term, form=form, encoded_form=form, tokens=ids, source='emitted'))
        paths.add(tuple(ids))
    if terms - {row['term'] for row in rows}:
        raise ValueError('dictionary entry has no encodable or emitted token path')
    return rows
