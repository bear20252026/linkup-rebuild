#!/usr/bin/env python3
"""Inventory the recovered sources so the rebuild backlog is based on counted facts,
not on the 3929-class headline number.

1.5.6 (out_jadx_156) keeps readable business names -> it is the behavioural reference.
1.6.6 (src166) is R8-flattened -> it is the protocol/asset reference.
"""
import os, re, json, collections

R156 = "D:/linkup-rebuild/_raw/out_jadx_156/sources/com/youloft/watcher"
R166 = "D:/linkup-rebuild/_raw/src166/sources"
OUT = "D:/linkup-rebuild/recovered/module_inventory.md"

OBSC = re.compile(r"^[A-Z][0-9]?$|^[a-z][0-9]{0,2}$")

def leaf_obfuscated(path):
    name = os.path.basename(path)[:-5]
    return bool(re.match(r"^[a-z][a-z0-9]{0,2}$", name))

def scan(root):
    stats = collections.Counter()
    bytes_ = collections.Counter()
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(dirpath, root).replace("\\", "/")
            top = rel.split("/")[0] if rel != "." else "(root)"
            stats[top] += 1
            try:
                bytes_[top] += os.path.getsize(full)
            except OSError:
                pass
    return stats, bytes_

rows = []
if os.path.isdir(R156):
    st, by = scan(R156)
    for pkg, n in st.most_common():
        rows.append((pkg, n, by[pkg]))

c166_total = sum(1 for _ in os.walk(R166) for f in _[2] if f.endswith(".java"))
obsc_166 = 0
youloft_166 = 0
for dirpath, _d, files in os.walk(R166):
    for f in files:
        if not f.endswith(".java"):
            continue
        p = os.path.join(dirpath, f)
        if os.sep + "com" + os.sep + "youloft" + os.sep in p:
            youloft_166 += 1
        if leaf_obfuscated(p):
            obsc_166 += 1

lines = ["# Recovered source inventory", ""]
lines.append(f"- 1.5.6 readable business files under `com/youloft/watcher`: **{sum(r[1] for r in rows)}**")
lines.append(f"- 1.6.6 java files total: **{c166_total}**, of which `com/youloft/**`: **{youloft_166}**")
lines.append(f"- 1.6.6 files whose class name is a 1-3 letter R8 stub: **{obsc_166}** ({obsc_166*100//max(c166_total,1)}%)")
lines.append("")
lines.append("## 1.5.6 business modules (behavioural reference for the rewrite)")
lines.append("")
lines.append("| package | files | KB |")
lines.append("|---|---|---|")
for pkg, n, b in rows:
    lines.append(f"| `{pkg}` | {n} | {b//1024} |")

pages = os.path.join(R156, "pages")
if os.path.isdir(pages):
    sub = collections.Counter()
    for d in sorted(os.listdir(pages)):
        p = os.path.join(pages, d)
        if os.path.isdir(p):
            sub[d] = sum(1 for _rp, _r, fs in os.walk(p) for f in fs if f.endswith(".java"))
    lines.append("")
    lines.append("## pages/ breakdown")
    lines.append("")
    lines.append("| feature | files |")
    lines.append("|---|---|")
    for k, v in sub.most_common():
        lines.append(f"| `{k}` | {v} |")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines[:12]))
print("wrote", OUT)
