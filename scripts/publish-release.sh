#!/usr/bin/env bash
#
# 发一次版本：把当前分支合并进 main（发版一律落在 main 上）、构建两个 ABI 的 APK、
# 按当前版本号生成更新清单 version.json，并建 GitHub Release。
#
# 用法：
#   scripts/publish-release.sh v1.1.1 [--notes-file 发行说明.md]
#         [--manifest-notes-file 中文清单说明.md] [--manifest-notes-en-file 英文清单说明.md]
#         [--skip-merge] [--merge-only] [--dry-run]
#
#   --notes-file             Releases 页面上的完整发行说明（缺省用 gh 自动生成）。
#   --manifest-notes-file    应用内弹窗显示的中文说明；只取该文件第一段。
#   --manifest-notes-en-file 同上，写入清单 notesEn；应用按界面语言在两者间选。
#   --skip-merge             不合并进 main（tag 打在当前分支上，仅用于排查）。
#   --merge-only             只做「合并进 main 并推送」这一步，不发版。
#   --dry-run                只校验并打印计划，不动 git、不构建、不发版。
#
# 分支规则（发版的第一步）：**发版一律落在 main 上**。当前不在 main 时，先把当前分支合并进
# main 并推送，再在 main 的 HEAD 上构建与打 tag（`gh release create --target main`），保证
# 「main 上的代码 = 发布的包」。工作区必须干净，否则拒绝执行。
#
# 前置：
#   - `gh auth status` 已登录，且有该仓库的写权限；
#   - `keystore.properties` 存在（否则产出的是未签名包，装不上）；
#   - `app/build.gradle.kts` 里的 versionName 与 tag 对应（脚本会检查），versionCode 已递增。
#
# 清单地址固定走 `releases/latest/download/version.json`：换版本不用改应用里的更新源，
# 所以 APK 也被构建期嵌入这个地址（-PupdateManifestUrl）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ $# -lt 1 ]]; then
  echo "用法: scripts/publish-release.sh <tag> [--notes-file <文件>] [--manifest-notes-file <文件>] [--manifest-notes-en-file <文件>] [--skip-merge] [--merge-only] [--dry-run]" >&2
  exit 2
fi

TAG="$1"; shift
NOTES=""
MANIFEST_NOTES=""
MANIFEST_NOTES_EN=""
DRY_RUN=0
SKIP_MERGE=0
MERGE_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes-file) NOTES="${2:?--notes-file 需要一个文件}"; shift 2 ;;
    --manifest-notes-file) MANIFEST_NOTES="${2:?--manifest-notes-file 需要一个文件}"; shift 2 ;;
    --manifest-notes-en-file) MANIFEST_NOTES_EN="${2:?--manifest-notes-en-file 需要一个文件}"; shift 2 ;;
    --skip-merge) SKIP_MERGE=1; shift ;;
    --merge-only) MERGE_ONLY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$CURRENT_BRANCH" == "HEAD" ]]; then
  echo "当前处于 detached HEAD，无法确定要合并的分支。" >&2
  exit 1
fi

if [[ $DRY_RUN -eq 0 && -n "$(git status --porcelain)" ]]; then
  echo "工作区不干净：发版前请先提交（git status 里还有未提交改动）。" >&2
  exit 1
fi

RELEASE_BRANCH="$CURRENT_BRANCH"
if [[ $SKIP_MERGE -eq 0 ]]; then RELEASE_BRANCH="main"; fi

# 第一步：发版落在 main 上——不在 main 就先把当前分支合并进去。
merge_into_main() {
  if [[ "$CURRENT_BRANCH" == "main" ]]; then
    echo "已在 main：推送当前提交"
    git push origin main
    return
  fi
  echo "把 ${CURRENT_BRANCH} 合并进 main"
  git fetch origin main
  git checkout main
  if ! git merge-base --is-ancestor origin/main main; then
    git merge --ff-only origin/main
  fi
  if git merge-base --is-ancestor "$CURRENT_BRANCH" HEAD; then
    echo "  main 已包含 ${CURRENT_BRANCH}，无需合并"
  else
    git merge --no-ff -m "chore(release): 合并 ${CURRENT_BRANCH}，发布 ${TAG}" "$CURRENT_BRANCH"
  fi
  git push origin main
  echo "  main 现在在 $(git log --oneline -1)"
}

if [[ $SKIP_MERGE -eq 1 ]]; then
  echo "已跳过合并：tag 将打在 ${CURRENT_BRANCH} 上（仅排查用）。"
elif [[ $DRY_RUN -eq 1 ]]; then
  if [[ "$CURRENT_BRANCH" == "main" ]]; then
    echo "（dry-run）已在 main，无需合并；发版会先 git push origin main。"
  else
    echo "（dry-run）会把 ${CURRENT_BRANCH} 合并进 main、推送 main，再在 main 上构建与打 tag ${TAG}。"
  fi
