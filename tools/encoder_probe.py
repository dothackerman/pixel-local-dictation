#!/usr/bin/env python3
"""Prepare a bounded synthetic Android encoder probe. No inference or recordings."""
import argparse
import base64
import hashlib
import json
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

def freeze(model, out, app_apk, test_apk, provider="cpu"):
    builds = {'app_sha256': sha(app_apk), 'test_sha256': sha(test_apk)}
    identities = {name: sha(model / name) for name in ('encoder.onnx', 'encoder.weights')}
    out.mkdir(parents=True, exist_ok=False)
    (out / 'audio_signal.f32').write_bytes(bytes(1 * 128 * 17 * 4))
    (out / 'length.i64').write_bytes(struct.pack('<q', 17))
    sources = ['tools/encoder_probe.py', 'tools/encoder_conversion.py', 'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
               'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt', 'app/build.gradle.kts']
    value = {'schema': 1, 'input_kind': 'synthetic silence; no recordings',
             'shape': [1, 128, 17], 'true_length': 17, 'provider': provider,
             'eligible': False, 'scope': 'short feasibility checkpoint; full take not run',
             'atol': 1e-4, 'rtol': 1e-3, 'model': identities, 'builds': builds,
             'inputs': {p.name: sha(p) for p in out.iterdir()},
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

def compare(reference, candidate):
    import numpy as np
    from encoder_conversion import tensor_verdict
    runs = []
    hashes = []
    for folder, provider in ((reference, 'cpu'), (candidate, 'webgpu')):
        result = folder / 'result.json'
        run = json.loads(result.read_text())
        hashes.append(sha(result))
        if run.get('outcome') != 'complete':
            print(json.dumps({'eligible': False, 'tensor_verdict': {'passed': False},
                              'failure': run.get('failure', 'incomplete run'),
                              'checkpoint': run.get('checkpoint'), 'receipt_sha256': hashes,
                              'full_take': 'not run'}))
            return
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
                   'intra_op_threads': 4, 'inter_op_threads': 1, 'profiling': True}
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
            raw = file.read_bytes() if file.exists() else base64.b64decode(tensor['raw_base64'], validate=True)
            if hashlib.sha256(raw).hexdigest() != tensor['sha256']:
                raise ValueError('output changed')
            tensors.append(np.frombuffer(raw, dtype=dtype).reshape(tensor['shape']))
        runs.append((run, tensors))
    # Provider is the sole planned difference. Builds, runtime, source, fixture,
    # tolerances and every other frozen setting must describe the same setup.
    common_frozen = [{key: value for key, value in run['frozen'].items() if key != 'provider'}
                     for run, _ in runs]
    if common_frozen[0] != common_frozen[1]:
        raise ValueError('unmatched frozen builds/runtime/sources or inputs/settings')
    for field in ('builds', 'runtime', 'sources', 'inputs', 'model'):
        if not common_frozen[0].get(field):
            raise ValueError('missing frozen ' + field)
    print(json.dumps({'eligible': False, 'tensor_verdict': tensor_verdict(runs[0][1], runs[1][1]),
                      'reference': 'Android ORT 1.31 CPU', 'candidate': 'Android ORT 1.31 WebGPU',
                      'receipt_sha256': hashes, 'physical_fp32_proof': 'unproven',
                      'full_take': 'not run'}))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    frozen = commands.add_parser('freeze')
    frozen.add_argument('--model', type=Path, required=True)
    frozen.add_argument('--out', type=Path, required=True)
    frozen.add_argument('--app-apk', type=Path, required=True)
    frozen.add_argument('--test-apk', type=Path, required=True)
    frozen.add_argument('--provider', choices=('cpu', 'webgpu'), default='cpu')
    packaged = commands.add_parser('package')
    packaged.add_argument('--ort-aar', type=Path, required=True)
    packaged.add_argument('--sherpa-aar', type=Path, required=True)
    packaged.add_argument('--out', type=Path, required=True)
    compared = commands.add_parser('compare')
    compared.add_argument('--reference', type=Path, required=True)
    compared.add_argument('--candidate', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'freeze':
            freeze(args.model, args.out, args.app_apk, args.test_apk, args.provider)
        elif args.command == 'compare':
            compare(args.reference, args.candidate)
        else:
            package(args.ort_aar, args.sherpa_aar, args.out)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(2, str(error) + '\n')

if __name__ == '__main__':
    main()
