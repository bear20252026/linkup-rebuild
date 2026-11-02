#!/usr/bin/env python3
"""Recover the 1.6.6 route table from the R8-renamed Retrofit interface (gh/a.java).

R8 moved retrofit2.http.* into hm.*, so @GET/@POST show up as @hm.f/@hm.o etc.
We identify which letter is which verb empirically: a path we already verified as
GET (api/client/config) tells us the GET marker; form/body-carrying paths tell us POST.
"""
import re, sys, json, collections

SRC = "D:/linkup-rebuild/_raw/src166/sources/gh/a.java"
text = open(SRC, encoding="utf-8", errors="replace").read()

# each interface method: annotation lines then "Object <Name>(<params>, kotlin.coroutines.d<? super RESPONSE> dVar);"
method_re = re.compile(
    r'((?:\s*@[A-Za-z0-9_.]+(?:\([^)]*\))?\n)+)\s*Object\s+(\w+)\(([^;]*)\);')

rows = []
for m in method_re.finditer(text):
    anns_blob, name, params = m.group(1), m.group(2), m.group(3)
    path_anns = re.findall(r'@(\w+(?:\.\w+)*)\("([^"]*)"\)', anns_blob)
    http_anns = [(a, p) for a, p in path_anns if a in ("f", "o", "t", "y", "hm.f", "hm.o") or p.startswith(("api/", "http", "/"))]
    # param-level annotations
    panns = re.findall(r'@(\w+(?:\.\w+)*)\("([^"]*)"\)', params)
    resp = re.search(r'ApiResponse<([^>]*(?:>[^>]*)?)>', params)
    rows.append({
        "marker": http_anns[0][0] if http_anns else "",
        "path": http_anns[0][1] if http_anns else "",
        "all_anns": path_anns,
        "method": name,
        "param_anns": panns,
        "params": re.sub(r'\s+', ' ', params.strip()),
        "response": resp.group(1) if resp else "",
    })

marks = collections.Counter(r["marker"] for r in rows)
print("methods parsed:", len(rows))
print("marker histogram:", dict(marks))
known_get = {"api/client/config", "api/user/info", "api/vip/index", "api/version/check"}
for mk in marks:
    hits = [r["path"] for r in rows if r["marker"] == mk][:6]
    print(f"  marker {mk!r}: {hits}")
json.dump(rows, open("D:/linkup-rebuild/_raw/routes_166.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
print("wrote routes_166.json")
