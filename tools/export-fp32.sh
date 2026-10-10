#!/usr/bin/env bash
# Laptop only: export Parakeet TDT 0.6B v3 to fp32 ONNX with sherpa-onnx's own script
# (tag v1.13.8), for the INT8 parity check in docs/plan.md. Needs about 12 GB of disk
# and downloads the official 2.5 GB checkpoint. Result: models/parakeet-v3-fp32/.
# Pins that upstream leaves open: torch 2.7.1 (newer torch switches to an exporter the
# script was not written for), pyarrow<18 (newer needs NumPy 2, upstream pins NumPy 1),
# and a uv-managed Python (the system one lacks headers for one NeMo dependency).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$ROOT/models/export"
RAW="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/scripts/nemo"
mkdir -p "$W/parakeet-tdt-0.6b-v3" && cd "$W"

fetch() {  # path sha256 url
  [ -f "$1" ] || curl --fail --location --silent --output "$1" "$3"
  echo "$2  $1" | sha256sum --check --quiet
}
fetch parakeet-tdt-0.6b-v3/export_onnx.py ec6194e90bd202b3ed8c47a02794a8fb80530f374f611ca4115f0be102321e40 "$RAW/parakeet-tdt-0.6b-v3/export_onnx.py"
fetch generate_bpe_vocab.py d898c4261b480fa189a6d5375fb308703dc6fd7a82f805fbcf6f01ede91fe73c "$RAW/generate_bpe_vocab.py"
fetch parakeet-tdt-0.6b-v3/parakeet-tdt-0.6b-v3.nemo 3cbdc85877e668ca7b82d0d56770eb1fac76691f55d6b97545e8d61ca588d10d \
  https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/main/parakeet-tdt-0.6b-v3.nemo

[ -d venv ] || uv venv -q venv --python 3.12 --python-preference only-managed
uv pip install -q --python venv/bin/python --extra-index-url https://download.pytorch.org/whl/cpu \
  torch==2.7.1 torchaudio==2.7.1 "nemo_toolkit[asr]==2.5.0" "numpy<2" kaldi-native-fbank librosa \
  onnx==1.17.0 onnxruntime==1.17.1 soundfile "pyarrow<18"
uv pip freeze --python venv/bin/python > freeze.txt

(cd parakeet-tdt-0.6b-v3 && ../venv/bin/python export_onnx.py)
OUT="$ROOT/models/parakeet-v3-fp32"
mkdir -p "$OUT"
for f in encoder.onnx encoder.weights decoder.onnx joiner.onnx tokens.txt; do ln -f "parakeet-tdt-0.6b-v3/$f" "$OUT/$f"; done
echo "done: $OUT (full package list in $W/freeze.txt)"
