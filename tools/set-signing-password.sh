#!/usr/bin/env bash
# Store the release keystore password where the build reads it: local.properties
# (gitignored, mode 600). Run from anywhere; asks for the password without echo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="$ROOT/local.properties"
KEYSTORE="$HOME/.keys/pixel-dictation/release.jks"
[ -f "$KEYSTORE" ] || { echo "keystore not found at $KEYSTORE" >&2; exit 1; }

read -r -s -p "Keystore password: " PW; echo
read -r -s -p "Repeat: " PW2; echo
[ "$PW" = "$PW2" ] || { echo "passwords differ" >&2; exit 1; }
[ -n "$PW" ] || { echo "empty password" >&2; exit 1; }

# keep any unrelated lines (e.g. sdk.dir), replace ours
tmp="$(mktemp)"
[ -f "$FILE" ] && grep -vE '^dictation\.' "$FILE" > "$tmp" || true
{
  cat "$tmp"
  echo "dictation.keystore=$KEYSTORE"
  echo "dictation.keyAlias=dictation"
  echo "dictation.keystorePassword=$PW"
} > "$FILE"
rm -f "$tmp"
chmod 600 "$FILE"
echo "written: $FILE (mode 600, ignored by git)"
