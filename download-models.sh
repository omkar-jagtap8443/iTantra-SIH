#!/usr/bin/env bash
set -euo pipefail

DEST="app/src/main/assets"
mkdir -p "$DEST"

MODELS=(
  "vosk-model-small-en-in-0.4"
  "vosk-model-small-gu-0.42"
  "vosk-model-small-hi-0.22"
  "vosk-model-small-te-0.42"
)

for m in "${MODELS[@]}"; do
  if [ -d "$DEST/$m" ]; then
    echo "OK $m already present, skipping"
    continue
  fi
  url="https://alphacephei.com/vosk/models/$m.zip"
  tmp="$(mktemp -t "$m.XXXXXX.zip")"
  echo "Downloading $m ..."
  curl -L -o "$tmp" "$url"
  echo "Extracting $m ..."
  unzip -q "$tmp" -d "$DEST"
  rm -f "$tmp"
  echo "OK $m done"
done

echo
echo "All Vosk models installed in $DEST"