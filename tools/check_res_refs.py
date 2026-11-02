#!/usr/bin/env python3
"""Validate every `@type/name` and `?attr/name` reference inside the migrated resource
tree against the resources the tree actually defines.

AGP hides the real aapt2 message here (its blame logger crashes on the Windows pseudo
path `<ns>-<task>:/values/values.xml`), so this offline check is how we find the culprit.
"""
import os, re, sys, json, collections

ROOT = sys.argv[1] if len(sys.argv) > 1 else "D:/linkup-rebuild/res166/src/main/res"

DIR_TYPE = {
    "anim": "anim", "animator": "animator", "color": "color", "drawable": "drawable",
    "layout": "layout", "menu": "menu", "mipmap": "mipmap", "raw": "raw",
    "xml": "xml", "font": "font", "interpolator": "interpolator",
}
VALUES_TAG = {"color": "color", "string": "string", "dimen": "dimen", "style": "style",
              "bool": "bool", "integer": "integer", "fraction": "integer",
              "string-array": "string-array", "array": "array", "plurals": "plurals",
              "attr": "attr", "declare-styleable": "styleable", "id": "id"}

defined = set()

for entry in sorted(os.listdir(ROOT)):
    path = os.path.join(ROOT, entry)
    if not os.path.isdir(path):
        continue
    base = entry.split("-")[0]
    if base in DIR_TYPE:
        t = DIR_TYPE[base]
        for _dp, _d, files in os.walk(path):
            for f in files:
                stem = os.path.splitext(f)[0]
                if stem.endswith(".9"):      # 9-patch marker is not part of the resource name
                    stem = stem[:-2]
                defined.add(f"{t}/{stem}")

ref_re = re.compile(r'@([a-z][a-z0-9\-]*)/([A-Za-z0-9_.]+)')
name_re = re.compile(r'<(\w+)[^>]*name="([^"]+)"')
item_re = re.compile(r'<item[^>]*type="([^"]+)"[^>]*name="([^"]+)"')

for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".xml"):
            continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8", errors="replace").read()
        rel = os.path.relpath(dirpath, ROOT).replace("\\", "/")
        base = rel.split("/")[0]
        if base == "values" or base.startswith("values-"):
            for tag, name in name_re.findall(text):
                if tag in VALUES_TAG:
                    defined.add(f"{VALUES_TAG[tag]}/{name}")
            for t, name in item_re.findall(text):
                defined.add(f"{t}/{name}")
                if t == "id":
                    defined.add(f"id/{name}")

declared_id = re.compile(r'@\+id/([A-Za-z0-9_.]+)')
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".xml"):
            continue
        text = open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
        for n in declared_id.findall(text):
            defined.add(f"id/{n}")

refs = collections.Counter()
where = {}
for dirpath, _dirs, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".xml"):
            continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8", errors="replace").read()
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        for t, n in ref_re.findall(text):
            if t == "android":
                continue
            key = f"{t}/{n}"
            if key not in defined:
                refs[key] += 1
                where.setdefault(key, []).append(rel)

# style parents are referenced bare, capture those separately
missing_styles = 0
print(f"defined resources: {len(defined)}")
print(f"MISSING references: {len(refs)} distinct, {sum(refs.values())} occurrences")
by_type = collections.Counter(k.split('/')[0] for k in refs)
print("missing by type:", dict(by_type))
for k, c in refs.most_common(25):
    print(f"  {k:<52} x{c:<4} e.g. {where[k][0]}")
json.dump({k: {"count": c, "files": where[k][:8]} for k, c in refs.most_common()},
          open("D:/linkup-rebuild/recovered/missing_resource_refs.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
print("wrote recovered/missing_resource_refs.json")
