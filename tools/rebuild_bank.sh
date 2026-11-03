#!/bin/bash
# Rebuild :res166 from the pristine apktool output, then strip whatever the CURRENT dependency
# set owns. Run this after adding or removing any third-party dependency in :app, otherwise the
# bank keeps stale copies of the library's attrs/styles and aapt2 fails on duplicates.
#
# The dependency list is refreshed from Gradle first: strip_lib_res.py reads _raw/app_deps.txt,
# so an outdated file silently strips the wrong set.
set -u
cd /d/linkup-rebuild || exit 1
LOG=_raw/bank.log
: > "$LOG"
sh tools/gradle.sh :app:dependencies --configuration debugRuntimeClasspath --console=plain > _raw/app_deps.txt 2>&1
sh tools/gradle.sh :app:fetchDebugArtifacts --console=plain -q >> "$LOG" 2>&1; echo "fetch_rc=$?" >> "$LOG"
echo "deps_rc=$? modules=$(grep -oE '[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9][a-zA-Z0-9._-]*' _raw/app_deps.txt | sort -u | wc -l)" >> "$LOG"
# migrate copies INTO the tree, so it must start empty or last run's renamed files
# collide with the freshly copied originals (this bit us once already).
rm -rf res166/src/main/res
python tools/migrate_res.py       >>"$LOG" 2>&1; echo "migrate_rc=$?"     >>"$LOG"
python tools/fix_res_names.py     >>"$LOG" 2>&1; echo "names_rc=$?"      >>"$LOG"
python tools/apply_res_deltas.py  >>"$LOG" 2>&1; echo "deltas_rc=$?"     >>"$LOG"
python tools/strip_lib_res.py     >>"$LOG" 2>&1; echo "strip_rc=$?"      >>"$LOG"
tail -8 "$LOG"
