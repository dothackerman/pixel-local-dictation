#!/usr/bin/env python3
"""Build the tensor binding against the installed sherpa wheel's exact ORT core.

Usage: python build.py --headers PINNED_ORT_HEADERS --eigen EIGEN_SOURCE --output BUILD_DIRECTORY
Requires pybind11 and sherpa-onnx in the executing Python environment.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sysconfig
import pybind11
import sherpa_onnx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--headers', type=Path, required=True)
    parser.add_argument('--eigen', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--python-headers', type=Path, default=Path(sysconfig.get_paths()['include']))
    args = parser.parse_args()
    core = Path(sherpa_onnx.__file__).parent / 'lib/libonnxruntime.so'
    source = Path(__file__).with_name('runtime.cc')
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / ('_tdt_ort' + sysconfig.get_config_var('EXT_SUFFIX'))
    command = ['c++', '-O2', '-shared', '-std=c++17', '-fPIC', str(source),
               '-I'+str(args.eigen), '-I'+str(args.headers), '-I'+pybind11.get_include(),
               '-I'+str(args.python_headers), str(core),
               '-Wl,-rpath,'+str(core.parent), '-o', str(target)]
    subprocess.run(command, check=True)
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
    receipt = {'sherpa_version': sherpa_onnx.__version__,
               'ort_version': sherpa_onnx.onnxruntime_version,
               'core': str(core), 'core_sha256': sha(core),
               'source_sha256': sha(source), 'binding_sha256': sha(target),
               'headers': {p.name: sha(p) for p in sorted(args.headers.glob('*.h'))},
               'eigen_core_sha256': sha(args.eigen/'Eigen/Core'),
               'command': command}
    (args.output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(target)


if __name__ == '__main__':
    main()
