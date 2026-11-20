# 已存「重命名与分类」对平台多行标题报「整理失败」（2026-11-20）

## 结论

从平台保存的标题是原文，X 推文的换行会原样进库。重命名弹窗把这个标题预填进「文件名称」，
用户只改第一行时换行仍留在文本里；`DownloadLibraryMetadataRepository` 却用文件名校验规则
拒绝 ISO 控制字符，于是保存必定失败，界面只显示笼统的「整理失败，请检查名称、文件和可用空间
后重试」。现在标题只校验非空与长度，真正写盘的文件名继续由 `DownloadPaths.buildFileName`
统一清洗（路径分隔符、换行、其它控制字符都换成空格），分类标签的严格校验保持不变。

## 现象与复现

用户提供的截图：已存 10 项，长按记录打开「重命名与分类」，文件名称已改成「还得是前排32」、
分类填「美女」，点「保存修改」后弹窗内出现红字「整理失败，请检查名称、文件和可用空间后重试」。

在 Pixel_10_Pro AVD / Android 17（API 37，arm64）上复现到同一条数据：

| 项 | 值 |
| :--- | :--- |
| 记录 | `189ed024-cd00-4edd-8743-11fe3a57fa49`（X，视频，`status=COMPLETED`） |
| 库内标题 | `Lisa 前排 4K ，还得是前排\n\n这钱花的真值 …\n\n风光无限好啊`（59 字符，含 4 个 `\n`） |
| 弹窗提交的标题 | `还得是前排32\n\n这钱花的真值 …\n\n风光无限好啊`（49 字符，仍含 4 个 `\n`） |
| 结果 | 弹窗显示「整理失败…」；`download_history.json` 的 title / folder 均未改变；mp4 未改名 |
| 磁盘 | `files/library-renames/` 目录**没有生成**，`rename.json` 也不存在 |

没有生成 journal，说明失败发生在写盘事务之前——即参数校验阶段，与空间、文件占用无关。

## 根因

`data/local/DownloadLibraryMetadataRepository.kt` 的旧校验把标题和分类当成同一类名字：

```kotlin
require(name == null || validName(name, 80))          // 标题
require(category == null || category.isEmpty() || validName(category, 40))
private fun validName(value: String, max: Int) = value.isNotBlank() && value.length <= max &&
    value.none { it.isISOControl() || it in "\\/:*?\"<>|" } && value !in setOf(".", "..")
```

`String.isISOControl()` 对 `\n` 返回 true，所以任何带换行的平台标题只要用户动过标题字段，
就一定被拒。而首次下载时走的是 `DownloadPaths.buildFileName → sanitizeTitle`，它会把
`\r\n\t` 和路径分隔符换成空格——同一条标题在下载阶段是合法的，到了重命名阶段却变成非法，
两处规则不一致。标题里常见的 `?`、`/`（如英文问句、`AC/DC`）同样会被误杀。

## 实现范围

- `data/local/DownloadLibraryMetadataRepository.kt`：
  - 新增 `validTitle`：只要求非空、长度 ≤ 80（与弹窗按钮的启用条件一致）；标题是展示用元数据，
    不再承担文件名校验。
  - 原 `validName` 拆成 `validCategory`，仍拒绝路径分隔符、ISO 控制字符以及 `.`/`..`——分类是
    库内标签，不经过文件名清洗，保留严格校验是合理的。
  - 失败文案 `meta_name_invalid` 由「不能包含路径分隔符或控制字符」改为「不能为空，且不能超过
    80 个字符」，与新的校验范围一致（中英文各一条）。
- `data/download/DownloadPaths.kt`：`sanitizeTitle` 的清洗正则由 `\r\n\t` 扩到 `\p{Cntrl}`，
  保证任何来源的控制字符都不会进入文件名；下载与重命名共用这一个出口，并在 KDoc 里写明
  「不要绕过 buildFileName」。
- `README.md`：P2 重命名条目补充标题原文与文件名清洗的关系。

## 自动化证据

### JVM（`./gradlew testDebugUnitTest`）

- **744 个用例全部通过**（0 失败、0 错误）。
- `DownloadLibraryMetadataTest` 共 10 例，新增：
  - `platform caption line breaks do not block a rename and never reach the file name`：
    带 `\n` 与 `\u0001` 的标题重命名成功、标题原样保留、文件名不含任何 ISO 控制字符、
    文件名以清洗后的单行标题开头、落盘目录仍是下载目录。
  - `caption punctuation cannot turn a title into a path outside the download directory`：
    标题 `../../逃逸/名字?` 保存成功，但 `canonicalFile.parentFile` 仍等于下载目录。
  - 原 `invalid names …` 用例改为 `empty or overlength names …`：空白标题、81 字符标题、
    `bad/folder` 分类、活动中任务、目录外文件仍全部被拒。
- 这些用例在修复前实测失败：`platform caption…` 与 `caption punctuation…` 卡在
  `update(...).isSuccess`，与线上现象一致。

### 设备（`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.P2LibraryOrganizationDeviceTest`）

- 新增 `platformMultilineTitleCanStillBeRenamedAndSaved`：把记录标题改成 `多行标题\n第二行`，
  用弹窗把文本替换成 `多行标题\n第二行 改` 后保存；断言无错误、标题保留换行、文件存在、
  文件名不含 `\n` 且以 `多行标题 第二行 改_` 开头。**通过**。
- 同类既有用例：`tappingOutsideTheCardDismisses…`、`favoriteControlFilters…`、
  `realPersistenceFailure…` 通过；`actualRenameAndCategorySurviveRotation…`（横屏 + 键盘
  几何判据）首轮失败，单条重跑通过——属于该 AVD 输入法时序抖动，与本改动无代码交集。
- 证据截图（测试内 `takeScreenshot`，运行后从 `cache/dialog-evidence/` 取回）：
  [`after-rename.png`](library-rename-multiline-title-2026-11-20/after-rename.png)，
  已存列表显示保存后的「多行标题 / 第二行 改」，不再出现失败提示。

## 遗留边界

- 只跑了 `P2LibraryOrganizationDeviceTest` 一个设备测试类，未重跑全部 41+ 设备用例。
- 失败提示仍是统一的「整理失败…」，具体原因（名称过长、同名文件、文件缺失等）依旧不展示；
  本次只修掉误判，没有改错误分类。
- 分类标签的严格校验未改：`/`、换行等仍会被拒，但分类输入框是单行，正常输入碰不到。
- 弹窗取消后 `organization.error` 仍会留在列表顶部的内联提示里（本次复现时观察到），属于
  另一个小的生命周期问题，未在本次改动内处理。
