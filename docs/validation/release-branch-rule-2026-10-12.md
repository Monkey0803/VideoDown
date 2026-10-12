# 发版分支规则：一律落在 main（2026-10-12）

## 规则

**发版的第一步是把当前分支合并进 `main`。** 具体是：

1. 工作区必须干净（有未提交改动直接拒绝，避免把半成品打进 tag）；
2. 当前不在 `main` 时：`git fetch origin main` → `git checkout main` → 需要时 `--ff-only` 对齐 `origin/main`
   → `git merge --no-ff` 当前分支 → `git push origin main`；
3. 构建与打 tag 都在 `main` 的 HEAD 上进行：`gh release create … --target main`，
   保证「**`main` 上的代码 = 发布的包**」，而不是「某个分支上的代码 = 发布的包，main 落后」。

落地位置：`scripts/publish-release.sh`（新增 `--merge-only` 只做合并推送、`--skip-merge` 仅排查、
`--dry-run` 只校验打印计划），README「应用更新入口」一节同步。

## 自测（用本地裸仓库冒充 origin，不碰公开仓库）

```text
$ git checkout -b selftest/release-merge          # 分支上有一个空提交 ac3e5a0
$ git remote set-url origin /tmp/vd-test-origin.git
$ scripts/publish-release.sh v1.1.1 --merge-only
 * branch            main       -> FETCH_HEAD
Switched to branch 'main'
Merge made by the 'ort' strategy.
To /tmp/vd-test-origin.git
   f6ecb6c..98bfc4c  main -> main
  main 现在在 98bfc4c chore(release): 合并 selftest/release-merge，发布 v1.1.1
merge-only：已把 selftest/release-merge 落到 main 上，未发版。

$ git log --oneline --graph -3 main
*   98bfc4c chore(release): 合并 selftest/release-merge，发布 v1.1.1
|\
| * ac3e5a0 chore(release): 发版流程自测用的空提交
|/
* f6ecb6c feat(release): 发版脚本先把当前分支合并进 main
```

- 合并是真正的 `--no-ff` 合并提交（两个父提交），本地 `main` 与裸仓库 `main` 都推进到它；
- 自测后清理：`origin` 恢复、`main` 回到自测前的 `f6ecb6c`、自测分支与临时裸仓库删除。
- 另外验证了两条分支路径：非 `main` 分支上 `--dry-run` 只打印计划（提示会合并进 `main` 并推送），
  `git` 状态零变化；`--skip-merge` 会明确提示「tag 打在当前分支上，仅排查用」。
- 工作区不干净的守卫是实测过的：第一次自测因为 `README.md`/脚本改动还没提交而被脚本直接拒绝。

## 未覆盖边界

- 自测用的是**本地裸仓库**，没有真的推过 GitHub `main`；`gh release create --target main` 这一段
  到下次真实发版时才会走到（脚本只在最后一步用它，语法与参数已按 `gh` 文档核对）。
- **冲突未覆盖**：`main` 与待发布分支如果改到同一处，合并会在 `git merge` 处停下并把控制权交回人工，
  脚本不会自动解决（`set -e` 直接退出）。
- `1.0.2` 这个旧分支仍在远端且落后于 `main`：新规则下它若再被用来发版会被合并进 `main`；
  不再使用的话可以删除（`git push origin --delete 1.0.2`），本轮没有替你删。
- 发版脚本这次的改动曾被我在自测清理时用 `git reset --hard` 误删过一次（当时改动还没提交），
  已重做并先提交再自测；教训是**自测前先提交**。
