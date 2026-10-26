#!/usr/bin/env python3
"""Python executable specification of sherpa-onnx 1.13.8 greedy NeMo TDT.

The tensor runtime is the installed sherpa wheel's own ONNX Runtime; no version
substitution. Encoder results can be cached independently of decoder experiments.
"""
from dataclasses import dataclass
import numpy as np


@dataclass
class DecodeResult:
    tokens: list
    timestamps: list
    durations: list
    joiner_calls: int
    text: str = ''
    continuation: object = None


def decode_logits(encoded, vocab_size, decoder, joiner, state, blank_penalty=0, *, tree=None, alpha=0, continuation=None):
    """Decode encoder frames against decoder/joiner model-call boundaries.

    Inputs/outputs use the pinned model tensor semantics. Ties choose the first
    index, including duration zero. Blank never updates the prediction state.
    """
    blank = vocab_size - 1
    if continuation is None:
        prediction, state = decoder(blank, state)
        tree_state = 0
    else:
        prediction, state, tree_state = continuation
    tokens, timestamps, durations = [], [], []
    t = count = calls = 0
    while t < len(encoded):
        logits = np.asarray(joiner(encoded[t], prediction)).reshape(-1).copy()
        calls += 1
        if blank_penalty > 0:
            logits[blank] -= blank_penalty
        token = int(np.argmax(logits[:vocab_size]))
        if token != blank and tree is not None and tree.alphabet and alpha != 0:
            token = int(np.argmax(logits[:blank] + np.float32(alpha) * tree.scores(tree_state, blank)))
            _, tree_state = tree.step(tree_state, token)
        skip = int(np.argmax(logits[vocab_size:]))
        if token != blank:
            tokens.append(token)
            timestamps.append(t)
            durations.append(skip)
            prediction, state = decoder(token, state)
            count += 1
        if skip > 0:
            count = 0
        if count >= 5:
            count, skip = 0, 1
        if token == blank and skip == 0:
            count, skip = 0, 1
        t += skip
    return DecodeResult(tokens, timestamps, durations, calls, continuation=(prediction, state, tree_state))


def tokens_to_text(tokens, symbols):
    """Pinned NeMo conversion: BPE spaces, CJK/punctuation spacing, one lead space."""
    text = ''.join((' ' + symbols[t][1:]) if symbols[t].startswith('▁') else symbols[t] for t in tokens)
    def within(char, ranges):
        return any(lo <= ord(char) <= hi for lo, hi in ranges)
    cjk = [(0x1100,0x11FF),(0x2E80,0xA4CF),(0xA840,0xD7AF),(0xF900,0xFAFF),
           (0xFE30,0xFE4F),(0xFF65,0xFFDC),(0x20000,0x2FFFF)]
    punct = [(0x21,0x2F),(0x3A,0x40),(0x5B,0x60),(0x7B,0x7E),(0x3000,0x303F),
             (0xFF01,0xFF0F),(0xFF1A,0xFF20),(0xFF3B,0xFF40),(0xFF5B,0xFF65)]
    text = ''.join(c for i,c in enumerate(text) if not (
        c == ' ' and 0 < i < len(text)-1 and (
            within(text[i+1], punct) or (within(text[i-1], cjk) and within(text[i+1], cjk)))))
    return text[1:] if text.startswith(' ') else text


def features(samples, dimension=128):
    """Pinned sherpa NeMo frontend; samples are mono 16 kHz float32 in [-1,1]."""
    import kaldi_native_fbank as knf
    options = knf.FbankOptions()
    options.frame_opts.dither = 0
    options.frame_opts.snip_edges = False
    options.frame_opts.remove_dc_offset = False
    options.mel_opts.num_bins = dimension
    options.mel_opts.low_freq = 0
    options.mel_opts.high_freq = -400
    options.mel_opts.is_librosa = True
    fbank = knf.OnlineFbank(options)
    fbank.accept_waveform(16000, np.asarray(samples, np.float32).tolist())
    fbank.input_finished()
    raw = np.array([fbank.get_frame(i) for i in range(fbank.num_frames_ready)], np.float32)
    if len(raw) == 0:
        raise ValueError('audio has no feature frames')
    import _tdt_ort
    return _tdt_ort.normalize(raw)


