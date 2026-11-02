#!/usr/bin/env python3
"""Turn the recovered 1.6.6 beans into Kotlin data classes and re-emit LinkUpApi with typed
responses instead of the JsonElement placeholders.

Why field names are trustworthy: neither 1.5.6 nor 1.6.6 keeps @SerializedName (R8 strips the
annotation), so Gson must be reading plain field names -- which is exactly why R8 was told to keep
them. `ChannelConfig`'s fields matched the live production keys one for one, which is the check
this generator relies on.
"""
import os, re, json, collections

SRC = "D:/linkup-rebuild/_raw/src166/sources"
ROUTES = "D:/linkup-rebuild/recovered/routes_166.json"
DTO_OUT = "D:/linkup-rebuild/net/src/main/kotlin/com/youloft/linkup/api/dto/Dtos.kt"
API_OUT = "D:/linkup-rebuild/net/src/main/kotlin/com/youloft/linkup/net/LinkUpApi.kt"
MAP_OUT = "D:/linkup-rebuild/recovered/bean_map.json"

# R8-shrunk helpers we do not want to fabricate classes for
ALIAS = {"o2": "Unit", "kotlin.Unit": "Unit"}

TYPE_MAP = {"boolean": "Boolean", "int": "Int", "long": "Long", "double": "Double",
            "float": "Float", "short": "Int", "byte": "Byte", "char": "String",
            "java.lang.String": "String", "String": "String", "java.lang.Integer": "Int",
            "java.lang.Long": "Long", "java.lang.Boolean": "Boolean",
            "java.lang.Double": "Double", "java.lang.Float": "Float",
            "Boolean": "Boolean", "Integer": "Int?", "Long": "Long?",
            "Double": "Double?", "Float": "Float?", "Character": "String?"}

DEFAULTS = {"Boolean": "false", "Int": "0", "Long": "0L", "Double": "0.0", "Float": "0f",
            "Byte": "0"}


def index_classes():
    """simple name -> (package, java path). Prefer readable packages on collision."""
    idx = {}
    for dirpath, _dirs, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".java"):
                continue
            simple = f[:-5]
            pkg = os.path.relpath(dirpath, SRC).replace(os.sep, ".").rstrip(".")
            readable = pkg.startswith("com.youloft")
            cur = idx.get(simple)
            if cur is None or (readable and not cur[2]):
                idx[simple] = (pkg, os.path.join(dirpath, f), readable)
    return idx


IDX = index_classes()

FIELD_RE = re.compile(r'^\s*(?:private|public|protected)\s+(?:static\s+)?(?:final\s+)?'
                      r'([\w.$<>,?\s\[\]]+?)\s+(\w+)\s*(?:=.*)?;\s*$')
ANN_RE = re.compile(r'@(?:vl|pl|kotlin\.jvm\.internal)\.(l|m)\b')


def split_top(decl, open_ch, close_ch):
    i = decl.index(open_ch)
    depth = 0
    for j in range(i, len(decl)):
        if decl[j] == open_ch:
            depth += 1
        elif decl[j] == close_ch:
            depth -= 1
            if depth == 0:
                return decl[i + 1:j], j + 1
    return "", len(decl)


def parse_fields(path):
    """Return [(name, raw_type, nullable)] reading annotations attached above each field."""
    lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    out = []
    pending = None
    for line in lines:
        a = ANN_RE.search(line)
        if a and ";" not in line and "=" not in line:
            pending = a.group(1)  # l = non-null, m = nullable
            continue
        m = FIELD_RE.match(line)
        if not m:
            continue
        raw_type, name = m.group(1).strip(), m.group(2)
        if name in ("serialVersionUID",):
            continue
        nullable = (pending == "m") if pending else not raw_type.islower()
        pending = None
        out.append((name, raw_type, nullable))
    return out


def class_generics(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r'^\s*(?:public|final|\s)*\s*class\s+(\w+)\s*<([^>]+)>', text, re.M)
    return m.group(2).replace(" ", "") if m else ""


FIELDS_CACHE = {}


def fields_of(path):
    if path not in FIELDS_CACHE:
        seen, out = set(), []
        for name, raw, nul in parse_fields(path):
            if name in seen:          # jadx can emit the same field twice
                continue
            seen.add(name)
            out.append((name, raw, nul))
        FIELDS_CACHE[path] = out
    return FIELDS_CACHE[path]


