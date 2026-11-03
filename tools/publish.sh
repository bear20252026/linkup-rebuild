#!/bin/bash
# Publish the rebuild tree into the public GitHub repo.
#
# Excluded on purpose, every run:
#   _raw/, **/build/, .gradle/, local.properties  原料与产物，不进公开仓
#   tools/jadx/                                   75MB 反编译器发行包，不是本项目的产物
#   app/src/main/assets/**                        公开仓只跟"被布局引用"的那批动画；
#                                                 从 APK 解出来的厂商 SDK 二进制（25MB）一律不推
#
# The tree is copied from `git archive HEAD`, so only committed work can ever be published.
# Run without PUSH=1 to sync + review; the script then prints the exact pending diff and stops.
set -eu

SRC="$(cd "$(dirname "$0")/.." && pwd)"
PUB="${PUB:-$SRC/../linkup-recovery-pub}"
REPO="bear20252026/linkup-recovery"

[ -d "$PUB/.git" ] || gh repo clone "$REPO" "$PUB"

cd "$PUB"
git checkout -- .
rm -rf rebuild/app rebuild/fastkit rebuild/net rebuild/res166 rebuild/recovered rebuild/tools \
       rebuild/*.kts rebuild/*.properties rebuild/README.md rebuild/.gitignore

git -C "$SRC" archive HEAD | tar -x -C "$PUB/rebuild"
cat >> rebuild/.gitignore <<'IGNORE'

# publish-only: keep derived tooling and vendor binaries out of the public repo
tools/jadx/
app/src/main/assets/
IGNORE

git add -A
git status --short
echo "== pending files: $(git status --porcelain | wc -l)"

if [ "${PUSH:-0}" = "1" ]; then
    git commit -m "${MSG:?set MSG to the commit message}"
    git push origin HEAD          # 推到 clone 当前分支跟踪的那个分支，别写死分支名
    echo "pushed ${REPO} on $(git rev-parse --abbrev-ref HEAD)"
else
    echo "dry run: set PUSH=1 MSG='...' to publish"
fi
