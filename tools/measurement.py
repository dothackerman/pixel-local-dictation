#!/usr/bin/env python3
"""One fresh diagnostic process per invocation; no experiment scheduling or ledger writes.

Suite JSON holds finite WAV paths and prefix references. Paths must stay under the
repository. Results are exclusively created; a failed run preserves failure evidence.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import traceback
import uuid

MODES = ("unpaced-only", "paced-only", "persistent")
ROOT = Path(__file__).resolve().parents[1]


def memory(phase):
    result = {"phase": phase, "monotonic_ns": time.monotonic_ns()}
    for line in Path('/proc/self/status').read_text().splitlines():
        if line.startswith(('VmRSS:', 'VmHWM:')):
            result[{'VmRSS': 'rssKb', 'VmHWM': 'highWaterKb'}[line.split(':')[0]]] = int(line.split()[1])
    for line in Path('/proc/self/smaps_rollup').read_text().splitlines():
        if line.startswith('Pss:'):
            result['pssKb'] = int(line.split()[1])
    result['javaUsedBytes'] = None
    result['nativeAllocatedBytes'] = None  # allocator API not exposed by Python/sherpa
    return result


def take(pipeline, v, rec, x, executor, paced, memory_rows):
    c = v['cutting']
    chunker = pipeline.Chunker(int(c['min_chunk_s'] * pipeline.RATE), int(c['pad_s'] * pipeline.RATE), int(c['max_chunk_s'] * pipeline.RATE))
    vad = pipeline.make_vad(v, str(ROOT / 'models/silero_vad.onnx'))
    rows, futures = [], []
    state = {'pending': 0, 'active': False, 'begun': False}
    lock = threading.Lock()

    def submit(chunk):
        start, samples, speech = chunk
        queued = time.monotonic_ns()
        with lock:
            state['pending'] += 1
        def decode():
            began = time.monotonic_ns()
            with lock:
                state['pending'] -= 1
                state['active'] = True
            row = {'startSample': start, 'samples': len(samples), 'audioSeconds': len(samples) / pipeline.RATE,
                   'queueWaitMs': (began - queued) / 1e6, 'skipped': v['post']['skip_silent_chunks'] and not speech}
            try:
                lid_started = time.monotonic_ns()
                if not row['skipped'] and not state['begun']:
                    row['lid'] = rec.begin_take(samples)
                    state['begun'] = True
                row['lidMs'] = (time.monotonic_ns() - lid_started) / 1e6
                started = time.monotonic_ns()
                row['text'] = '' if row['skipped'] else rec.transcribe(samples)
                row['decodeMs'] = (time.monotonic_ns() - started) / 1e6
                row['rtf'] = row['decodeMs'] / 1000 / row['audioSeconds']
                memory_rows.append(memory('after-chunk'))
                rows.append(row)
                return row['text']
            finally:
                with lock:
                    state['active'] = False
        futures.append(executor.submit(decode))

    start_time = time.monotonic()
    for i in range(0, len(x), 640):
        piece = x[i:i + 640]
        if paced:
            time.sleep(max(0, start_time + (i + len(piece)) / pipeline.RATE - time.monotonic()))
        vad.accept_waveform(piece)
        forced = chunker.append(piece, vad.is_speech_detected())
        if forced:
            submit(forced)
        while not vad.empty():
            segment = vad.front
            vad.pop()
            ready = chunker.on_speech_end(segment.start + len(segment.samples))
            if ready:
                submit(ready)
    stopped = time.monotonic_ns()
    with lock:
        stop_state = {'active': state['active'], 'queued': state['pending']}
    residual = len(chunker.pending)
    rest = chunker.rest(vad.is_speech_detected())
    if rest:
        submit(rest)
    texts = [f.result() for f in futures]
    final_text = pipeline.post_join(v, texts)
    done = time.monotonic_ns()
    covered = sum(r['samples'] for r in rows)
    if covered != len(x) or any(r['startSample'] != sum(a['samples'] for a in rows[:n]) for n, r in enumerate(rows)):
        raise ValueError('incomplete or overlapping audio coverage')
    return {'text': final_text, 'chunks': rows, 'audioSeconds': len(x) / pipeline.RATE,
            'stopToFinalMs': (done - stopped) / 1e6, 'inputSamples': len(x), 'coverageSamples': covered, 'stopState': stop_state, 'residualSamples': residual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('variant'); parser.add_argument('suite'); parser.add_argument('output')
    parser.add_argument('--mode', choices=MODES, required=True)
    parser.add_argument('--repetitions', type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 3 or (args.mode != 'persistent' and args.repetitions != 1):
        parser.error('only persistent mode permits 2..3 repeats')
    import pipeline
    import variant
    suite = json.loads(Path(args.suite).read_text())
    v = variant.load(args.variant)
    from runtime_config import set_profile_directory
    set_profile_directory(str(Path(args.output).resolve().with_suffix(".profiles")))
    if not 1 <= len(suite['inputs']) <= 32:
        parser.error('suite must contain 1..32 inputs')
    import measurement_identity as identity
    observed = dict(build_sha256=identity.digest(identity.source_files(ROOT)),
                    runtime_sha256=identity.digest(identity.runtime_files()),
                    model_sha256=identity.digest(identity.model_files(ROOT, v)),
                    configuration_sha256=identity.sha(args.variant),
                    evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)))
    observed['dictionary_sha256'] = identity.sha(ROOT / 'corpus/lab' / v['hotwords']['file']) if v['hotwords']['file'] else identity.digest([])
    observed['inputs_sha256'] = identity.digest(suite['inputs'])
    observed['references_sha256'] = identity.digest(identity.reference_files(ROOT))
    # Reserve before inference so an existing result can never be overwritten.
    with open(args.output, 'x') as destination:
        result = {'schema': 4, 'outcome': 'running', 'provenance': dict(suite['provenance'], platform='laptop', mode=args.mode,
                  feed=640, run_id=str(uuid.uuid4()), process_start_id=f"{os.getpid()}:{Path('/proc/self/stat').read_text().split()[21]}"),
                  'report': {'variant': v, 'memory': [], 'takes': [], 'endpoints': []}}
        result['provenance'].update(observed)
        report = result['report']
        def checkpoint():
            destination.seek(0); json.dump(result, destination, indent=2); destination.truncate(); destination.flush()
        checkpoint()
        try:
            report['memory'].append(memory('before-load'))
            started = time.monotonic()
            hotwords = str(ROOT / 'corpus/lab' / v['hotwords']['file']) if v['hotwords']['file'] else ''
            rec = pipeline.recognizer(v, str(ROOT / 'models'), hotwords)
            report['coldLoadMs'] = (time.monotonic() - started) * 1000
            report['memory'].append(memory('after-load'))
            import numpy as np
            rec.transcribe(np.zeros(pipeline.RATE, dtype=np.float32))
            report['memory'].append(memory('after-warmup'))
            with ThreadPoolExecutor(max_workers=1) as executor:
                for repetition in range(args.repetitions):
                    for item in suite['inputs']:
                        path = (ROOT / item['path']).resolve()
                        if not path.is_relative_to(ROOT):
                            raise ValueError('input outside repository')
                        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
                            raise ValueError('input checksum mismatch')
                        x = pipeline.read_wav(str(path))
                        samples = item.get('stop_sample', len(x))
                        if not 0 < samples <= len(x):
                            raise ValueError('invalid Stop sample')
                        x = x[:samples].copy()
                        report['memory'].append(memory('buffer-loaded'))
                        row = take(pipeline, v, rec, x, executor, args.mode != 'unpaced-only', report['memory'])
                        row.update(id=item['id'], file=path.name, repetition=repetition)
                        if 'reference' in item:
                            import score
                            ref, hyp = score.normalise(item['reference'], item['lang']), score.normalise(row['text'], item['lang'])
                            row['prefixErrors'] = score.errors(ref, hyp)
                            row['cutWord'] = item.get('cut_word')
                            row['missingFinalOutput'] = bool(ref) and not hyp
                            row['tailMatches'] = hyp[-3:] == ref[-3:]
                            row['adjacentDuplicateWords'] = sum(a == b for a, b in zip(hyp, hyp[1:]))
                            report['endpoints'].append(row)
                        else:
                            report['takes'].append(row)
                        del x
                        report['memory'].append(memory('buffer-released'))
                        checkpoint()
            del rec
            report['memory'].append(memory('recognizer-released'))
            result['outcome'] = 'complete'
        except Exception:
            result['outcome'] = 'failed'
            result['failure'] = traceback.format_exc()
            raise
        finally:
            checkpoint()

if __name__ == '__main__':
    main()
