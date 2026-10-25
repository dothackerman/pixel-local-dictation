#!/usr/bin/env python3
"""Build the arm64 Sherpa 1.13.8 JNI against the official ORT 1.31.0 core, with the
role-profiler collector and the WebGPU/encoder-provider patch. One coherent runtime:
the AAR carries only the 1.31 core and this JNI. No phone access or inference.

usage: build.py /path/to/onnxruntime-android-1.31.0.aar
"""
import hashlib, json, os, pathlib, re, shutil, subprocess, sys, tarfile, time, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
PROFILER = ROOT/'tools/native-role-profiler'
WORK = ROOT/'build/ort131-sherpa'
COMMIT = '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf'
SHERPA_SHA = '0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2'
ORT_AAR_SHA = '72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7'
CORE_SHA = '8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0'
CORE = 'jni/arm64-v8a/libonnxruntime.so'
SDK = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
                   or pathlib.Path.home()/'Android/Sdk')
NDK = SDK/'ndk/30.0.16248370'
LLVM = NDK/'toolchains/llvm/prebuilt/linux-x86_64/bin'
CMAKE = SDK/'cmake/4.1.2/bin/cmake'
NINJA = CMAKE.with_name('ninja')


def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()


def run(args, timeout, log, env=None):
    start = time.monotonic()
    with log.open('w') as sink:
        subprocess.run([str(x) for x in args], check=True, timeout=timeout, env=env,
                       stdout=sink, stderr=subprocess.STDOUT)
    return time.monotonic() - start


def needed_versions(lib):
    """Symbol versions this ELF requires from libonnxruntime.so."""
    out = subprocess.run([str(LLVM/'llvm-readelf'), '-V', str(lib)], check=True, timeout=30,
                         capture_output=True, text=True).stdout
    section = out.split('libonnxruntime.so', 1)[1].split('File:', 1)[0] if 'libonnxruntime.so' in out else ''
    return sorted(set(re.findall(r'Name: (VERS_[0-9.]+)', section)))


