#!/usr/bin/env bash
# Download the files pinned in assets.lock into models/ (gitignored) and verify them.
# Laptop only. Afterwards the AAR is copied to app/libs/ and the model archive unpacked.
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
echo "done: $DEST"
