#!/usr/bin/env python3
"""Strip the `$` that apktool injects into animated-vector frame names.

The APK stores these as `@drawable/$avd_hide_password__0`; aapt2 rejects `$` in a
file-based resource name, so rename the file and rewrite every reference in the same pass.
"""
import os, re, json

ROOT = "D:/linkup-rebuild/res166/src/main/res"

renames = {}
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if "$" not in f:
            continue
        stem, ext = os.path.splitext(f)
        new_stem = stem.replace("$", "")
        src = os.path.join(dirpath, f)
        dst = os.path.join(dirpath, new_stem + ext)
        if os.path.exists(dst):
            print("SKIP collision:", f)
            continue
        os.rename(src, dst)
        renames[stem] = new_stem

print("renamed:", len(renames))

rewritten = 0
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".xml"):
            continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        if "$" not in text:
            continue
        new = text
        for old, repl in renames.items():
            new = new.replace(old, repl)
        if new != text:
            open(p, "w", encoding="utf-8", newline="").write(new)
            rewritten += 1

print("xml files rewritten:", rewritten)
json.dump(renames, open("D:/linkup-rebuild/_raw/res_renames.json", "w", encoding="utf-8"), indent=1)