def main(ort_aar):
    assert sha(ort_aar) == ORT_AAR_SHA, 'official ORT 1.31.0 AAR digest mismatch'
    WORK.mkdir(parents=True, exist_ok=True)
    tarball = WORK/'sherpa.tar.gz'
    if not tarball.exists():
        cached = ROOT/'build/native-role-profiler/sherpa.tar.gz'
        if cached.exists():
            shutil.copy2(cached, tarball)
        else:
            subprocess.run(['curl', '-fL', '--max-time', '85', '-o', str(tarball),
                            f'https://github.com/k2-fsa/sherpa-onnx/archive/{COMMIT}.tar.gz'], check=True, timeout=90)
    assert sha(tarball) == SHERPA_SHA, 'sherpa source hash mismatch'
    source = WORK/f'sherpa-onnx-{COMMIT}'
    if source.exists():
        shutil.rmtree(source)  # Patches apply only to pristine pinned source.
    def safe(member, path):
        # Upstream ships absolute symlinks under scripts/go; skip unsafe links, keep the data filter.
        try:
            return tarfile.data_filter(member, path)
        except (tarfile.AbsoluteLinkError, tarfile.LinkOutsideDestinationError):
            assert '/sherpa-onnx/csrc/' not in member.name and '/cmake/' not in member.name, member.name
            return None
    with tarfile.open(tarball) as archive:
        archive.extractall(WORK, filter=safe)
    subprocess.run([sys.executable, str(PROFILER/'patch-source.py'), str(source)], check=True, timeout=30)
    subprocess.run([sys.executable, str(HERE/'patch-webgpu.py'), str(source)], check=True, timeout=30)

    ort = WORK/'ort'
    if ort.exists():
        shutil.rmtree(ort)
    (ort/'headers').mkdir(parents=True)
    (ort/'lib').mkdir()
    with zipfile.ZipFile(ort_aar) as archive:
        core = archive.read(CORE)
        assert hashlib.sha256(core).hexdigest() == CORE_SHA, 'ORT core digest mismatch'
        (ort/'lib/libonnxruntime.so').write_bytes(core)
        for name in archive.namelist():
            if name.startswith('headers/') and not name.endswith('/'):
                (ort/'headers'/pathlib.Path(name).name).write_bytes(archive.read(name))
    env = dict(os.environ, SHERPA_ONNXRUNTIME_LIB_DIR=str(ort/'lib'),
               SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort/'headers'))
    flags = ['-G', 'Ninja', f'-DCMAKE_MAKE_PROGRAM={NINJA}',
             f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake',
             '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-36', '-DCMAKE_BUILD_TYPE=Release',
             '-DBUILD_SHARED_LIBS=ON', '-DSHERPA_ONNX_ENABLE_JNI=ON']
    flags += [f'-DSHERPA_ONNX_ENABLE_{key}=OFF' for key in
              ('TTS', 'SPEAKER_DIARIZATION', 'BINARY', 'C_API', 'WEBSOCKET', 'PORTAUDIO')]
    build = WORK/'android'
    if build.exists():
        shutil.rmtree(build)
    configure_seconds = run([CMAKE, '-S', source, '-B', build, *flags], 240, WORK/'configure.log', env)
    compile_seconds = run([CMAKE, '--build', build, '--target', 'sherpa-onnx-jni', '-j', '4'], 900, WORK/'compile.log')
    jni = WORK/'libsherpa-onnx-jni.so'
    subprocess.run([str(LLVM/'llvm-strip'), '--strip-unneeded', '-o', str(jni),
                    str(build/'lib/libsherpa-onnx-jni.so')], check=True, timeout=30)
    versions = needed_versions(jni)
    assert versions == ['VERS_1.31.0'], f'JNI must bind only the 1.31 core, got {versions}'

    stock = ROOT/'app/libs/sherpa-onnx-1.13.8.aar'
    out = WORK/'sherpa-ort131.aar'
    dropped = []
    with zipfile.ZipFile(stock) as inp, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as dest:
        for entry in inp.infolist():
            name = entry.filename
            if name.startswith('jni/') and name.endswith('.so') and not name.startswith('jni/arm64-v8a/'):
                dropped.append(name)  # arm64-only app; other ABIs would carry the 1.28 core.
                continue
            if name in ('jni/arm64-v8a/libsherpa-onnx-c-api.so', 'jni/arm64-v8a/libsherpa-onnx-cxx-api.so'):
                dropped.append(name)  # Bound to VERS_1.28.2 and never loaded.
                continue
            data = {CORE: core, 'jni/arm64-v8a/libsherpa-onnx-jni.so': jni.read_bytes()}.get(name)
            dest.writestr(entry, data if data is not None else inp.read(name))
    with zipfile.ZipFile(out) as built:
        natives = sorted(n for n in built.namelist() if n.endswith('.so'))
    patched = [p for p in (source/'sherpa-onnx/csrc').iterdir() if p.is_file() and 'Dictation patch' in p.read_text(errors='ignore')]
    receipt = {'schema': 1, 'sherpa_commit': COMMIT, 'ort_version': '1.31.0',
               'ort_source_commit': '402979ac1bef89dcd55add41eaa7277c407599eb',
               'official_aar_sha256': ORT_AAR_SHA, 'core_sha256': CORE_SHA, 'jni_needs': versions,
               'ndk': str(NDK), 'cmake': str(CMAKE), 'flags': flags,
               'configure_seconds': configure_seconds, 'compile_seconds': compile_seconds,
               'natives': natives, 'dropped': dropped,
               'artifacts': {str(p.relative_to(ROOT)): sha(p) for p in (stock, out, jni, tarball)},
               'patch_scripts': {str(p.relative_to(ROOT)): sha(p) for p in
                                 (HERE/'patch-webgpu.py', HERE/'build.py', PROFILER/'patch-source.py')},
               'patched_sources': {p.name: sha(p) for p in sorted(patched)},
               'command': f'./gradlew -PnativeRoleProfilerAar={out} :app:assembleBenchmark :app:assembleBenchmarkAndroidTest'}
    (WORK/'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main(pathlib.Path(sys.argv[1]))
