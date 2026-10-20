#!/usr/bin/env python3
"""Build diagnostic arm64 Sherpa JNI, reusing the stock AAR's exact ORT binary.
No phone access, inference, model changes, or release AAR replacement.
"""
import hashlib, json, os, pathlib, shutil, subprocess, tarfile, time, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
WORK = ROOT/'build/native-role-profiler'
COMMIT = '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf'
SDK = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
                   or pathlib.Path.home()/'Android/Sdk')
NDK = SDK/'ndk/30.0.16248370'
CMAKE = SDK/'cmake/4.1.2/bin/cmake'
NINJA = CMAKE.with_name('ninja')

def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def fetch(url, path, expected):
    if not path.exists():
        subprocess.run(['curl','-fL','--connect-timeout','15','--max-time','85',url,'-o',str(path)],check=True,timeout=90)
    assert sha(path) == expected, f'dependency hash mismatch: {path}'
def run(args, timeout, log, env=None):
    start=time.monotonic()
    with log.open('w') as sink:
        subprocess.run([str(x) for x in args], check=True, timeout=timeout, env=env, stdout=sink, stderr=subprocess.STDOUT)
    return time.monotonic()-start

WORK.mkdir(parents=True,exist_ok=True)
fetch(f'https://github.com/k2-fsa/sherpa-onnx/archive/{COMMIT}.tar.gz', WORK/'sherpa.tar.gz', '0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2')
fetch('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip', WORK/'ort.zip', '01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d')
source=WORK/f'sherpa-onnx-{COMMIT}'
if not source.exists():
    with tarfile.open(WORK/'sherpa.tar.gz') as archive: archive.extractall(WORK,filter='data')
if not (source/'sherpa-onnx/csrc/role-profiler.cc').exists():
    subprocess.run(['python3',str(HERE/'patch-source.py'),str(source)],check=True,timeout=10)
else:
    # Compiler will rebuild collector changes; model call-site patch is immutable.
    for name in ('role-profiler.h','role-profiler.cc','role-profiler-jni.cc'):
        shutil.copy2(HERE/name,source/'sherpa-onnx/csrc'/name)
if not (WORK/'ort/headers/onnxruntime_cxx_api.h').exists():
    with zipfile.ZipFile(WORK/'ort.zip') as archive: archive.extractall(WORK/'ort')
stock=ROOT/'app/libs/sherpa-onnx-1.13.8.aar'
with zipfile.ZipFile(stock) as archive: ort=archive.read('jni/arm64-v8a/libonnxruntime.so')
assert ort == (WORK/'ort/jni/arm64-v8a/libonnxruntime.so').read_bytes()
(WORK/'lib').mkdir(exist_ok=True)
(WORK/'lib/libonnxruntime.so').write_bytes(ort)
env=dict(os.environ,SHERPA_ONNXRUNTIME_LIB_DIR=str(WORK/'lib'),SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(WORK/'ort/headers'))
flags=['-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={NINJA}',f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake','-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-36','-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=ON','-DSHERPA_ONNX_ENABLE_JNI=ON']
flags += [f'-DSHERPA_ONNX_ENABLE_{key}=OFF' for key in ('TTS','SPEAKER_DIARIZATION','BINARY','C_API','WEBSOCKET','PORTAUDIO')]
configure_seconds=run([CMAKE,'-S',source,'-B',WORK/'android',*flags],240,WORK/'configure.log',env)
compile_seconds=run([CMAKE,'--build',WORK/'android','--target','sherpa-onnx-jni','-j','4'],900,WORK/'compile.log')
unstripped=WORK/'android/lib/libsherpa-onnx-jni.so'
jni=WORK/'libsherpa-onnx-jni.so'
subprocess.run([str(NDK/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-unneeded','-o',str(jni),str(unstripped)],check=True,timeout=30)
assert jni.exists(), list((WORK/'android').rglob('libsherpa-onnx-jni.so'))
# Keep all original classes/resources. Target app supports arm64 only; preserve other
# original native entries as-is, replace only the selected ABI's JNI implementation.
out=WORK/'sherpa-onnx-role-profiler.aar'
with zipfile.ZipFile(stock) as inp, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as dest:
    for entry in inp.infolist():
        data=jni.read_bytes() if entry.filename=='jni/arm64-v8a/libsherpa-onnx-jni.so' else inp.read(entry.filename)
        dest.writestr(entry,data)
assert hashlib.sha256(ort).hexdigest() == '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1'
receipt={'schema':1,'sherpa_commit':COMMIT,'ort_version':'1.28.2','ort_source_commit':'33ca9628233dc8f002435e868d4c2e9f82766ca1','ndk':str(NDK),'cmake':str(CMAKE),'flags':flags,'configure_seconds':configure_seconds,'compile_seconds':compile_seconds,'artifacts':{str(p.relative_to(ROOT)):sha(p) for p in (stock,out,jni,WORK/'lib/libonnxruntime.so',WORK/'sherpa.tar.gz',WORK/'ort.zip')},'collector_sources':{p.name:sha(p) for p in HERE.iterdir() if p.is_file()},'patched_model_sources':{p.name:sha(p) for p in (source/'sherpa-onnx/csrc').glob('offline-*-model.cc') if 'dictation_profile::Run' in p.read_text()},'command':f'./gradlew -PnativeRoleProfilerAar={out} :app:assembleBenchmark :app:assembleBenchmarkAndroidTest'}
(WORK/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
