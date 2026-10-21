#!/usr/bin/env python3
"""Prepare a bounded synthetic Android encoder probe. No inference or recordings."""
import argparse
import base64
import errno
import os
import shutil
import hashlib
import json
import re
import math
from pathlib import Path
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ORT_SHA = '72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7'
CORE_SHA = '8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0'
JNI_SHA = '5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29'
SOURCE_COMMIT = '402979ac1bef89dcd55add41eaa7277c407599eb'
CORE = 'jni/arm64-v8a/libonnxruntime.so'
JNI = 'jni/arm64-v8a/libonnxruntime4j_jni.so'

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')

PLACEMENT = 'encoder-placement-v1'
SHORT_V2 = 'short-encoder-v2'
SHAPE_TYPES = {6, 7, 9}  # ONNX INT32, INT64, BOOL


def placement_check(listing, steps):
    """ADR-023 as amended: cause lines name every CPU step (count equals ORT's
    declared CPU total) and each CPU step touches only integer/bool tensors.
    `steps` maps original node name -> (op type, [element types of inputs+outputs])."""
    providers = listing['providers']
    names = [name for _, name in listing['causes']['kernel_not_found']] + list(listing['causes']['cpu_preferred'])
    declared = providers.get('CPUExecutionProvider', {}).get('declared', 0)
    failures = []
    if set(providers) - {'CPUExecutionProvider', 'WebGpuExecutionProvider'}:
        failures.append('unexpected provider')
    # No WebGPU-count condition (ADR-023 amendment 2): logd may drop that summary
    # line, and with two providers the matched CPU cause count already implies it.
    if '' in names:
        failures.append('unnamed CPU step cannot be identified')
    for op, name in listing['causes']['kernel_not_found']:
        if name in steps and steps[name][0] != op:
            failures.append('cause op %s differs from graph op for %s' % (op, name))
    if len(set(names)) != len(names) or len(names) != declared:
        failures.append('cause lines name %d steps (%d unique); ORT declared %d' % (len(names), len(set(names)), declared))
    for name in sorted(set(names)):
        if name not in steps:
            failures.append('CPU step not in original graph: ' + name)
        elif any(kind not in SHAPE_TYPES for kind in steps[name][1]):
            failures.append('CPU step touches non-shape tensor: %s (%s)' % (name, steps[name][0]))
    return {'passed': not failures, 'cpu_steps': declared,
            'kernel_not_found': len(listing['causes']['kernel_not_found']),
            'cpu_preferred': len(listing['causes']['cpu_preferred']),
            'webgpu_steps': providers.get('WebGpuExecutionProvider', {}).get('declared', 0),
            'failures': failures[:20]}


def graph_steps(model):
    """Node name -> (op type, inferred element types); unknown types count as 0 (fail)."""
    import onnx
    graph = onnx.shape_inference.infer_shapes(onnx.load(str(model), load_external_data=False)).graph
    kinds = {value.name: value.type.tensor_type.elem_type
             for value in list(graph.value_info) + list(graph.input) + list(graph.output)}
    kinds.update({tensor.name: tensor.data_type for tensor in graph.initializer})
    steps, duplicates = {}, set()
    for node in graph.node:
        if node.name in steps:
            duplicates.add(node.name)
        steps[node.name] = (node.op_type, [kinds.get(name, 0) for name in list(node.input) + list(node.output) if name])
    for name in duplicates | {''}:
        steps.pop(name, None)  # Ambiguous names cannot identify a CPU step.
    return steps

CHUNK_V1 = 'chunk-encoder-v1'
PIPELINE = 'pipeline-placement-v1'
GATE_VARIANT = 'variants/beam4-fp32-gate.json'
FEED_SAMPLES = 640  # MeasurementRun/LabTest feed size: the smallest possible final chunk
RATE, SHIFT = 16000, 160  # Sherpa fbank: 10 ms shift, snip_edges=false
SOURCES = ['tools/encoder_probe.py', 'tools/encoder_conversion.py', 'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
           'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt', 'app/build.gradle.kts']
CHUNK_SOURCES = SOURCES + ['app/src/encoderProbe/java/ch/og/dictation/GpuEvidence.java']
PIPELINE_SOURCES = CHUNK_SOURCES + ['app/src/encoderProbe/java/ch/og/dictation/PipelinePlacement.java',
                                    'app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt',
                                    'app/src/main/kotlin/ch/og/dictation/Variant.kt']
PIPELINE_MODEL = ('encoder.onnx', 'encoder.weights', 'decoder.onnx', 'joiner.onnx', 'tokens.txt')
WEBGPU_OPTIONS = dict(enableGraphCapture='0', enableInt64='1', preferredLayout='NCHW', dawnBackendType='Vulkan')
VULKAN_HAL = re.compile(r'/(vendor|odm|system)/lib64/hw/vulkan\.[A-Za-z0-9_.-]+\.so')


