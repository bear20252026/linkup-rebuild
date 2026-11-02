#!/usr/bin/env python3
"""List migrated resource files whose stem is not a legal Android resource name,
and count how often those names are referenced elsewhere in the tree."""
import os, re, json, collections

ROOT = "D:/linkup-rebuild/res166/src/main/res"
VALID = re.compile(r"^[a-z0-9_]+$")

bad = {}
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        stem = os.path.splitext(f)[0]
        if stem.endswith(".9"):          # 9-patch marker is part of a legal name
            stem = stem[:-2]
        if not VALID.match(stem):
            rel = os.path.relpath(os.path.join(dirpath, f), ROOT).replace("\\", "/")
            bad[rel] = stem

by_dir = collections.Counter(r.split("/")[0] for r in bad)
print("invalid-name files:", len(bad))
print("by dir:", dict(by_dir))
for r in list(bad)[:8]:
    print("  ", r)

# how many xml references point at these stems
hit = collections.Counter()
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".xml"):
            continue
        text = open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
        for rel, stem in bad.items():
            if stem in text:
                hit[rel] += 1

referenced = sum(1 for v in hit.values() if v)
print(f"of those, referenced somewhere in the tree: {referenced}")
json.dump({"invalid": sorted(bad), "referenced": sorted(hit)},
          open("D:/linkup-rebuild/_raw/invalid_res_names.json", "w", encoding="utf-8"), indent=1)
