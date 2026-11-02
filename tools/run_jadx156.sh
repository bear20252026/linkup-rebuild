#!/bin/bash
# Local decompile lane A: 1.5.6 -> readable Java (names not obfuscated in this build)
set -u
export JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.8-hotspot"
RAW=/d/linkup-rebuild/_raw
OUT=$RAW/out_jadx_156
LOG=$RAW/jadx156.log
mkdir -p "$OUT"
: > "$LOG"
echo "START $(date -u +%FT%TZ)" >> "$LOG"
cd "$RAW" || exit 1
./../tools/jadx/bin/jadx \
  -d "$OUT" \
  --no-res --show-bad-code --no-debug-info -j 4 \
  com.youloft.watcher1.5.6.rebuilt.apk >> "$LOG" 2>&1
RC=$?
echo "jadx_exit=$RC" >> "$LOG"
N=$(find "$OUT" -name '*.java' 2>/dev/null | wc -l)
echo "java_files=$N" >> "$LOG"
Y=$(find "$OUT/sources/com/youloft" -name '*.java' 2>/dev/null | wc -l)
echo "youloft_java_files=$Y" >> "$LOG"
echo "END $(date -u +%FT%TZ)" >> "$LOG"
