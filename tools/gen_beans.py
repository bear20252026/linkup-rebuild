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

# simple name of a nested bean -> the class it is nested in (filled as beans are generated)
NESTED = {}

# `static` is excluded on purpose: Parcelable beans carry a `public static final Creator<X> CREATOR`
# field that Gson does not serialize, and it was landing in the generated data classes.
FIELD_RE = re.compile(r'^\s*(?:private|public|protected)\s+(?!static\b)(?:final\s+)?'
                      r'([\w.$<>,?\s\[\]]+?)\s+(\w+)\s*(?:=.*)?;\s*$')
ANN_RE = re.compile(r'@(?:vl|pl|kotlin\.jvm\.internal)\.(l|m)\b')
RENAMED_RE = re.compile(r'renamed from:\s*([^\s,]+)')
CLASS_RE = re.compile(r'\b(?:class|interface)\s+(\w+)')


def class_bodies(text):
    """[(name, start, end)] char ranges of every class body in the file, outermost first."""
    out = []
    for m in CLASS_RE.finditer(text):
        i = text.find('{', m.end())
        if i < 0:
            continue
        depth = 0
        for j in range(i, len(text)):
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
                if depth == 0:
                    out.append((m.group(1), i + 1, j))
                    break
    return out


def innermost(line_start, line_end, bodies):
    """Name of the smallest class body holding this line."""
    best = None
    for name, start, end in bodies:
        if start <= line_start and line_end <= end:
            if best is None or (best[2] - best[1]) > (end - start):
                best = (name, start, end)
    return best[0] if best else None


def parse_class_fields(path, owner, bodies):
    """Fields declared directly in `owner`'s body, not those of its nested classes."""
    text = open(path, encoding="utf-8", errors="replace").read()
    lines = text.split("\n")
    offsets = []
    pos = 0
    for line in lines:
        offsets.append((pos, pos + len(line)))
        pos += len(line) + 1

    out, pending, pending_name = [], None, None
    for idx, line in enumerate(lines):
        lo, hi = offsets[idx]
        a = ANN_RE.search(line)
        if a and ";" not in line and "=" not in line:
            pending = a.group(1)
            continue
        r = RENAMED_RE.search(line)
        if r:
            pending_name = r.group(1)
            continue
        m = FIELD_RE.match(line)
        if not m:
            continue
        raw_type, name = m.group(1).strip(), m.group(2)
        if name in ("serialVersionUID",):
            continue
        if innermost(lo, hi, bodies) != owner:
            pending_name = None
            continue
        # jadx renames fields that collide with a package segment (`id` becomes `f63406id`).
        # The real Java name is what Gson sees as the JSON key, so recover it from the comment.
        if pending_name and name != pending_name and name.endswith(pending_name) and re.match(r"f\d+", name):
            name = pending_name
        pending_name = None
        nullable = (pending == "m") if pending else not raw_type.islower()
        pending = None
        out.append((name, raw_type, nullable))
    return out


