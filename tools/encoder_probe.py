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
WRAPPER_SOURCE = 'tools/proposals/phone-short-encoder-v3.py'
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

def freeze(model, out, app_apk, test_apk, provider="cpu", protocol='short-encoder-v1'):
    if protocol == PLACEMENT and provider != 'webgpu':
        raise ValueError('placement listing requires --provider webgpu')
    builds = {'app_sha256': sha(app_apk), 'test_sha256': sha(test_apk)}
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
    sources = ['tools/encoder_probe.py', 'tools/encoder_conversion.py', WRAPPER_SOURCE, 'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
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
    sources = {'tools/encoder_probe.py','tools/encoder_conversion.py',WRAPPER_SOURCE,
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


def compare(reference, candidate, model=None):
    import numpy as np
    from encoder_conversion import tensor_verdict
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
    frozen.add_argument('--protocol', choices=('short-encoder-v1', SHORT_V2, PLACEMENT), default='short-encoder-v1')
    packaged = commands.add_parser('package')
    packaged.add_argument('--ort-aar', type=Path, required=True)
    packaged.add_argument('--sherpa-aar', type=Path, required=True)
    packaged.add_argument('--out', type=Path, required=True)
    compared = commands.add_parser('compare')
    compared.add_argument('--reference', type=Path, required=True)
    compared.add_argument('--candidate', type=Path, required=True)
    compared.add_argument('--model', type=Path, help='frozen encoder.onnx; required for short-encoder-v2')
    checked = commands.add_parser('placement-check', help='ADR-023 check of one placement or v2 WebGPU receipt')
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
