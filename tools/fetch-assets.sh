#!/usr/bin/env bash
# Download the files pinned in assets.lock into models/ (gitignored) and verify them.
# Laptop only. Afterwards the AAR is copied to app/libs/, the published model archive unpacked for reference, and the built model linked into the app assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/models"
mkdir -p "$DEST" "$ROOT/app/libs"

grep -v '^#' "$ROOT/assets.lock" | while read -r sha bytes file url; do
  [ -n "$sha" ] || continue
  out="$DEST/$file"
  if [ ! -f "$out" ] || [ "$(sha256sum "$out" | cut -d' ' -f1)" != "$sha" ]; then
    echo "fetching $file"
    curl --fail --location --proto '=https' --tlsv1.2 --output "$out.part" "$url"
    mv "$out.part" "$out"
  fi
  got="$(sha256sum "$out" | cut -d' ' -f1)"
  size="$(stat -c %s "$out")"
  [ "$got" = "$sha" ] && [ "$size" = "$bytes" ] || { echo "MISMATCH $file: sha256 $got, $size bytes" >&2; exit 1; }
  echo "ok $file"
done

cp "$DEST"/sherpa-onnx-*.aar "$ROOT/app/libs/"
MODEL="sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
[ -d "$DEST/$MODEL" ] || tar -xjf "$DEST/$MODEL.tar.bz2" -C "$DEST"
# The model the app ships (ADR-003): MatMul-only INT8 encoder with fp32 decoder and joiner,
# built on this laptop by tools/export-fp32.sh and tools/quant_variant.py. Hard links, not
# copies, into the app's assets (gitignored).
BUILT="$DEST/parakeet-v3-matmul8"
WANT="$(grep '^#built ' "$ROOT/assets.lock" | cut -d' ' -f2)"
[ -f "$BUILT/encoder.int8.onnx" ] || { echo "missing $BUILT: run tools/export-fp32.sh, then
  models/export/venv/bin/python tools/quant_variant.py models/parakeet-v3-fp32 $BUILT MatMul
  and link decoder.onnx, joiner.onnx, tokens.txt from models/parakeet-v3-fp32 into it" >&2; exit 1; }
[ "$(sha256sum "$BUILT/encoder.int8.onnx" | cut -d' ' -f1)" = "$WANT" ] || { echo "MISMATCH built encoder, expected $WANT" >&2; exit 1; }
ASSETS="$ROOT/app/src/main/assets/parakeet-v3"
mkdir -p "$ASSETS"
for f in encoder.int8.onnx decoder.onnx joiner.onnx tokens.txt; do ln -f "$BUILT/$f" "$ASSETS/$f"; done
ln -f "$DEST/silero_vad.onnx" "$ASSETS/silero_vad.onnx"
echo "done: $DEST, $ASSETS"