else
  merge_into_main
fi

if [[ $MERGE_ONLY -eq 1 ]]; then
  echo "merge-only：已把 ${CURRENT_BRANCH} 落到 main 上，未发版。"
  exit 0
fi

# 版本号在「要发布的这棵树」上校验。
GRADLE_VERSION_CODE="$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')"
GRADLE_VERSION_NAME="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"

if [[ "v$GRADLE_VERSION_NAME" != "$TAG" ]]; then
  echo "tag（${TAG}）与 app/build.gradle.kts 的 versionName（${GRADLE_VERSION_NAME}）对不上，先改版本号再发。" >&2
  exit 1
fi

SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
ASSET_BASE="https://github.com/$SLUG/releases/latest/download"
MANIFEST_URL="$ASSET_BASE/version.json"
OUT=".tooling/release-$TAG"

echo "仓库：$SLUG"
echo "分支：$(git rev-parse --abbrev-ref HEAD)（tag 目标 ${RELEASE_BRANCH}）"
echo "版本：${GRADLE_VERSION_NAME}（versionCode ${GRADLE_VERSION_CODE}）"
echo "清单：$MANIFEST_URL"
echo "产物：$OUT/"

if [[ $DRY_RUN -eq 0 ]]; then
  gh auth status >/dev/null
  [[ -f keystore.properties ]] || echo "提示：没有 keystore.properties，产出的是未签名包。" >&2
fi

if [[ $DRY_RUN -eq 1 ]]; then
  echo "dry-run：到此为止，没有动 git、没有构建、没有发版。"
  exit 0
fi

mkdir -p "$OUT"

# 先跑测试：版本号要在测试之后才可信。
if ! ./gradlew testDebugUnitTest --rerun-tasks; then
  echo "测试未通过，发版中止。若失败只有 AutoResumeDownloadTest / P1DownloadIntegrationTest 的超时" >&2
  echo "（本地 HTTP 服务 + 定时等待，偶发记录见 docs/validation/p6-phase-c-2026-10-09.md），重跑本脚本即可。" >&2
  exit 1
fi

# 两种 ABI 分开构建：同一条命令里跑 assembleDebug/assembleRelease 会撞 AGP 的打包任务，
# 而且后一次构建会清掉前一次的产物，所以每构建一种就立刻复制走。
./gradlew assembleRelease -Pabi=arm64 -PupdateManifestUrl="$MANIFEST_URL"
cp app/build/outputs/apk/release/videodown-arm64-v8a-release.apk "$OUT/"
./gradlew assembleRelease -Pabi=universal -PupdateManifestUrl="$MANIFEST_URL"
cp app/build/outputs/apk/release/videodown-universal-release.apk "$OUT/"

python3 - "$OUT/version.json" "$GRADLE_VERSION_CODE" "$GRADLE_VERSION_NAME" "$ASSET_BASE" "$SLUG" "$MANIFEST_NOTES" "$MANIFEST_NOTES_EN" <<'PY'
import json, sys
out, code, name, asset_base, slug, notes_path, notes_en_path = sys.argv[1:8]

def first_paragraph(path):
    """清单里的说明只在应用内弹窗显示，取该文件第一段；Releases 页面用完整的 --notes-file。"""
    if not path:
        return ""
    text = open(path, encoding="utf-8").read().strip()
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip() and not p.lstrip().startswith("#")]
    return paragraphs[0].replace("\n", " ") if paragraphs else ""

manifest = {
    "versionCode": int(code),
    "versionName": name,
    "downloadUrl": f"{asset_base}/videodown-arm64-v8a-release.apk",
    "releasePageUrl": f"https://github.com/{slug}/releases/latest",
    "notes": first_paragraph(notes_path),
    "notesEn": first_paragraph(notes_en_path),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(open(out, encoding="utf-8").read())
PY

NOTES_ARGS=()
if [[ -n "$NOTES" ]]; then NOTES_ARGS=(--notes-file "$NOTES"); else NOTES_ARGS=(--generate-notes); fi

# --target 显式指向发布分支：tag 与 main 的 HEAD 对齐，不受当前 checkout 影响。
gh release create "$TAG" \
  "$OUT/videodown-arm64-v8a-release.apk" \
  "$OUT/videodown-universal-release.apk" \
  "$OUT/version.json" \
  --target "$RELEASE_BRANCH" \
  --title "拾影 ${GRADLE_VERSION_NAME}（Keepsake）" \
  "${NOTES_ARGS[@]}"

echo
echo "已发布：https://github.com/$SLUG/releases/tag/$TAG （tag 打在 ${RELEASE_BRANCH} 上）"
echo "应用内更新源：$MANIFEST_URL"
echo "（老版本要能看到升级，清单里的 versionCode 必须大于它自己的 versionCode。）"
