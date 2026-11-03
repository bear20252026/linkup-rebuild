#!/bin/bash
# assets/ and the decompiled references are kept out of git (size + they are derivable from the APK).
# Re-run the local lanes to recreate them, then copy assets into :app.
set -eu
RAW="$(cd "$(dirname "$0")/.." && pwd)/_raw"
mkdir -p "$RAW"
echo "1) reassemble the two APKs from Release v1.0 parts, then verify:"
echo "     1.5.6 -> 8d8e19b9f7435e2f5b33355c9cc897d4"
echo "     1.6.6 -> 5ddd548a8b01b3b85fa1f9153c75d393"
echo "2) sh tools/run_apktool166.sh        # layouts + assets"
echo "3) sh tools/run_jadx156.sh           # readable 1.5.6 business java"
echo "4) cp -r $RAW/out_apktool_166/assets/. app/src/main/assets/"
