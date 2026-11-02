#!/usr/bin/env python3
"""Remove library-owned resources from :res166 so :app can depend on the real AARs again.

:res166 currently holds the APK's *merged* resource table, which already contains every
AppCompat/Material/etc. definition. Keeping both sides gives aapt2 duplicate `attr`
definitions and the link fails.

The strip set is not guessed from name prefixes: it is read out of the actual .aar files
Gradle resolved for debugRuntimeClasspath, so it covers transitive dependencies exactly.
The untouched bank stays in _raw/out_apktool_166/res.
"""
import os, re, json, shutil, zipfile, xml.etree.ElementTree as ET, collections

DEPS = "D:/linkup-rebuild/_raw/app_deps.txt"
CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
RES = "D:/linkup-rebuild/res166/src/main/res"
REPORT = "D:/linkup-rebuild/recovered/stripped_lib_resources.json"

GAV = re.compile(r"([A-Za-z0-9._-]+):([A-Za-z0-9._-]+):([0-9][A-Za-z0-9._-]*)")
DIR_TYPE = {"anim": "anim", "animator": "animator", "color": "color", "drawable": "drawable",
            "layout": "layout", "menu": "menu", "mipmap": "mipmap", "raw": "raw",
            "xml": "xml", "font": "font", "interpolator": "interpolator", "array": "array"}
TAG_TYPE = {"style": "style", "color": "color", "string": "string", "dimen": "dimen",
            "bool": "bool", "integer": "integer", "string-array": "array", "array": "array",
            "plurals": "plurals", "attr": "attr", "declare-styleable": "styleable",
            "item": None, "string": None}

for p in ("android", "aapt", "tools"):
    ET.register_namespace(p, {"android": "http://schemas.android.com/apk/res/android",
                              "aapt": "http://schemas.android.com/aapt",
                              "tools": "http://schemas.android.com/tools"}[p])

gavs = set()
text = open(DEPS, encoding="utf-8", errors="replace").read()
for line in text.split("\n"):
    if not line.startswith(("+---", "\\---", "     +---", "     \\---", "|    ", "    ")):
        continue
    m = GAV.search(line)
    if m:
        gavs.add(m.groups())

lib = set()
aar_found, aar_missing = [], []
for group, name, version in sorted(gavs):
    aar = None
    base = os.path.join(CACHE, group, name, version)
    if os.path.isdir(base):
        for d in os.listdir(base):
            cand = os.path.join(base, d, f"{name}-{version}.aar")
            if os.path.exists(cand):
                aar = cand
                break
    if aar is None:
        aar_missing.append(f"{group}:{name}:{version}")
        continue
    aar_found.append(f"{group}:{name}:{version}")
    with zipfile.ZipFile(aar) as z:
        for entry in z.namelist():
            if not entry.startswith("res/"):
                continue
            rel = entry[4:]
            parts = rel.split("/")
            if len(parts) < 2:
                continue
            cfg, fname = parts[0], parts[-1]
            typ = cfg.split("-")[0]
            if typ == "values":
                if fname.endswith(".xml") and len(parts) == 2:
                    try:
                        root = ET.fromstring(z.read(entry))
                    except ET.ParseError:
                        continue
                    for el in root:
                        tag = el.tag.split("}")[-1]
                        nm = el.get("{http://schemas.android.com/apk/res/android}name") or el.get("name")
                        if nm is None:
                            continue
                        if tag == "declare-styleable":
                            lib.add(("styleable", nm))
                            # AppCompat/Material declare most attrs *inside* a styleable; those
                            # nested definitions are what collide with the bank's copies.
                            for child in el:
                                if child.tag != "attr":
                                    continue
                                cname = child.get("name")
                                if cname and (len(child) or child.get("format")):
                                    lib.add(("attr", cname))
                            continue
                        t = el.get("type", "drawable") if tag == "item" else TAG_TYPE.get(tag, tag)
                        if t:
                            lib.add((t, nm))
                continue
            if typ not in DIR_TYPE:
                continue
            stem = os.path.splitext(fname)[0]
            if stem.endswith(".9"):
                stem = stem[:-2]
            lib.add((typ, stem))

print(f"resolved modules={len(gavs)} aars read={len(aar_found)} non-aar={len(aar_missing)}")
print(f"library resource identifiers: {len(lib)}")

removed_files = collections.Counter()
for d in os.listdir(RES):
    path = os.path.join(RES, d)
    if not os.path.isdir(path):
        continue
    typ = d.split("-")[0]
    if typ not in DIR_TYPE:
        continue
    for dirpath, _dirs, files in list(os.walk(path)):
        for f in files:
            stem = os.path.splitext(f)[0]
            if stem.endswith(".9"):
                stem = stem[:-2]
            if (typ, stem) in lib:
                os.remove(os.path.join(dirpath, f))
                removed_files[typ] += 1

removed_entries = collections.Counter()
for d in os.listdir(RES):
    if not d.startswith("values"):
        continue
    dirp = os.path.join(RES, d)
    for f in list(os.listdir(dirp)):
        p = os.path.join(dirp, f)
        try:
            tree = ET.parse(p)
        except ET.ParseError:
            continue
        root = tree.getroot()
        original = list(root)
        keep = []
        changed = False
        for el in original:
            tag = el.tag.split("}")[-1]
            nm = el.get("name")
            if nm is None:
                keep.append(el)
                continue
            t = el.get("type") if tag == "item" else TAG_TYPE.get(tag, tag)
            if t and (t, nm) in lib:
                removed_entries[t] += 1
                changed = True
                continue
            if tag == "declare-styleable":
                for child in list(el):
                    if child.tag != "attr":
                        continue
                    cname = child.get("name")
                    if cname and ("attr", cname) in lib and (len(child) or child.get("format")):
                        # keep it as a reference to the library's definition instead of a second definition
                        for gc in list(child):
                            child.remove(gc)
                        child.attrib.pop("format", None)
                        removed_entries["attr-nested"] += 1
                        changed = True
            keep.append(el)
        if changed:
            for el in list(root):
                root.remove(el)
            for el in keep:
                root.append(el)
            if keep:
                tree.write(p, encoding="utf-8", xml_declaration=True)
            else:
                os.remove(p)

report = {
    "resolved_modules": len(gavs),
    "aars_read": sorted(aar_found),
    "library_identifiers": len(lib),
    "removed_files": dict(removed_files),
    "removed_value_entries": dict(removed_entries),
    "totals": {"files": sum(removed_files.values()), "entries": sum(removed_entries.values())},
    "note": "原始未剥离资源树保留在 _raw/out_apktool_166/res，可整棵重放（tools/migrate_res.py）",
}
json.dump(report, open(REPORT, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(json.dumps(report["removed_files"], ensure_ascii=False))
print(json.dumps(report["removed_value_entries"], ensure_ascii=False))
print("totals:", report["totals"])
print("wrote", REPORT)
