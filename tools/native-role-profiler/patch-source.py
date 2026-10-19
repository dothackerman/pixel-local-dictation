#!/usr/bin/env python3
"""Apply diagnostic collector to exact pinned Sherpa source. Refuses repeat/unknown sites."""
import pathlib, re, shutil, sys
root = pathlib.Path(sys.argv[1]).resolve()
ours = pathlib.Path(__file__).resolve().parent
for name in ('role-profiler.h','role-profiler.cc','role-profiler-jni.cc'):
    shutil.copy2(ours/name, root/'sherpa-onnx/csrc'/name)
for name, mapping, expected in [
    ('offline-transducer-nemo-model.cc', {'encoder':0,'decoder':1,'joiner':2}, 3),
    ('offline-cohere-transcribe-model.cc', {'encoder':3,'decoder':4}, 4),
    ('offline-whisper-model.cc', {'encoder':5,'decoder':6}, 4),
]:
    path = root/'sherpa-onnx/csrc'/name
    text = path.read_text()
    assert 'dictation_profile' not in text, 'patch already applied'
    text = '#include "sherpa-onnx/csrc/role-profiler.h"\n' + text
    fields = ''.join(f'  const uint64_t profile_{role}_id_ = dictation_profile::RegisterSession({value});\n' for role,value in mapping.items())
    text = text.replace(' private:\n', ' private:\n'+fields, 1)
    matches = list(re.finditer(r'(encoder|decoder|joiner)_sess_->Run\(', text))
    assert len(matches) == expected, (name,len(matches))
    for match in reversed(matches):
        role = match[1]
        start = match.start()
        end = text.index(';', match.end())
        expr = text[start:end]
        text = text[:start] + f'dictation_profile::Run({mapping[role]}, profile_{role}_id_, [&]() {{ return {expr}; }})' + text[end:]
    path.write_text(text)
p = root/'sherpa-onnx/jni/CMakeLists.txt'
s=p.read_text().replace('  audio-tagging.cc','  ${PROJECT_SOURCE_DIR}/sherpa-onnx/csrc/role-profiler.cc\n  ${PROJECT_SOURCE_DIR}/sherpa-onnx/csrc/role-profiler-jni.cc\n  audio-tagging.cc',1)
p.write_text(s)
p=root/'sherpa-onnx/jni/sherpa-onnx-symbols.lds'
p.write_text(p.read_text().replace('  global:', '  global:\n    Java_ch_og_dictation_NativeRoleProfiler*;',1))
