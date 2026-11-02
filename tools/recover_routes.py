#!/usr/bin/env python3
"""Recover the LinkUp 1.6.6 route table from the R8-renamed Retrofit interface.

R8 repackaged retrofit2.http.* into hm.*; retrofit2/w.java (the ServiceMethod parser)
proves the letter -> verb mapping, hard-coded below.
"""
import re, json, os, collections

SRC = "D:/linkup-rebuild/_raw/src166/sources/gh/a.java"
OUT = "D:/linkup-rebuild/recovered/routes_166.json"

METHOD_ANN = {"f": "GET", "o": "POST", "p": "PUT", "b": "DELETE", "n": "PATCH",
              "m": "OPTIONS", "g": "HTTP", "k": "FormUrlEncoded", "l": "Streaming",
              "e": "Multipart", "h": "Headers"}
# retrofit2/http/* survived R8 only as hm/{a,b,c,...}.java. The letter->name mapping below
# is read off retrofit2/w.java, whose error strings still say "@QueryMap", "@Body", etc.
PARAM_ANN = {"t": "Query", "v": "QueryName", "u": "QueryMap", "a": "Body", "y": "Url",
             "s": "Path", "i": "Header", "j": "HeaderMap", "c": "Field", "d": "FieldMap",
             "r": "PartMap", "x": "Part", "q": "Part", "m": "Nullable", "l": "Streaming"}

# Kotlin nullability markers (vl.l / vl.m) decorate every param; they are not Retrofit annotations
text = re.sub(r'@(?:vl\.)?(?:l|m)(?=\s)', '', open(SRC, encoding="utf-8", errors="replace").read())
method_re = re.compile(r'((?:\s*@\w+(?:\([^)]*\))?\s*\n)+)\s*Object\s+(\w+)\(([^;]*)\);')

rows = []
for anns_blob, name, params in method_re.findall(text):
    anns = [(a, v.strip('"')) for a, v in re.findall(r'@(?:\w+\.)*(\w+)(?:\(([^)]*)\))?', anns_blob)]
    verb = path = None
    flags = []
    for a, val in anns:
        if a in ("f", "o", "p", "b", "n", "m", "g") and path is None and val:
            verb, path = METHOD_ANN[a], val
        elif a in ("k", "l", "e", "h"):
            flags.append(METHOD_ANN[a])
    resp = re.search(r'ApiResponse<(.+?)>\s+dVar', params)
    rtype = resp.group(1) if resp else ""
    while rtype.endswith(">") and rtype.count("<") < rtype.count(">"):
        rtype = rtype[:-1]
    arg_list = [p.strip() for p in re.split(r',(?![^<]*>)', params) if 'dVar' not in p]
    plist = []
    for arg in arg_list:
        m = re.match(r'@(?:\w+\.)*(\w+)(?:\(([^)]*)\))?\s*(.*)$', arg)
        if m:
            plist.append({"ann": PARAM_ANN.get(m.group(1), m.group(1)),
                          "arg": (m.group(2) or "").strip('"'),
                          "type": re.sub(r'@vl\.\w+\s*|@l\s*', '', m.group(3)).strip()})
        else:
            plist.append({"ann": "", "arg": "", "type": arg})
    rows.append({"verb": verb or "?", "path": path or "", "method": name,
                 "flags": flags, "response": rtype,
                 "params": plist, "raw": re.sub(r'\s+', ' ', params.strip())})

rows = [r for r in rows if r["path"]]
print("routes:", len(rows), "| verbs:", dict(collections.Counter(r["verb"] for r in rows)))
dups = {p: c for p, c in collections.Counter(r["path"] for r in rows).items() if c > 1}
print("dup paths:", dups)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(rows, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for r in rows[:10]:
    print(f'  {r["verb"]:<6} {r["path"]:<42} -> {r["response"]}')
print("wrote", OUT)
