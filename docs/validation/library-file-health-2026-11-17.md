# 已存文件健康检查与修复验证（I4，2026-11-17）

## 结论

I4 已完成实现与自动化验证：已存文件先用「路径 + 大小 + 修改时间」做低成本检查，用户主动
「检查文件」时才执行容器 / 轨道 / 尾部结构校验；文件缺失、与完成时不一致或结构损坏时，
界面给出「重新下载」（沿用原地址重启同一条记录）与「重新解析」（回到解析页）两个修复入口，
重新下载完整保留收藏、分类、隐私和手动排序。

## 实现范围

- `domain/model/StoredFileHealth.kt`（新增，纯 Kotlin）：
  - `StoredFileHealth`：`UNKNOWN`（旧记录没有完成快照，只能确认文件在）/ `HEALTHY` /
    `MISSING` / `CHANGED`（大小或修改时间与快照不同）/ `CORRUPT`（结构校验失败）。
  - `FileHealthPolicy.cheapHealth(...)`：任一侧快照缺失都**不**升级为 CHANGED，避免旧记录被误判；
    `afterStructureCheck(...)`：结构校验失败一律 CORRUPT，通过时沿用低成本结论。
- `domain/model/DownloadRecord.kt`：新增可空默认字段 `fileSizeBytes` / `fileModifiedAt`；
  旧 `download_history.json` 反序列化为 `null`（只做存在性判断）。
- `data/download/DownloadEngine.kt`：直链、引擎与打包三种完成路径都在完成时写入文件快照；
  `restart` 在删除成品的同时清空快照，重新下载完成后写回新的快照。
- `data/download/LibraryFileInspector.kt`（新增）：
  - `cheapCheck` 只做 `stat`（存在性、大小、修改时间）；
  - `inspect` 先做低成本比对，文件存在时再复用 `validateDownloadedFile`（容器、轨道、尾部），
    结构校验放在 IO 线程，不做全量哈希、不逐帧解码；
  - 文件缺失时直接返回 MISSING，不去打开不存在的文件。
- `presentation/library/LibraryActions.kt`：完成记录的操作面板新增「检查文件」；
  健康/未知用提示条报告，缺失/变化/损坏弹出「文件检查」对话框，主操作「重新下载」、
  次操作「重新解析」、「取消」关闭。
- `presentation/library/LibraryScreen.kt` + `presentation/MainActivity.kt`：
  `onRedownload` 接到 `HistoryViewModel.restart`，`onReparse` 取
  `sourcePageUrl`/`media.pageUrl` 交给解析页并切到解析 Tab。
- 文案：新增 `library_check_file` / `library_checking` / `library_check_title` /
  `library_check_healthy` / `library_check_unknown` / `library_check_missing` /
  `library_check_changed` / `library_check_corrupt`（中英各 8 条）。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**630 个用例通过**。
- 新增 `FileHealthPolicyTest`（8 例）：缺失优先于一切；快照一致为健康；无快照为未知；
  大小不同或修改时间不同为「已变化」；实际大小不可读不当作损坏；结构失败一律损坏；
  结构通过时沿用低成本结论。
- 新增 `LibraryFileInspectorTest`（6 例，注入假结构校验）：一致快照 + 结构通过 = 健康；
  文件删除时不调用结构校验；结构失败即使快照一致也报损坏；大小变化在结构通过后仍是「已变化」；
  旧记录会校验但结论保持未知；无路径记录直接缺失。
- 新增 `RestartPreservesMetadataTest`（1 例，本地 HTTP 服务）：完成记录带
  `favorite=true / folder=旅行 / isPrivate=true / sortIndex=7`，删除文件后 `restart`，
  重新完成后四个字段原样保留、文件快照刷新、内容与原始字节一致。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

- 新增 `LibraryFileHealthDeviceTest` **6/6 通过**（真实 PNG / MP4 与生产检查器）：
  完整图片与视频为 HEALTHY；删除文件为 MISSING；截断 PNG 在低成本层为 CHANGED、
  深入检查为 CORRUPT；截断 MP4 为 CORRUPT；无快照旧记录为 UNKNOWN；无快照的损坏文件仍能判 CORRUPT。
- 新增 `LibraryFileHealthUiDeviceTest` **2/2 通过**（Compose 界面）：
  - 完整图片点「检查文件」→ 提示条显示「文件完整…」；
  - 删除文件后点「检查文件」→ 弹出「文件检查」对话框，点「重新下载」确实回调了下载器。
- 既有 `LibraryActionsDeviceTest` 复跑通过（操作面板改动未影响长按菜单与删除确认）。

- 结果 XML 归档在
  [device-api37/LibraryFileHealthDeviceTest.xml](library-file-health-2026-11-17/device-api37/LibraryFileHealthDeviceTest.xml)
  与 [device-api37/LibraryFileHealthUiDeviceTest.xml](library-file-health-2026-11-17/device-api37/LibraryFileHealthUiDeviceTest.xml)（6 + 2 条用例，0 失败）。

### 构建

- `./gradlew compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin`
  通过；`assembleDebug`、`assembleRelease` 通过（未配置签名时 Release 保持未签名）。

## 未覆盖边界

1. **「重新解析」的真实导航未端到端验证**：界面回调已接到 `parseSharedText` + 切解析 Tab，
   但没有针对真实链接跑完整流程；本轮只验证了「重新下载」回调被触发。
2. **重新下载依赖原地址仍然有效**：签名地址过期时任务会以链接过期失败，需要用户再点「重新解析」；
   这一组合路径没有自动用例。
3. **不检测文件中间的空洞**，也不比较内容哈希：大小与修改时间一致就视为健康，只做一次结构校验。
4. **「已变化」只提示不动作**：不会自动删除或覆盖「已变化」的文件，避免误删用户替换过的内容。
5. **低成本检查没有接到列表滚动路径**：目前只在用户点「检查文件」时执行；批量健康巡检未做。
6. 平板 / 折叠屏与 API 26/28 设备未验证；本轮只在 API 37 模拟器上运行。
