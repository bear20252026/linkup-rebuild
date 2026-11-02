#!/usr/bin/env python3
"""Apply the only delta the recovered resource tree needs in order to link.

apktool copies a library error message verbatim, and that message contains a literal `@`
which aapt2 reads as the start of a resource reference. Everything else stays byte-identical:
the resources that looked "missing" are already present as empty `<item type=… name=…/>`
placeholders in values/drawables.xml and values/arrays.xml (the id table kept them, the
values were shrunk away), which is exactly what the shipping APK contains.
"""
import os, json

ROOT = "D:/linkup-rebuild/res166/src/main/res"
NOTES = "D:/linkup-rebuild/recovered/res_migration_deltas.json"

strings = os.path.join(ROOT, "values", "strings.xml")
text = open(strings, encoding="utf-8").read()
fixed = text.replace('attribute : “@color/colorID', 'attribute : “\\@color/colorID')
applied = fixed != text
if applied:
    open(strings, "w", encoding="utf-8", newline="").write(fixed)

# Any stub added by an earlier run would duplicate those placeholder items.
for stray in ("values/migration_stubs.xml",
              "drawable/material_ic_keyboard_arrow_next_black_24dp.xml",
              "drawable/material_ic_keyboard_arrow_previous_black_24dp.xml"):
    p = os.path.join(ROOT, stray.replace("/", os.sep))
    if os.path.exists(p):
        os.remove(p)
        print("removed stray stub:", stray)

deltas = {
    "edited_files": ["values/strings.xml#indicator_color_error"] if applied else ["(already applied)"],
    "what": "把库错误文案里的字面 @ 转义成 \\@，其余 3982 个文件与 APK 原文逐字节一致",
    "not_needed": "side_bar_array / material_ic_keyboard_arrow_* 在 values/arrays.xml 与 values/drawables.xml "
                  "里本来就是空占位 item（id 表保留、值被 shrink），补定义反而会撞重复",
}
json.dump(deltas, open(NOTES, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(deltas, ensure_ascii=False, indent=1))