def frames(samples):
    """kaldi-native-fbank frame count for a flushed stream with snip_edges=false."""
    return (samples + SHIFT // 2) // SHIFT


def encoded_length(length):
    """FastConformer 8x subsampling: three stride-2 convolutions, kernel 3, padding 1."""
    for _ in range(3):
        length = (length - 1) // 2 + 1
    return length


def chunk_fixtures(variant):
    """Encoder inputs spanning the chunk envelope the variant's PauseChunker produces.
    A chunk is cut pad_s after a pause once >= min_chunk_s; the VAD forces a pause after
    vad_max_speech_s of speech; max_chunk_s > 0 is a hard cap; the final rest can be one feed."""
    if str(ROOT / 'tools') not in sys.path:
        sys.path.insert(0, str(ROOT / 'tools'))
    import variant as variants
    cut = dict(variants.DEFAULT['cutting'], **variant.get('cutting', {}))
    sec = lambda seconds: frames(round(seconds * RATE))
    minimum, typical = sec(cut['min_chunk_s']), sec(cut['min_chunk_s'] + cut['pad_s'])
    upper = sec(cut['max_chunk_s']) if cut['max_chunk_s'] > 0 else sec(
        cut['min_chunk_s'] + cut['vad_max_speech_s'] + cut['pad_s'])
    tiny = frames(FEED_SAMPLES)
    rows = [('typical', typical, typical, 'noise', 1), ('tiny-tail', tiny, tiny, 'noise', 2),
            ('upper', upper, upper, 'noise', 3), ('silence', minimum, minimum, 'zeros', 0),
            ('masked-padding', typical, minimum, 'noise', 4)]
    return [{'name': name, 'shape': [1, 128, t], 'frames': t, 'true_length': length,
             'encoded_length': encoded_length(length), 'content': content, 'seed': seed}
            for name, t, length, content, seed in rows]


def fixture_features(fixture):
    """Little-endian FP32 [1,128,T]: zeros (normalized silence) or unit-variance uniform
    noise from SHAKE-256 over the valid frames; padded frames are zero like Sherpa's PadSequence."""
    import numpy as np
    t, length = fixture['frames'], fixture['true_length']
    values = np.zeros((1, 128, t), dtype='<f4')
    if fixture['content'] == 'noise':
        raw = hashlib.shake_256(b'%s:%d' % (CHUNK_V1.encode(), fixture['seed'])).digest(4 * 128 * length)
        unit = np.frombuffer(raw, dtype='<u4').astype(np.float64) / 2.0 ** 32
        values[0, :, :length] = ((unit * 2 - 1) * math.sqrt(3)).reshape(128, length)
    elif fixture['content'] != 'zeros':
        raise ValueError('unknown fixture content')
    return values.tobytes()


def stage_model(model, staged_model, names):
    identities = {name: sha(model / name) for name in names}
    staged_model.mkdir()
    for name, expected in identities.items():
        source, target = (model / name).resolve(strict=True), staged_model / name
        try:
            os.link(source, target)
        except OSError as error:
            if error.errno != errno.EXDEV:
                raise
            shutil.copy2(source, target)
        if sha(target) != expected:
            raise ValueError('model changed during staging: ' + name)
    return identities


def runtime_identity():
    return {'version': '1.31.0', 'official_aar_sha256': ORT_SHA, 'core_sha256': CORE_SHA, 'jni_sha256': JNI_SHA,
            'source_commit': SOURCE_COMMIT}


def freeze_chunk(model, out, builds, provider):
    variant = json.loads((ROOT / GATE_VARIANT).read_text())
    fixtures = chunk_fixtures(variant)
    out.mkdir(parents=True, exist_ok=False)
    (out / 'fixtures').mkdir()
    for fixture in fixtures:
        (out / 'fixtures' / (fixture['name'] + '.f32')).write_bytes(fixture_features(fixture))
        (out / 'fixtures' / (fixture['name'] + '.i64')).write_bytes(struct.pack('<q', fixture['true_length']))
        fixture['features_sha256'] = sha(out / 'fixtures' / (fixture['name'] + '.f32'))
        fixture['length_sha256'] = sha(out / 'fixtures' / (fixture['name'] + '.i64'))
    (out / 'variant.json').write_bytes((ROOT / GATE_VARIANT).read_bytes())
    threads = variant.get('threads', 4)
    value = {'schema': 1, 'protocol': CHUNK_V1, 'input_kind': 'synthetic features; no recordings',
             'provider': provider, 'eligible': False, 'scope': 'chunk-shape tensor gate; full take not run',
             'atol': 1e-4, 'rtol': 1e-3, 'model': stage_model(model, out / 'model', ('encoder.onnx', 'encoder.weights')),
             'builds': builds, 'fixtures': fixtures,
             'envelope': {'cutting': variant.get('cutting', 'defaults'), 'feed_samples': FEED_SAMPLES,
                          'frames': '(samples + 80) // 160', 'encoded': '3x ((n - 1) // 2 + 1)'},
             # Sherpa session.cc: intra = inter = num_threads, default (ALL) optimization, sequential,
             # CPU fallback allowed (ADR-023 placement check instead of strict rejection).
             'session_options': {'intra_op_threads': threads, 'inter_op_threads': threads, 'optimization': 'ALL_OPT',
                                 'execution_mode': 'SEQUENTIAL', 'cpu_fallback': 'allowed'},
             'staging_envelope': {'variant.json': sha(out / 'variant.json')},
             'runtime': runtime_identity(), 'sources': {name: sha(ROOT / name) for name in CHUNK_SOURCES}}
    write(out / 'encoder-probe.json', value)
    print(json.dumps({'path': str(out / 'encoder-probe.json'), 'sha256': sha(out / 'encoder-probe.json')}))


def freeze_pipeline(model, out, builds):
    """Untimed Sherpa session-creation diagnostic: the gate variant plus the WebGPU encoder and
    session-level VERBOSE ORT logging (LogSeverityLevel=0). No audio, no Run."""
    variant = json.loads((ROOT / GATE_VARIANT).read_text())
    variant['runtime'] = dict(variant.get('runtime', {}), encoder_provider='webgpu', placement_log=True)
    out.mkdir(parents=True, exist_ok=False)
    write(out / 'variant.json', variant)
    value = {'schema': 1, 'protocol': PIPELINE, 'provider': 'webgpu', 'eligible': False,
             'scope': 'untimed placement and adapter diagnostic; no audio, no Run',
             'model': stage_model(model, out / 'model', PIPELINE_MODEL), 'builds': builds,
             'variant_sha256': sha(out / 'variant.json'), 'session_log_severity': 0,
             'runtime': runtime_identity(), 'sources': {name: sha(ROOT / name) for name in PIPELINE_SOURCES}}
    write(out / 'pipeline-placement.json', value)
    print(json.dumps({'path': str(out / 'pipeline-placement.json'), 'sha256': sha(out / 'pipeline-placement.json')}))


def freeze(model, out, app_apk, test_apk, provider="cpu", protocol='short-encoder-v1'):
    if protocol in (PLACEMENT, PIPELINE) and provider != 'webgpu':
        raise ValueError('placement listing requires --provider webgpu')
    builds = {'app_sha256': sha(app_apk), 'test_sha256': sha(test_apk)}
    if protocol == CHUNK_V1:
        return freeze_chunk(model, out, builds, provider)
    if protocol == PIPELINE:
        return freeze_pipeline(model, out, builds)
    identities = {name: sha(model / name) for name in ('encoder.onnx', 'encoder.weights')}
    out.mkdir(parents=True, exist_ok=False)
    (out / 'audio_signal.f32').write_bytes(bytes(1 * 128 * 17 * 4))
    (out / 'length.i64').write_bytes(struct.pack('<q', 17))
    # The protected staging command requires its established variant/model
    # envelope even though the short tensor helper never consumes the variant.
    (out / 'variant.json').write_bytes((ROOT / 'variants/beam4-fp32-gate.json').read_bytes())
    staged_model = out / 'model'
    staged_model.mkdir()
    for name, expected in identities.items():
        source, target = (model / name).resolve(strict=True), staged_model / name
        try:
            os.link(source, target)
        except OSError as error:
            if error.errno != errno.EXDEV:
                raise
            shutil.copy2(source, target)
        if sha(target) != expected:
            raise ValueError('model changed during staging: ' + name)
    sources = ['tools/encoder_probe.py', 'tools/encoder_conversion.py', 'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
               'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt', 'app/build.gradle.kts']
    scope = ('placement listing only; Run not executed' if protocol == PLACEMENT
             else 'short feasibility checkpoint; full take not run')
    value = {'schema': 1, 'protocol': protocol, 'input_kind': 'synthetic silence; no recordings',
             'shape': [1, 128, 17], 'true_length': 17, 'provider': provider,
             'eligible': False, 'scope': scope,
             'atol': 1e-4, 'rtol': 1e-3, 'model': identities, 'builds': builds,
             'inputs': {name: sha(out / name) for name in ('audio_signal.f32', 'length.i64')},
             'staging_envelope': {'variant.json': sha(out / 'variant.json')},
             'runtime': {'version': '1.31.0', 'official_aar_sha256': ORT_SHA, 'core_sha256': CORE_SHA, 'jni_sha256': JNI_SHA, 'source_commit': SOURCE_COMMIT},
             'sources': {name: sha(ROOT / name) for name in sources}}
    write(out / 'encoder-probe.json', value)
    print(json.dumps({'path': str(out / 'encoder-probe.json'), 'sha256': sha(out / 'encoder-probe.json')}))

def package(ort, sherpa, out):
    if sha(ort) != ORT_SHA:
        raise ValueError('official ORT 1.31.0 AAR digest mismatch')
    with zipfile.ZipFile(ort) as runtime, zipfile.ZipFile(sherpa) as original:
        core = runtime.read(CORE)
        if hashlib.sha256(core).hexdigest() != CORE_SHA:
            raise ValueError('ORT core digest mismatch')
        if original.namelist().count(CORE) != 1:
            raise ValueError('expected exactly one original arm64 runtime core')
        out.mkdir(parents=True, exist_ok=False)
        with zipfile.ZipFile(out / 'sherpa-ort131.aar', 'w', zipfile.ZIP_DEFLATED) as target:
            for entry in original.infolist():
                target.writestr(entry, core if entry.filename == CORE else original.read(entry.filename))
        # App owns the sole core; test APK owns matching JNI + Java only.
        with zipfile.ZipFile(out / 'ort131-java-jni.aar', 'w', zipfile.ZIP_DEFLATED) as target:
            # The published library manifest registers telemetry and network
            # permissions. This isolated offline probe needs Java/JNI only.
            target.writestr('AndroidManifest.xml',
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.onnxruntime">'
                '<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" /></manifest>')
            for name in ('classes.jar', JNI, 'proguard.txt'):
                target.writestr(name, runtime.read(name))
        headers = out / 'headers'
        headers.mkdir()
        for name in runtime.namelist():
            if name.startswith('headers/') and not name.endswith('/'):
                (headers / Path(name).name).write_bytes(runtime.read(name))
    artifacts = {p.name: sha(p) for p in out.iterdir() if p.is_file()}
    write(out / 'runtime.json', {'schema': 1, 'version': '1.31.0', 'official_aar_sha256': ORT_SHA,
        'core_sha256': CORE_SHA, 'jni_sha256': JNI_SHA, 'source_commit': SOURCE_COMMIT, 'original_sherpa_sha256': sha(sherpa), 'artifacts': artifacts,
        'headers': {p.name: sha(p) for p in headers.iterdir()},
        'manifest_policy': 'SDK36 library manifest; no permissions, providers or other components',
        'limitation': 'Synthetic direct ORT only. Sherpa JNI compatibility and full-take integration not established.'})
    print(json.dumps({'path': str(out), 'artifacts': artifacts}))

def validate_short_comparison_receipt(run):
    plan = run['frozen']
    sources = {'tools/encoder_probe.py','tools/encoder_conversion.py',
               'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
               'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt','app/build.gradle.kts'}
    model = {'encoder.onnx':'2144d185b85e7c75077a19afbd8b77f0cb8daf814844c79f213e68e8fbf5a725',
             'encoder.weights':'9a22d372c51455c34f13405da2520baefb7125bd16981397561423ed32d24f36'}
    inputs = {'audio_signal.f32':'e8b31e302d11fbf7da124b537ba2d44f88e165da03c6557e2b0f6dc486e025bb',
              'length.i64':'35e3a6176b50da27fcb868bf840f0e76290bd9bf55c40545e67bac66353d0674'}
    runtime = dict(version='1.31.0',official_aar_sha256=ORT_SHA,core_sha256=CORE_SHA,jni_sha256=JNI_SHA,source_commit=SOURCE_COMMIT)
    fields = {'schema','protocol','input_kind','shape','true_length','provider','eligible','scope',
              'atol','rtol','model','builds','inputs','staging_envelope','runtime','sources'}
    listed = run.get('protocol') == SHORT_V2 and plan.get('provider') == 'webgpu'
    if (run.get('protocol') not in ('short-encoder-v1', SHORT_V2) or run.get('protocol') != plan.get('protocol')
            or type(run.get('schema')) is not int or run['schema'] != 1
            or run.get('eligible') is not False or not re.fullmatch('[0-9a-f]{32}',run.get('attempt_id',''))
            or not re.fullmatch('[0-9a-f]{64}',run.get('freeze_sha256',''))
            or plan.get('schema') != 1
            or set(plan) != fields or plan.get('eligible') is not False
            or plan.get('input_kind') != 'synthetic silence; no recordings'
            or plan.get('scope') != 'short feasibility checkpoint; full take not run'
            or plan.get('atol') != 1e-4 or plan.get('rtol') != 1e-3
            or plan.get('staging_envelope') != {'variant.json':sha(ROOT / 'variants/beam4-fp32-gate.json')}
            or plan.get('shape') != [1,128,17] or plan.get('true_length') != 17
            or plan.get('model') != model or plan.get('inputs') != inputs or plan.get('runtime') != runtime
            or set(plan.get('sources',{})) != sources
            or any(not re.fullmatch('[0-9a-f]{64}',value) for value in plan['sources'].values())
            or any(type(run.get(k)) is not int or run[k] < (1 if k=='pid' else 0) for k in ('pid','processStartElapsedMs'))
            or any(run.get(k) != 'unproven' for k in ('adapter_driver_proof','fp32_shader_proof'))
            or run.get('residual_node_assignment') not in (('listed','incomplete') if listed else ('unproven',))
            or listed != ('placement' in run)
            or run.get('checkpoint') != 'outputs-materialized' or run.get('initialization_complete') is not True):
        raise ValueError('unsupported typed short encoder receipt or frozen inputs')
    if run.get('cpu_fallback_disabled') is not (plan['provider']=='webgpu' and plan['protocol']=='short-encoder-v1'):
        raise ValueError('short encoder fallback policy differs')
    if plan['provider']=='webgpu' and run.get('provider_options') != dict(enableGraphCapture='0',enableInt64='1',preferredLayout='NCHW',dawnBackendType='Vulkan'):
        raise ValueError('short encoder WebGPU options differ')
    phases={'before-load','after-load','after-output','finally'}
    memory=run.get('memory',[])
    if (len(memory)!=4 or {row.get('phase') for row in memory}!=phases
            or any(any(type(row.get(k)) is not int or row[k]<0 for k in ('highWaterKb','pssKb','thermalStatus'))
                   or row['thermalStatus']>6 for row in memory)):
        raise ValueError('short encoder resource observations differ')
    for key in ('cold_load_ms','run_to_host_output_ms','initialization_elapsed_ms'):
        if type(run.get(key)) not in (int,float) or not math.isfinite(run[key]) or run[key]<0:
            raise ValueError('short encoder timing missing or invalid')
    if len(run.get('outputs',[]))!=2:
        raise ValueError('short encoder outputs missing')
    for row,(name,dtype,shape,size) in zip(run['outputs'],[
        ('encoder-probe-output0.f32','FLOAT',[1,1024,3],12288),('encoder-probe-output1.i64','INT64',[1],8)]):
        if row.get('file')!=name or row.get('dtype')!=dtype or row.get('shape')!=shape:
            raise ValueError('short encoder fixed tensor type or shape differs')
        raw=base64.b64decode(row['raw_base64'],validate=True)
        if len(raw)!=size or hashlib.sha256(raw).hexdigest()!=row['sha256']:
            raise ValueError('output changed')
        if dtype=='FLOAT' and not all(math.isfinite(x[0]) for x in struct.iter_unpack('<f',raw)):
            raise ValueError('nonfinite short output')
        if dtype=='INT64' and struct.unpack('<q',raw)!=(3,):
            raise ValueError('short encoded length differs')


def _check(valid, reason):
    if not valid:
        raise ValueError(reason)


def validate_chunk_receipt(run, provider):
    plan = run['frozen']
    variant = json.loads((ROOT / GATE_VARIANT).read_text())
    expected = chunk_fixtures(variant)
    _check(run.get('protocol') == CHUNK_V1 == plan.get('protocol') and run.get('schema') == 1 == plan.get('schema')
           and run.get('eligible') is False is plan.get('eligible') and plan.get('provider') == provider
           and re.fullmatch('[0-9a-f]{32}', run.get('attempt_id', '')) and re.fullmatch('[0-9a-f]{64}', run.get('freeze_sha256', ''))
           and plan.get('input_kind') == 'synthetic features; no recordings'
           and plan.get('atol') == 1e-4 and plan.get('rtol') == 1e-3
           and plan.get('staging_envelope') == {'variant.json': sha(ROOT / GATE_VARIANT)}
           and plan.get('runtime') == runtime_identity() and set(plan.get('sources', {})) == set(CHUNK_SOURCES)
           and set(plan.get('model', {})) == {'encoder.onnx', 'encoder.weights'}
           and all(type(run.get(k)) is int and run[k] >= (1 if k == 'pid' else 0) for k in ('pid', 'processStartElapsedMs'))
           and run.get('checkpoint') == 'outputs-materialized' and run.get('initialization_complete') is True
           and run.get('runtime_version') == '1.31.0' and run.get('fp32_shader_proof') == 'unproven',
           'unsupported chunk encoder receipt or frozen plan')
    fixtures = plan.get('fixtures', [])
    _check(len(fixtures) == len(expected) and all(
        {k: v for k, v in f.items() if not k.endswith('_sha256')} == e
        and f.get('features_sha256') == hashlib.sha256(fixture_features(e)).hexdigest()
        and f.get('length_sha256') == hashlib.sha256(struct.pack('<q', e['true_length'])).hexdigest()
        for f, e in zip(fixtures, expected)), 'frozen fixtures differ from the variant envelope')
    threads = variant.get('threads', 4)
    options = {'intra_op_threads': threads, 'inter_op_threads': threads, 'optimization': 'ALL_OPT',
               'execution_mode': 'SEQUENTIAL'}
    _check(plan.get('session_options') == dict(options, cpu_fallback='allowed')
           and all(run.get(k) == v for k, v in options.items()) and run.get('profiling') is False
           and run.get('cpu_fallback_disabled') is False, 'session options differ from the Sherpa encoder session')
    _check(provider != 'webgpu' or run.get('provider_options') == WEBGPU_OPTIONS, 'WebGPU options differ from Sherpa patch')
    bindings = {'build_sha256': plan['builds']['app_sha256'], 'test_build_sha256': plan['builds']['test_sha256'],
                'runtime_core_sha256': CORE_SHA, 'runtime_jni_sha256': JNI_SHA}
    _check(all(run.get(k) == v for k, v in bindings.items()), 'installed build/runtime identity differs from freeze')
    memory = run.get('memory', [])
    _check(len(memory) == 4 and {row.get('phase') for row in memory} == {'before-load', 'after-load', 'after-output', 'finally'}
           and all(type(row.get(k)) is int and row[k] >= 0 for row in memory for k in ('highWaterKb', 'pssKb', 'thermalStatus')),
           'chunk encoder resource observations differ')
    evidence = run.get('gpu_evidence')
    _check(isinstance(evidence, dict) and all(isinstance(evidence.get(k), list)
           for k in ('mapped_gpu_libraries', 'device_nodes', 'ep_devices')), 'GPU evidence missing')
    _check(len(run.get('fixtures', [])) == len(fixtures), 'fixture outputs missing')


def load_chunk_tensors(folder, run):
    import numpy as np
    tensors = []
    for row, fixture in zip(run['fixtures'], run['frozen']['fixtures']):
        _check(row.get('name') == fixture['name'] and len(row.get('outputs', [])) == 2, 'fixture outputs differ')
        pair = []
        for index, (dtype, shape) in enumerate((('FLOAT', [1, 1024, encoded_length(fixture['frames'])]), ('INT64', [1]))):
            output = row['outputs'][index]
            name = 'outputs/%s-%d.%s' % (fixture['name'], index, 'f32' if index == 0 else 'i64')
            _check(output.get('file') == name and output.get('dtype') == dtype and output.get('shape') == shape,
                   'output name, dtype or shape differs: ' + fixture['name'])
            raw = (folder / name).read_bytes()
            _check(hashlib.sha256(raw).hexdigest() == output.get('sha256'), 'output changed: ' + name)
            pair.append(np.frombuffer(raw, dtype='<f4' if index == 0 else '<i8').reshape(shape))
        _check(int(pair[1][0]) == fixture['encoded_length'], 'encoded length differs from true length: ' + fixture['name'])
        tensors.append(pair)
    return tensors


def gpu_summary(reference, candidate):
    """Physical adapter/driver observation: the WebGPU process mapped a Vulkan HAL and opened a
    GPU device node; the CPU control shows what the runtime maps without WebGPU."""
    hal = lambda run: [row for row in run['gpu_evidence']['mapped_gpu_libraries'] if VULKAN_HAL.fullmatch(row.get('path', ''))]
    evidence = candidate['gpu_evidence']
    observed = bool(hal(candidate)) and bool(evidence['device_nodes'])
    return {'status': 'observed' if observed else 'unproven', 'vulkan_hal': hal(candidate),
            'device_nodes': evidence['device_nodes'], 'ep_devices': evidence['ep_devices'],
            'cpu_control_vulkan_hal': hal(reference)}


def compare_chunk(reference, candidate):
    import numpy as np
    from encoder_conversion import tensor_verdict
    runs, hashes = [], []
    for folder, provider in ((reference, 'cpu'), (candidate, 'webgpu')):
        result = folder / 'result.json'
        _check(result.stat().st_size <= 1024 * 1024, 'chunk result exceeds fixed byte bound')
        run = json.loads(result.read_text())
        hashes.append(sha(result))
        if run.get('outcome') != 'complete':
            print(json.dumps({'protocol': CHUNK_V1, 'eligible': False, 'tensor_verdict': {'passed': False},
                              'failure': run.get('failure', 'incomplete run'), 'checkpoint': run.get('checkpoint'),
                              'receipt_sha256': hashes, 'full_take': 'not run'}))
            return
        validate_chunk_receipt(run, provider)
        runs.append((run, load_chunk_tensors(folder, run)))
    (cpu, cpu_tensors), (gpu, gpu_tensors) = runs
    _check(cpu['attempt_id'] != gpu['attempt_id'] and (cpu['pid'], cpu['processStartElapsedMs']) !=
           (gpu['pid'], gpu['processStartElapsedMs']), 'CPU/GPU require separate attempts and fresh processes')
    common = [{k: v for k, v in run['frozen'].items() if k != 'provider'} for run in (cpu, gpu)]
    _check(common[0] == common[1], 'unmatched frozen builds/runtime/sources or fixtures/settings')
    rows = []
    for fixture, a, b in zip(cpu['frozen']['fixtures'], cpu_tensors, gpu_tensors):
        valid = fixture['encoded_length']
        verdict = tensor_verdict([np.ascontiguousarray(a[0][:, :, :valid]), a[1]],
                                 [np.ascontiguousarray(b[0][:, :, :valid]), b[1]])
        padded_finite = bool(np.isfinite(a[0]).all() and np.isfinite(b[0]).all())
        rows.append({'name': fixture['name'], 'shape': fixture['shape'], 'true_length': fixture['true_length'],
                     'compared_frames': valid, 'passed': verdict['passed'] and padded_finite,
                     'padded_frames_finite': padded_finite, 'outputs': verdict['outputs']})
    print(json.dumps({'protocol': CHUNK_V1, 'eligible': False,
                      'tensor_verdict': {'passed': all(row['passed'] for row in rows), 'fixtures': rows},
                      'reference': 'Android ORT 1.31 CPU', 'candidate': 'Android ORT 1.31 WebGPU',
                      'session': 'one retained session per provider; fixtures in frozen order',
                      'adapter_driver': gpu_summary(cpu, gpu), 'fp32_shader_proof': 'unproven',
                      'receipt_sha256': hashes, 'full_take': 'not run'}))


PLACED = re.compile(r'(All nodes|Node\(s\)) placed on \[([A-Za-z]+ExecutionProvider)\]\. Number of nodes: (\d+)')
MISSING = re.compile(r'webgpu kernel not found in registries for Op type: (\S+) node name: (.*?)\s*$')
PREFERRED = re.compile(r'Force fallback to CPU execution for node: (.*?) because ')


def pipeline_listing(lines):
    """Encoder placement from raw ORT lines of one Sherpa recognizer creation. Only the encoder
    session has WebGPU; decoder/joiner are CPU-only ("All nodes placed on [CPU…]") and ignored.
    Cause lines come only from the WebGPU provider, hence only from the encoder session."""
    providers, missing, preferred, webgpu = {}, [], [], 0
    for line in lines:
        if (m := MISSING.search(line)):
            missing.append([m.group(1), m.group(2)])
        elif (m := PREFERRED.search(line)):
            preferred.append(m.group(1))
        elif (m := PLACED.search(line)):
            kind, provider, count = m.group(1), m.group(2), int(m.group(3))
            if provider == 'WebGpuExecutionProvider':
                webgpu += 1
            elif kind == 'All nodes':
                continue  # a CPU-only session (decoder or joiner)
            _check(provider not in providers, 'repeated partial placement for ' + provider)
            providers[provider] = {'declared': count, 'all': kind == 'All nodes', 'nodes': []}
    _check(webgpu == 1, 'expected exactly one WebGPU encoder session listing, found %d' % webgpu)
    return {'complete': True, 'providers': providers, 'causes': {'kernel_not_found': missing, 'cpu_preferred': preferred}}


def pipeline_placement(run, model):
    plan = run.get('frozen', {})
    _check(run.get('protocol') == PIPELINE == plan.get('protocol') and run.get('outcome') == 'complete'
           and run.get('checkpoint') == 'placement-listed' and plan.get('session_log_severity') == 0
           and isinstance(run.get('placement_lines'), list) and all(isinstance(x, str) for x in run['placement_lines']),
           'needs a complete pipeline-placement-v1 receipt')
    _check(sha(model) == plan['model']['encoder.onnx'], 'model differs from the receipt freeze')
    _check(run.get('build_sha256') == plan['builds']['app_sha256'] and run.get('runtime_core_sha256') == CORE_SHA,
           'installed build/runtime identity differs from freeze')
    evidence = run.get('gpu_evidence', {})
    hal = [row for row in evidence.get('mapped_gpu_libraries', []) if VULKAN_HAL.fullmatch(row.get('path', ''))]
    return {'protocol': PIPELINE, 'session': 'Sherpa encoder session (in-pipeline options)',
            'placement_check': placement_check(pipeline_listing(run['placement_lines']), graph_steps(model)),
            'adapter_driver': {'status': 'observed' if hal and evidence.get('device_nodes') else 'unproven',
                               'vulkan_hal': hal, 'device_nodes': evidence.get('device_nodes', [])}}


def compare(reference, candidate, model=None):
    import numpy as np
    from encoder_conversion import tensor_verdict
    if json.loads((reference / 'result.json').read_text()).get('protocol') == CHUNK_V1:
        return compare_chunk(reference, candidate)
    runs = []
    hashes = []
    for folder, provider in ((reference, 'cpu'), (candidate, 'webgpu')):
        result = folder / 'result.json'
        if result.stat().st_size > 1024*1024:
            raise ValueError('short result exceeds fixed byte bound')
        run = json.loads(result.read_text())
        hashes.append(sha(result))
        if run.get('outcome') != 'complete':
            print(json.dumps({'eligible': False, 'tensor_verdict': {'passed': False},
                              'failure': run.get('failure', 'incomplete run'),
                              'checkpoint': run.get('checkpoint'), 'receipt_sha256': hashes,
                              'full_take': 'not run'}))
            return
        validate_short_comparison_receipt(run)
        if run['runtime_version'] != '1.31.0' or run['frozen']['provider'] != provider:
            raise ValueError('requires direct Android 1.31 CPU/WebGPU receipts')
        frozen = run['frozen']
        bindings = {'build_sha256': frozen['builds']['app_sha256'],
                    'test_build_sha256': frozen['builds']['test_sha256'],
                    'runtime_core_sha256': frozen['runtime']['core_sha256'],
                    'runtime_jni_sha256': frozen['runtime']['jni_sha256'],
                    'runtime_version': frozen['runtime']['version']}
        if any(run.get(field) != expected for field, expected in bindings.items()):
            raise ValueError('installed build/runtime identity differs from freeze')
        options = {'optimization': 'ALL_OPT', 'execution_mode': 'SEQUENTIAL',
                   'intra_op_threads': 4, 'inter_op_threads': 1, 'profiling': False}
        if any(run.get(field) != expected for field, expected in options.items()):
            raise ValueError('incompatible common session options')
        tensors = []
        for index, tensor in enumerate(run['outputs']):
            name = tensor['file']
            if Path(name).name != name:
                raise ValueError('output path escapes run')
            expected = 'FLOAT' if index == 0 else 'INT64'
            if tensor['dtype'] != expected:
                raise ValueError('unexpected output dtype')
            dtype = '<f4' if index == 0 else '<i8'
            file = folder / name
            raw = base64.b64decode(tensor['raw_base64'], validate=True)
            if file.exists() and (file.stat().st_size != len(raw) or file.read_bytes() != raw):
                raise ValueError('output changed')
            if hashlib.sha256(raw).hexdigest() != tensor['sha256']:
                raise ValueError('output changed')
            tensors.append(np.frombuffer(raw, dtype=dtype).reshape(tensor['shape']))
        runs.append((run, tensors))
    if (runs[0][0]['attempt_id'] == runs[1][0]['attempt_id'] or
            (runs[0][0]['pid'],runs[0][0]['processStartElapsedMs']) ==
            (runs[1][0]['pid'],runs[1][0]['processStartElapsedMs'])):
        raise ValueError('CPU/GPU require separate attempts and fresh processes')
    # Provider is the sole planned difference. Builds, runtime, source, fixture,
    # tolerances and every other frozen setting must describe the same setup.
    common_frozen = [{key: value for key, value in run['frozen'].items() if key != 'provider'}
                     for run, _ in runs]
    if common_frozen[0] != common_frozen[1]:
        raise ValueError('unmatched frozen builds/runtime/sources or inputs/settings')
    for field in ('builds', 'runtime', 'sources', 'inputs', 'model'):
        if not common_frozen[0].get(field):
            raise ValueError('missing frozen ' + field)
    report = {'eligible': False, 'tensor_verdict': tensor_verdict(runs[0][1], runs[1][1]),
              'reference': 'Android ORT 1.31 CPU', 'candidate': 'Android ORT 1.31 WebGPU',
              'receipt_sha256': hashes, 'physical_fp32_proof': 'unproven', 'full_take': 'not run'}
    if runs[1][0]['protocol'] == SHORT_V2:
        if model is None or sha(model) != runs[1][0]['frozen']['model']['encoder.onnx']:
            raise ValueError('short-encoder-v2 placement check needs --model with the frozen encoder.onnx')
        report['placement_check'] = placement_check(runs[1][0]['placement'], graph_steps(model))
    print(json.dumps(report))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    frozen = commands.add_parser('freeze')
    frozen.add_argument('--model', type=Path, required=True)
    frozen.add_argument('--out', type=Path, required=True)
    frozen.add_argument('--app-apk', type=Path, required=True)
    frozen.add_argument('--test-apk', type=Path, required=True)
    frozen.add_argument('--provider', choices=('cpu', 'webgpu'), default='cpu')
    frozen.add_argument('--protocol', choices=('short-encoder-v1', SHORT_V2, PLACEMENT, CHUNK_V1, PIPELINE), default='short-encoder-v1')
    packaged = commands.add_parser('package')
    packaged.add_argument('--ort-aar', type=Path, required=True)
    packaged.add_argument('--sherpa-aar', type=Path, required=True)
    packaged.add_argument('--out', type=Path, required=True)
    compared = commands.add_parser('compare')
    compared.add_argument('--reference', type=Path, required=True)
    compared.add_argument('--candidate', type=Path, required=True)
    compared.add_argument('--model', type=Path, help='frozen encoder.onnx; required for short-encoder-v2')
    checked = commands.add_parser('placement-check', help='ADR-023 check of one placement, v2 WebGPU or pipeline-placement-v1 receipt')
    checked.add_argument('--receipt', type=Path, required=True)
    checked.add_argument('--model', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'freeze':
            freeze(args.model, args.out, args.app_apk, args.test_apk, args.provider, args.protocol)
        elif args.command == 'compare':
            compare(args.reference, args.candidate, args.model)
        elif args.command == 'placement-check':
            run = json.loads(args.receipt.read_text())
            if run.get('protocol') == PIPELINE:
                print(json.dumps(pipeline_placement(run, args.model)))
                return
            if (run.get('outcome') != 'complete' or run.get('protocol') not in (PLACEMENT, SHORT_V2)
                    or run.get('residual_node_assignment') not in ('listed', 'incomplete')):
                raise ValueError('needs a complete placement or short-encoder-v2 WebGPU receipt')
            if sha(args.model) != run['frozen']['model']['encoder.onnx']:
                raise ValueError('model differs from the receipt freeze')
            print(json.dumps(placement_check(run['placement'], graph_steps(args.model))))
        else:
            package(args.ort_aar, args.sherpa_aar, args.out)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(2, str(error) + '\n')

if __name__ == '__main__':
    main()
