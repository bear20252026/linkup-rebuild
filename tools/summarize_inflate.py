#!/usr/bin/env python3
"""Summarize the Robolectric inflate survey into the numbers a planning meeting needs:
how much of the recovered UI runs today, and who owns each remaining blocker.

Run after: gradle :app:testDebugUnitTest --tests "*UiInflateSurvey*"
"""
import csv, re, collections, os, json

CSV = "D:/linkup-rebuild/app/build/ui_inflate_per_layout.csv"
OUT = "D:/linkup-rebuild/recovered/ui_inflate_summary.json"

BIZ = re.compile(r"^(activity|fragment|dialog|view|item|chat_row|widget|layout)_")
AD = re.compile(r"^(ksad|sdm_|anythink|csj|ttlive|pangle|mob_|gdt|mopub|adn_|browserkit)")

rows = {}
with open(CSV, encoding="utf-8") as f:
    for r in csv.reader(f):
        if len(r) >= 2:
            rows[r[0]] = (r[1], r[2] if len(r) > 2 else "")

def owner(cls):
    if cls.startswith("com.youloft"):
        return "APP-OWNED view (要我们重建)"
    if cls.startswith(("com.kwad", "com.smartdigimkt", "com.anythink", "com.byazt",
                       "com.bytedance", "com.qq.e", "com.google.android.gms")):
        return "广告 SDK 自带 UI"
    if cls.startswith("com.hyphenate"):
        return "环信 EaseUI"
    if cls.startswith(("androidx.", "com.google.android.material")):
        return "androidx 组件"
    if re.match(r"^[a-z]{2,6}\.", cls):
        return "其它三方 UI 库"
    return "非类名原因"

def rate(sel, label):
    sub = {k: v for k, v in rows.items() if sel(k)}
    ok = sum(1 for v in sub.values() if v[0] == "ok")
    return label, len(sub), ok, (ok * 100 // len(sub) if sub else 0)

summary = [
    rate(lambda k: True, "全部布局"),
    rate(lambda k: bool(BIZ.match(k)), "业务命名布局"),
    rate(lambda k: bool(AD.match(k)), "广告 SDK 前缀布局"),
]

cause_count = collections.Counter()
owner_count = collections.Counter()
biz_cause = collections.Counter()
for name, (status, msg) in rows.items():
    if status == "ok":
        continue
    m = re.search(r"Error inflating class (\S+)", msg)
    cls = m.group(1) if m else msg[:60]
    cause_count[cls] += 1
    owner_count[owner(cls)] += 1
    if BIZ.match(name):
        biz_cause[cls] += 1

print(f"layouts surveyed: {len(rows)}")
for label, total, ok, pct in summary:
    print(f"  {label:<22} total={total:4d} inflated_ok={ok:4d} ({pct}%)")
print("\n失败按归属:")
for k, v in owner_count.most_common():
    print(f"  {v:4d}  {k}")
print(f"\n业务布局仍失败: {sum(1 for k, v in rows.items() if v[0]=='fail' and BIZ.match(k))} 个，缺的自有 View 类:")
for k, v in biz_cause.most_common(16):
    if k.startswith("com.youloft"):
        print(f"   {v:3d}  {k}")

json.dump({
    "surveyed": len(rows),
    "rates": [{"scope": l, "total": t, "ok": o, "pct": p} for l, t, o, p in summary],
    "failures_by_owner": dict(owner_count),
    "app_owned_views_missing": {k: v for k, v in biz_cause.items() if k.startswith("com.youloft")},
    "top_causes": dict(cause_count.most_common(40)),
}, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("\nwrote", OUT)