class Decoder:
    def __init__(self, model_dir, runtime_dir, cache_dir, threads=4):
        import hashlib
        import importlib
        import json
        from pathlib import Path
        import sys
        import onnx
        import sherpa_onnx
        import importlib.metadata
        if not 1 <= threads <= 8:
            raise ValueError('threads must be 1..8')
        self.model_dir = Path(model_dir)
        sys.path.insert(0, str(runtime_dir))
        runtime = importlib.import_module('_tdt_ort')
        if runtime.version() != sherpa_onnx.onnxruntime_version:
            raise ValueError('tensor runtime must match stock sherpa wheel ONNX Runtime')
        self.cache = Path(cache_dir)
        self.cache.mkdir(parents=True, exist_ok=True)
        encoder = self.model_dir / 'encoder.int8.onnx'
        files = [encoder, self.model_dir/'decoder.onnx', self.model_dir/'joiner.onnx']
        self.identity = {'ort': runtime.version(), 'threads': threads, 'frontend': 'sherpa-nemo-eigen-v1',
                         'fbank': importlib.metadata.version('kaldi-native-fbank'),
                         'core_sha256': hashlib.file_digest((Path(sherpa_onnx.__file__).parent/'lib/libonnxruntime.so').open('rb'), 'sha256').hexdigest(),
                         'binding_sha256': hashlib.sha256(Path(runtime.__file__).read_bytes()).hexdigest(),
                         'models': {p.name: hashlib.file_digest(p.open('rb'), 'sha256').hexdigest() for p in files}}
        self.identity['tokens_sha256'] = hashlib.sha256((self.model_dir/'tokens.txt').read_bytes()).hexdigest()
        self.cache_identity = json.dumps({k: v for k, v in self.identity.items() if k != 'tokens_sha256'}, sort_keys=True).encode()
        metadata = {x.key:x.value for x in onnx.load(encoder, load_external_data=False).metadata_props}
        self.vocab_size = int(metadata['vocab_size']) + 1
        self.dimension = int(metadata['feat_dim'])
        self.state_shape = (int(metadata['pred_rnn_layers']), 1, int(metadata['pred_hidden']))
        self.encoder = runtime.Session(str(encoder), threads)
        self.decoder = runtime.Session(str(files[1]), threads)
        self.joiner = runtime.Session(str(files[2]), threads)
        self.symbols = {}
        for line in (self.model_dir/'tokens.txt').read_text().splitlines():
            symbol, index = line.rsplit(' ', 1)
            self.symbols[int(index)] = symbol

    def encode(self, samples):
        import hashlib
        import os
        import tempfile
        samples = np.ascontiguousarray(samples, np.float32)
        key = hashlib.sha256(self.cache_identity + samples.tobytes()).hexdigest()
        path = self.cache / (key+'.npz')
        if path.exists():
            with np.load(path, allow_pickle=False) as saved:
                return saved['encoded'], True
        frame = features(samples, self.dimension)
        outputs = self.encoder.run([np.ascontiguousarray(frame.T[None]), np.array([len(frame)], np.int64)])
        encoded = np.ascontiguousarray(outputs[0][0, :, :int(outputs[1][0])].T)
        with tempfile.NamedTemporaryFile(dir=self.cache, suffix='.npz', delete=False) as file:
            temp = file.name
            np.savez(file, encoded=encoded)
        os.replace(temp, path)
        return encoded, False

    def decode(self, encoded, blank_penalty=0, *, tree=None, alpha=0, continuation=None):
        def decoder(token, state):
            result = self.decoder.run([np.array([[token]], np.int32), np.array([1], np.int32), *state])
            return result[0], result[2:]
        def joiner(frame, prediction):
            return self.joiner.run([np.ascontiguousarray(frame[None,:,None]), prediction])[0]
        state = [np.zeros(self.state_shape, np.float32) for _ in range(2)]
        result = decode_logits(encoded, self.vocab_size, decoder, joiner, state, blank_penalty, tree=tree, alpha=alpha, continuation=continuation)
        result.text = tokens_to_text(result.tokens, self.symbols)
        return result


def main():
    import argparse
    import csv
    import hashlib
    import json
    from pathlib import Path
    import time
    import soundfile as sf
    import sherpa_onnx
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--guard', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    decoder = Decoder(args.model, args.runtime, args.cache)
    stock = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(args.model/'encoder.int8.onnx'), decoder=str(args.model/'decoder.onnx'),
        joiner=str(args.model/'joiner.onnx'), tokens=str(args.model/'tokens.txt'),
        model_type='nemo_transducer', num_threads=4)
    rows = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = {'schema': 1, 'protocol': 'tdt-python-parity-v1', 'runtime': decoder.identity,
              'source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'clips': rows}
    with (args.guard/'refs.tsv').open() as file:
        clips = list(csv.DictReader(file, delimiter='\t'))
    for i, clip in enumerate(clips):
        path = args.guard/clip['lang']/(clip['id']+'.wav')
        samples, rate = sf.read(path, dtype='float32')
        if rate != 16000 or samples.ndim != 1:
            raise ValueError(f'expected 16 kHz mono: {path}')
        start = time.monotonic()
        encoded, hit = decoder.encode(samples)
        encode_seconds = time.monotonic()-start
        start = time.monotonic()
        result = decoder.decode(encoded)
        decode_seconds = time.monotonic()-start
        stream = stock.create_stream()
        stream.accept_waveform(rate, samples)
        stock.decode_stream(stream)
        rows.append({'id': clip['id'], 'lang': clip['lang'], 'samples': len(samples),
                     'input_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                     'text': result.text, 'stock_text': stream.result.text,
                     'equal': result.text == stream.result.text,
                     'tokens': result.tokens, 'timestamps': result.timestamps, 'durations': result.durations,
                     'joiner_calls': result.joiner_calls, 'cache_hit': hit,
                     'encode_seconds': encode_seconds, 'decode_seconds': decode_seconds})
        report['complete'] = len(rows)==len(clips)
        report['passed'] = report['complete'] and all(row['equal'] for row in rows)
        args.output.write_text(json.dumps(report, indent=2)+'\n')
        if (i+1)%20==0 or i+1==len(clips):
            print(json.dumps({'completed': i+1, 'total': len(clips), 'mismatches': sum(not r['equal'] for r in rows)}), flush=True)
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