def simple(t):
    return t.strip().split(".")[-1]


def map_type(raw, nullable, queue):
    """Convert a decompiled Java type into a Kotlin type, queueing beans for generation."""
    raw = raw.strip()
    generic_part, _ = split_top(raw, '<', '>') if '<' in raw else ("", 0)
    head = simple(raw.split('<')[0].strip())

    if head in ("List", "Collection", "Iterable"):
        inner = [map_type(x, True, queue) for x in split_top(raw, '<', '>')[0].split(",")]
        return f"List<{inner[0] if inner else 'JsonElement'}>" + ("?" if nullable else "")
    if head == "Set":
        return "List<String>" + ("?" if nullable else "")
    if head in ("Map", "HashMap", "ArrayMap"):
        return "Map<String, JsonElement>" + ("?" if nullable else "")
    if head == "Object":
        return "JsonElement?"
    if raw.startswith("[]") or raw.endswith("[]"):
        return f"List<{map_type(raw.replace('[]', ''), False, queue)}>" + ("?" if nullable else "")

    if head in ALIAS:
        return ALIAS[head]
    if re.fullmatch(r"[A-Z]", head):        # a type variable (T/E) stays a type variable
        return head + ("?" if nullable else "")
    if head in TYPE_MAP:
        mapped = TYPE_MAP[head]
        return mapped if mapped.endswith("?") or not nullable else mapped + "?"

    entry = IDX.get(head)
    if entry is None:
        return "JsonElement?"
    _pkg, path, readable = entry
    if not readable:
        # A third-party SDK type (Tencent TuringFD `Date<T>`, ad beans, ...) is not part of this
        # product's API surface, so the DTO layer models it as raw JSON instead of cloning it.
        return "JsonElement?"
    if not fields_of(path):
        return "JsonElement?"
    if head not in generated and head not in queue:
        queue.append(head)
    return head + "?"                             # Gson leaves absent fields null


queue = collections.deque()
generated = {}
bean_map = {}

routes = json.load(open(ROUTES, encoding="utf-8"))


def generate(root):
    queue.append(root)
    while queue:
        name = queue.popleft()
        if name in generated:
            continue
        entry = IDX.get(name)
        if not entry:
            continue
        _pkg, path, readable = entry
        if not readable:
            continue
        generics = class_generics(path)
        fields = fields_of(path)
        if not fields:
            continue
        generated[name] = {"generics": generics,
                           "fields": [(n, map_type(t, nul, queue), nul) for n, t, nul in fields],
                           "source": os.path.relpath(path, SRC).replace("\\", "/")}


TYPE_RE = re.compile(r'ApiResponse<(.*)>\s+dVar')
for r in routes:
    decl = r["response"].strip()          # already balanced by recover_routes.py
    if decl:
        base = decl.split("<")[0].strip()
        if base and base not in ALIAS and base not in ALIAS.values():
            generate(base)

os.makedirs(os.path.dirname(DTO_OUT), exist_ok=True)
lines = ["package com.youloft.linkup.api.dto", "",
         "import com.google.gson.JsonElement", ""]
for name, spec in sorted(generated.items()):
    g = f"<{spec['generics']}>" if spec["generics"] else ""
    lines.append(f"/** recovered from {spec['source']} */")
    lines.append(f"data class {name}{g}(")
    body = []
    for fname, ftype, nullable in spec["fields"]:
        if ftype.endswith("?"):
            default = " = null"
        elif ftype in DEFAULTS:
            default = f" = {DEFAULTS[ftype]}"
        else:
            default = ""
        kn = re.sub(r"[^A-Za-z0-9_]", "", fname)
        if kn in ("in", "object", "fun", "is", "when", "type", "val", "var"):
            kn = f"`{kn}`"
        body.append(f"    val {kn}: {ftype}{default},")
    lines.extend(body)
    lines.append(")")
    lines.append("")
open(DTO_OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines))

for name, spec in sorted(generated.items()):
    bean_map[name] = spec["source"]
json.dump(bean_map, open(MAP_OUT, "w", encoding="utf-8"), indent=1)


