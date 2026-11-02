#!/usr/bin/env python3
"""Copy the recovered 1.6.6 resource tree into the :res166 module verbatim.

Deliberately edited-free so every layout/strings entry stays a faithful reference.
Only two exclusions: values/public.xml (pins aapt ids and would fail on resources we
do not migrate), and pure-language values-* folders, which are AppCompat/Material
translations that the real AARs already supply.
"""
import os, re, shutil, json

SRC = "D:/linkup-rebuild/_raw/out_apktool_166/res"
DST = "D:/linkup-rebuild/res166/src/main/res"

LOCALE = re.compile(
    r"^values-(?:"
    r"af|am|ar(?:-r[A-Z]{2})?|as(?:-r[A-Z]{2})?|az|b\+.*|[a-z]{2}(?:-r[A-Z]{2})?|"
    r"fi|fr|ga|gl|gu|hi|hr|hu|hy|in|is|it|iw|ja|ka|kk|km|kn|ko|ky|lo|lt|lv|mai|mi|mk|"
    r"ml|mn|mr|ms|my|nb|ne|nl|pa|pl|pt|ro|ru|si|sk|sl|sq|sr|sv|sw|ta|te|th|tl|tr|ug|uk|ur|uz|vi|zu"
    r")(?:-.*)?$"
)
KEEP_QUALIFIER = re.compile(
    r"^values-(?:night|land|port|large|small|xlarge|watch|ldrtl|hdpi|mdpi|xhdpi|sw\d+dp|h\d+dp|w\d+dp|v\d+)"
)
# Chinese is the product's own copy; every other pure-language folder is an AppCompat/Material translation.
FORCE_KEEP = {"values", "values-zh", "values-zh-rCN", "values-zh-rTW", "values-zh-rHK"}

os.makedirs(DST, exist_ok=True)
copied_files = 0
copied_bytes = 0
skipped = []

for entry in sorted(os.listdir(SRC)):
    src_path = os.path.join(SRC, entry)
    if not os.path.isdir(src_path):
        continue
    if entry not in FORCE_KEEP and LOCALE.match(entry) and not KEEP_QUALIFIER.match(entry):
        skipped.append(entry)
        continue
    for dirpath, _dirs, files in os.walk(src_path):
        for f in files:
            if f == "public.xml":
                skipped.append(f"{entry}/{f}")
                continue
            s = os.path.join(dirpath, f)
            rel = os.path.relpath(s, SRC).replace("\\", "/")
            d = os.path.join(DST, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
            copied_files += 1
            copied_bytes += os.path.getsize(s)

layouts = len(os.listdir(os.path.join(DST, "layout")))
strings = sum(1 for _ in open(os.path.join(DST, "values", "strings.xml"), encoding="utf-8", errors="replace") if "<string " in _ )
print(f"copied files={copied_files} bytes={copied_bytes/1024/1024:.1f}MB")
print(f"layout={layouts} strings={strings}")
print(f"skipped entries={len(skipped)}")
open("D:/linkup-rebuild/recovered/res_migration_manifest.json", "w", encoding="utf-8").write(
    json.dumps({"copied_files": copied_files, "copied_mb": round(copied_bytes/1048576, 1),
                "layouts": layouts, "strings": strings, "skipped": skipped}, ensure_ascii=False, indent=1))
