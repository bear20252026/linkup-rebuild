#!/bin/bash
# Local decompile lane B: 1.6.6 -> smali + text layouts via apktool
set -u
export JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.8-hotspot"
RAW=/d/linkup-rebuild/_raw
LOG=$RAW/apktool166.log
: > "$LOG"
echo "START $(date -u +%FT%TZ)" >> "$LOG"
cd "$RAW" || exit 1
java -Xmx6g -jar apktool_3.0.3.jar d -f -s -o "$RAW/out_apktool_166" com.youloft.watcher1.6.6.apk >> "$LOG" 2>&1
echo "apktool_exit=$?" >> "$LOG"
L=$(find "$RAW/out_apktool_166/res/layout*" -name '*.xml' 2>/dev/null | wc -l)
echo "layout_xml=$L" >> "$LOG"
cp "$RAW/out_apktool_166/AndroidManifest.xml" "$RAW/manifest_166.xml" 2>/dev/null
echo "manifest_cp=$?" >> "$LOG"
echo "END $(date -u +%FT%TZ)" >> "$LOG"