def kotlin_response(decl):
    """Map a decompiled generic like `ListBean<LockInfo>` onto a generated Kotlin type."""
    decl = decl.strip()
    head = simple(decl.split("<")[0].strip())
    inner = split_top(decl, "<", ">")[0] if "<" in decl else ""
    if head in ALIAS:
        return ALIAS[head]
    if re.fullmatch(r"[A-Z]", head):        # a type variable (T/E) stays a type variable
        return head + ("?" if nullable else "")
    if head == "ListBean":
        return f"ListBean<{', '.join(kotlin_response(x) for x in inner.split(',')) or 'JsonElement'}>"
    if head in ("List", "Collection"):
        return f"List<{', '.join(kotlin_response(x) for x in inner.split(',')) or 'JsonElement'}>"
    if head in ("Map", "HashMap", "ArrayMap"):
        return "Map<String, JsonElement>"
    if head == "String":
        return "String"
    if head in TYPE_MAP:
        return TYPE_MAP[head]
    if head in ALIAS:
        return ALIAS[head]
    return head if head in generated else "JsonElement"


METHOD_ANN = {"f": "GET", "o": "POST", "p": "PUT", "b": "DELETE", "n": "PATCH", "m": "OPTIONS"}
PARAM = {"t": "Query", "v": "QueryName", "u": "QueryMap", "a": "Body", "y": "Url",
         "s": "Path", "i": "Header", "j": "HeaderMap", "c": "Field", "d": "FieldMap",
         "r": "PartMap", "x": "Part", "q": "Part"}
KTYPE = {"long": "Long", "int": "Int", "short": "Int", "boolean": "Boolean",
         "double": "Double", "float": "Float", "String": "String"}


def arg_name(raw, idx):
    n = raw.strip()
    if re.match(r"^[a-z][A-Za-z0-9]*$", n):
        return n
    if re.match(r"^[A-Z][A-Za-z0-9]*$", n):
        return n[0].lower() + n[1:]
    return f"q{idx}"


def fn_name(path, used):
    slug = "".join(p.capitalize() for p in re.split(r"[_/]+", path.removeprefix("api/")) if p)
    n = re.sub(r"[^A-Za-z0-9]", "", slug)
    n = n[0].lower() + n[1:]
    base, i = n, 2
    while n in used:
        n, i = f"{base}{i}", i + 1
    used.add(n)
    return n


used, decls, typed = set(), [], 0
for r in routes:
    if not r["path"]:
        continue
    decl = r["response"].strip()
    rt = kotlin_response(decl) if decl else "JsonElement"
    if rt != "JsonElement":
        typed += 1
    args = []
    for i, p in enumerate(r["params"]):
        if p["ann"] == "Query":
            jt = p["type"].rsplit(" ", 1)[0].strip()
            args.append(f'@Query("{p["arg"]}") {arg_name(p["arg"], i)}: {KTYPE.get(jt, "String")}')
        elif p["ann"] == "Body":
            jt = p["type"].rsplit(" ", 1)[0].strip()
            head = simple(jt.split("<")[0].strip())
            args.append(f"@Body payload: {head}" if head in generated
                        else "@Body payload: Map<String, Any?>")
    decls.append(f'    @{r["verb"]}("{r["path"]}")\n'
                 f'    suspend fun {fn_name(r["path"], used)}({", ".join(args)}): ApiResponse<{rt}>')

header = ("package com.youloft.linkup.net\n\n"
          "import com.google.gson.JsonElement\n"
          "import com.youloft.linkup.api.dto.*\n"
          "import retrofit2.http.Body\n"
          "import retrofit2.http.GET\n"
          "import retrofit2.http.POST\n"
          "import retrofit2.http.Query\n\n"
          "/* Responses are recovered data classes; JsonElement stays only where the bean was\n"
          "   not present in the decompiled source (see recovered/bean_map.json). */\n"
          "interface LinkUpApi {\n\n" + "\n\n".join(decls) + "\n}\n")
open(API_OUT, "w", encoding="utf-8", newline="\n").write(header)

print(f"generated classes: {len(generated)}")
print(f"typed responses: {typed}/{len(decls)} routes")
print("wrote", DTO_OUT, "and", API_OUT)