def primary_and_nested(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    bodies = class_bodies(text)
    if not bodies:
        return None, []
    stem = os.path.basename(path)[:-5]
    primary = next((n for n, _s, _e in bodies if n == stem), bodies[0][0])
    nested = []
    for name, _s, _e in bodies:
        if name == primary or name.endswith("Creator"):
            continue
        fields = parse_class_fields(path, name, bodies)
        if fields:
            nested.append((name, fields))
    return primary, nested


def parse_fields(path):
    """Back-compatible entry point: the top-level class's own fields."""
    owner, _nested = primary_and_nested(path)
    if owner is None:
        return []
    return parse_class_fields(path, owner, class_bodies(
        open(path, encoding="utf-8", errors="replace").read()))


def nested_classes(path):
    """[(name, fields)] for nested `class` declarations that carry data fields."""
    return primary_and_nested(path)[1]


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


def map_type(raw, nullable, queue, local=frozenset()):
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

    if head in local:
        # A nested class of the bean being generated right now; unqualified resolves inside it.
        return head + ("?" if nullable else "")
    if head in NESTED:
        parent = NESTED[head]
        if head in IDX:
            return "JsonElement?"          # ambiguous simple name; do not guess a parent
        if parent not in generated and parent not in queue:
            queue.append(parent)
        return f"{parent}.{head}?"

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
        nested = [(cn, cf) for cn, cf in nested_classes(path)]
        if not fields and not nested:
            continue
        local = {cn for cn, _ in nested}
        for cn in local:
            NESTED[cn] = name
        generated[name] = {"generics": generics,
                           "fields": [(n, map_type(t, nul, queue, local), nul) for n, t, nul in fields],
                           "nested": [(cn, [(f, map_type(t, nul, queue, local), nul)
                                             for f, t, nul in cf]) for cn, cf in nested],
                           "source": os.path.relpath(path, SRC).replace("\\", "/")}


TYPE_RE = re.compile(r'ApiResponse<(.*)>\s+dVar')
for r in routes:
    decl = r["response"].strip()          # already balanced by recover_routes.py
    if decl:
        base = decl.split("<")[0].strip()
        if base and base not in ALIAS and base not in ALIAS.values():
            generate(base)

os.makedirs(os.path.dirname(DTO_OUT), exist_ok=True)

# The original beans implemented Parcelable, which is an Android type and cannot live in the
# pure-JVM `:net` module. Screens still need to hand a whole bean to the next screen through an
# Intent, so beans whose field graph is plain Java (no JsonElement, no generics) carry
# java.io.Serializable instead. Anything holding raw JSON is left unmarked rather than promising
# a serialization it cannot deliver.
def all_fields(spec):
    out = list(spec["fields"])
    for _cn, cfields in spec["nested"]:
        out.extend(cfields)
    return out


def bean_refs(spec):
    text = " ".join(t for _, t, _ in all_fields(spec))
    return {n for n in generated if re.search(rf"\b{n}\b", text)}


serializable = {n: True for n in generated}
for n, spec in generated.items():
    if any("JsonElement" in t for _, t, _ in all_fields(spec)) or spec["generics"]:
        serializable[n] = False
for _ in range(len(generated)):
    flipped = False
    for n, spec in generated.items():
        if serializable[n] and any(not serializable[d] for d in bean_refs(spec)):
            serializable[n] = False
            flipped = True
    if not flipped:
        break


def render_fields(fields, indent):
    out = []
    for fname, ftype, nullable in fields:
        if ftype.endswith("?"):
            default = " = null"
        elif ftype in DEFAULTS:
            default = f" = {DEFAULTS[ftype]}"
        else:
            default = ""
        kn = re.sub(r"[^A-Za-z0-9_]", "", fname)
        if kn in ("in", "object", "fun", "is", "when", "type", "val", "var"):
            kn = f"`{kn}`"
        out.append(f"{indent}    val {kn}: {ftype}{default},")
    return out


lines = ["package com.youloft.linkup.api.dto", "",
         "import com.google.gson.JsonElement", ""]
for name, spec in sorted(generated.items()):
    g = f"<{spec['generics']}>" if spec["generics"] else ""
    marker = " : java.io.Serializable" if serializable[name] else ""
    lines.append(f"/** recovered from {spec['source']} */")
    lines.append(f"data class {name}{g}(")
    lines.extend(render_fields(spec["fields"], ""))
    if spec["nested"]:
        lines.append(")" + marker + " {")
        for cn, cfields in spec["nested"]:
            lines.append("")
            lines.append(f"    /** nested bean of {spec['source']} */")
            lines.append(f"    data class {cn}(")
            lines.extend(render_fields(cfields, "    "))
            lines.append("    )" + marker)
        lines.append("}")
    else:
        lines.append(")" + marker)
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
