#!/usr/bin/env python3
"""Emit the rebuilt Retrofit interface (net/.../LinkUpApi.kt) plus a readable
contract spec (recovered/api_166_contract.md) from the recovered route table.

Method names are derived from the path because R8 flattened the original Kotlin names
to A/B0/C...; the original names stay in routes_166.json for cross-checking.
"""
import json, os, re, collections

ROOT = "D:/linkup-rebuild"
rows = json.load(open(f"{ROOT}/recovered/routes_166.json", encoding="utf-8"))
KT_OUT = f"{ROOT}/net/src/main/kotlin/com/youloft/linkup/net/LinkUpApi.kt"
MD_OUT = f"{ROOT}/recovered/api_166_contract.md"

TYPE_MAP = {"long": "Long", "int": "Int", "short": "Int", "boolean": "Boolean",
            "double": "Double", "float": "Float", "String": "String", "int i10": "Int"}

def kotlin_type(decl):
    head = decl.rsplit(" ", 1)[0].strip() if re.search(r"\s\w+$", decl) else decl
    return TYPE_MAP.get(head, head if head else "Any")

def name_for(path, used):
    slug = "".join(p.capitalize() for p in re.split(r"[_/]+", path.removeprefix("api/")) if p)
    n = re.sub(r"[^A-Za-z0-9]", "", slug)
    n = n[0].lower() + n[1:]
    base, i = n, 2
    while n in used:
        n = f"{base}{i}"
        i += 1
    used.add(n)
    return n

def query_arg_name(raw, idx):
    n = raw.strip()
    if re.match(r"^[a-z][A-Za-z0-9]*$", n):
        return n
    if re.match(r"^[A-Z][A-Za-z0-9]*$", n):
        return n[0].lower() + n[1:]
    parts = [p for p in re.split(r"[^A-Za-z0-9]", n) if p]
    name = parts[0].lower() + "".join(p.capitalize() for p in parts[1:]) if parts else ""
    if not re.match(r"^[a-z][A-Za-z0-9]*$", name or ""):
        name = f"q{idx}"
    return name

used, decls, spec = set(), [], []
for r in rows:
    fn = name_for(r["path"], used)
    args, md_args = [], []
    for i, p in enumerate(r["params"]):
        if p["ann"] == "Query":
            t = kotlin_type(p["type"])
            args.append(f'@Query("{p["arg"]}") {query_arg_name(p["arg"], i)}: {t}')
            md_args.append(f'query {p["arg"]}: {t}')
        elif p["ann"] == "Body":
            args.append("@Body payload: Map<String, Any?>")
            md_args.append("body: JSON map")
        else:
            md_args.append(f'{p["ann"]}({p["arg"]}) {p["type"]}')
    decls.append(f'    @{r["verb"]}("{r["path"]}")\n'
                 f'    suspend fun {fn}({", ".join(args)}): ApiResponse<JsonElement>')
    spec.append((r["verb"], r["path"], fn, r["response"], "; ".join(md_args) or "-", r["method"]))

imports = {"GET": "retrofit2.http.GET", "POST": "retrofit2.http.POST",
           "PUT": "retrofit2.http.PUT", "DELETE": "retrofit2.http.DELETE",
           "PATCH": "retrofit2.http.PATCH"}
used_anns = set()
for r in rows:
    used_anns.add(r["verb"])
for r in rows:
    for p in r["params"]:
        if p["ann"] in ("Query", "Body"):
            used_anns.add(p["ann"])
imports.update({"Query": "retrofit2.http.Query", "Body": "retrofit2.http.Body"})
needed = sorted({imports[a] for a in used_anns if a in imports})

header = "package com.youloft.linkup.net\n\nimport com.google.gson.JsonElement\n"
header += "".join(f"import {i}\n" for i in needed) + "\n"
os.makedirs(os.path.dirname(KT_OUT), exist_ok=True)
open(KT_OUT, "w", encoding="utf-8").write(header + "interface LinkUpApi {\n\n" + "\n\n".join(decls) + "\n}\n")

with open(MD_OUT, "w", encoding="utf-8") as f:
    f.write("# LinkUp 1.6.6 API contract (recovered)\n\n")
    f.write(f"97 routes recovered from `gh/a.java` (R8-renamed Retrofit interface). ")
    f.write("`Signature` = hex(AES-128-CBC(PKCS7)) of `path?query`, see net/SignatureInterceptor.kt.\n\n")
    f.write("| verb | path | rebuilt method | original bean | params | dex name |\n")
    f.write("|---|---|---|---|---|---|\n")
    for verb, path, fn, resp, params, dex in spec:
        f.write(f"| {verb} | `{path}` | `{fn}` | `{resp}` | {params} | `{dex}` |\n")

print("methods:", len(decls))
print("verbs:", collections.Counter(s[0] for s in spec))
print("wrote", KT_OUT)
print("wrote", MD_OUT)
