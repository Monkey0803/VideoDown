# 平台解析器读不出的 MP4 容器兜底校验（2026-11-20）

## 结论

X 动图（`animated_gif`）对应的 MP4 会把 `ctts` 的 flags 写成 1，而该字段按规范应为 0。
Android 的平台 `MediaExtractor` 因此放弃整条轨道（`trackCount == 0`，`getFileFormat` 直接抛
`IllegalArgumentException`），下载完成后的成品校验便以「UNKNOWN + 空提示」失败；同一份字节在
ExoPlayer 里却能正常播放。现在成品校验在平台解析器读不出任何轨道时退回纯 Kotlin 的
`Mp4Structure`（盒子结构 + `moov` 轨道），结构完整且轨道齐全就保存原字节；DRM 判定改为逐轨道
标记，平台异常也不再落成没有原因的失败。

## 现象与复现

用户报告 `https://x.com/subo08/status/2123371781994295411` 的下载任务里，「原画 · 341 KB」
这一条卡在 2% 后变成「下载失败」。该推文有 1 条动图 + 3 张图片：

| 媒体 | 地址 | 大小 | 结果 |
| :--- | :--- | :--- | :--- |
| 动图（video/mp4） | `video.twimg.com/tweet_video/HXdc8gGaoAA5OAo.mp4` | 348,911 B | **下载完成但校验失败** |
| 图片 1–3 | `pbs.twimg.com/media/HXe7X…` | 72,131 / 88,439 / 113,474 B | 正常完成 |

在 Pixel_10_Pro / Android 17（API 37，arm64）模拟器上复现出与截图完全一致的记录：
`status=FAILED`、`failureKind=UNKNOWN`、`errorMessage=null`、`downloadedBytes=8192`、
`totalBytes=348911`（8192 字节正好是第一次进度上报的值，界面上显示 2%）。
下载盘上的成品与 `curl` 取到的字节 **SHA-256 完全一致**
（`46d6da18f39e9975290ab0cfc3ab0993a1283749914981680359f8441f00361d`，348,911 B），
即「传输没问题，卡在校验」。

## 根因

在设备上直接探测同一份文件：

| 探测 | 结果 |
| :--- | :--- |
| `MediaExtractor.setDataSource(path)` + `trackCount` | 0（不抛异常） |
| `MediaExtractor.drmInitData` | 抛 `IllegalArgumentException`（`getFileFormatNative` → `getFileFormat` 返回非 OK） |
| `MediaExtractor.psshInfo` | 同上 |
| `MediaMetadataRetriever` 时长/宽高 | 全部 null |
| ExoPlayer 打开同一文件 | 正常 prepare：时长 2.66 s、720×1278，硬解出帧 |

逐项排除后，触发点定位到 `ctts`（composition time）盒子：

| 变体（均不改样本数据） | 平台 `trackCount` |
| :--- | :--- |
| 原文件 | 0 |
| 换掉 `ftyp` 品牌（iso5 → mp42） | 0（品牌不是原因） |
| 删掉整个 `ctts` | 1 |
| 只把 `ctts` flags 置 0 | 1 |
| 只把 `ctts` 的 composition offset 全置 0、保留 flags=1 | 0 |

即：**`ctts` 的 flags 非 0 时，平台的 MPEG4 解析器会放弃整条轨道**（样本表解析失败后整条
`trak` 被丢弃，`getFileFormat` 也随之失败）。这正是 X 动图 MP4 的实际形态。

## 实现范围

- `data/download/Mp4Structure.kt`（新增，纯 Kotlin，不依赖 Android）：
  用 `RandomAccessFile` 巡 ISO BMFF 顶层盒子（支持 64 位长度与 `size == 0` 末盒），
  读取 `moov → trak → mdia → hdlr` 得到轨道类型。结论三态：`NotMp4` / `Incomplete`
  （盒子越过文件末尾、缺 `moov`/`mdat`）/ `Complete(tracks)`。只读盒子头与 handler，不读媒体数据。
- `data/generic/EngineMediaDownloader.kt`：
  - `validateMediaFile` 先走平台提取器；`trackCount == 0` 或枚举抛异常时调用
    `validateByContainerStructure`：结构不完整 → `TRUNCATED`，不是 ISO BMFF → `UNSUPPORTED`，
    轨道缺失 → `MISSING_TRACK`。这一档**不做** I2 的尾部样本检查（容器都打不开，读不到尾部）。
  - DRM 判定从文件级 `drmInitData` / `psshInfo`（依赖 `getFileFormat`，读不顺就抛 IAE）改为
    逐轨道的 `crypto-key` / `is-encrypted` 标记；逐样本 `SAMPLE_FLAG_ENCRYPTED` 检查保留。
- `data/download/DownloadValidation.kt`：`validateDownloadedFile` 把未分类的平台异常归一成
  新键 `download:invalid_media`，不再让记录落成「UNKNOWN + 空提示」；新增中英文案各一条，
  `core/ErrorResources.kt` 补映射。
- `app/src/androidTest/assets/ctts-flags-fixture.mp4`（新增素材）：`faststart-fixture.mp4` 的副本，
  只把 `ctts` flags 由 0 改成 1（脚本改盒子，不动样本数据），同目录 `README.md` 已说明来源与用途。
- `README.md`：X 解析段补充这一形态，「已知限制」新增一条（含兜底覆盖不到尾部检查的说明）。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**742 个用例通过**（0 失败）。
- 新增 `Mp4StructureTest`（13 例）：faststart 单视频、视频+音频与额外盒子、`text`/`hint`
  handler 不算轨道、纯音频、`mdat` 声明长度越界（截断）、缺 `moov`、缺媒体数据、
  `trak` 无 handler、HTML/空文件/不存在、`ftyp` 非首个盒子、`size == 0` 末盒、
  64 位盒子长度及其越界。
- `AppErrorLanguageTest` 新增 1 例：图片 / 压缩包 / 新键三条校验失败 key 都必须解析到文案。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

`MediaIntegrityDeviceTest` **8/8 通过**（原有 5 例 + 新增 3 例）：

1. `aContainerThePlatformExtractorRefusesFallsBackToItsStructure`：
   断言平台 `trackCount == 0`（记录这一形态）、`Mp4Structure` 判为
   `Complete({VIDEO})`、`validateMediaFile` 通过；同时「视频 + 音频」仍报 `MISSING_TRACK`。
2. `theRefusedContainerStillPlaysInTheAppsOwnPlayer`：用应用同款 `ExoPlayer` prepare 该文件，
   在 15 s 内到达 `STATE_READY` 且 `duration > 0`——证明兜底接受的是能播放的文件。
3. `aTruncatedContainerThePlatformRefusesIsStillRejected`：同一份「平台读不出」文件截到 60%
   后仍报 `TRUNCATED`（兜底不会放过截断容器）。

原有 5 例（完整素材通过、合并缺音轨、截断 MP4 / WebM / 音频）继续通过，说明正向与截断路径未变。

结果 XML 归档：[device-api37/MediaIntegrityDeviceTest.xml](mp4-container-fallback-2026-11-20/device-api37/MediaIntegrityDeviceTest.xml)。

受影响的既有设备类另行复跑：`LibraryFileHealthDeviceTest`（6/6）、`DownloadRetryStatusDeviceTest`、
`GenericVideoDeviceTest`、`ImageArchiveDeviceTest`、`ConcurrentStorageDeviceTest` 通过。

### 端到端（真实推文）

同一台 API 37 模拟器、覆盖安装修复后的 APK，用应用内「解析 → 保存当前视频」重跑该推文：

- 修复前：`status=FAILED`、`failureKind=UNKNOWN`、`errorMessage=null`、`downloadedBytes=8192`。
- 修复后：`status=COMPLETED`、`downloadedBytes=348911`、落入
  `Android/data/com.videodown.douyin.debug/files/Movies/Douyin/…_3c675224.mp4`（348,911 B）。
- 修复前用「跳过校验」的临时构建确认过同一份字节能在应用播放页播放（00:02、720×1278、
  `c2.goldfish.h264.decoder`），设备用例 2 把这一条固化成了断言。
- 修复后同一台模拟器的任务列表与播放页：
  ![任务列表：原画 · 341 KB 已完成](mp4-container-fallback-2026-11-20/screens/tasks-completed.png)
  ![播放页：00:02 / 00:02，画面已渲染](mp4-container-fallback-2026-11-20/screens/player-playing.png)

## 补充：为什么这条动图不在「打包下载」里

X 的 `animated_gif` 在接口里就是一条 `video/mp4` 变体（`video.twimg.com/tweet_video/…mp4`，
无 GIF 原图可用），解析结果因此把它算作**视频条目**而不是图片：结果页默认停在这一条
（「当前 1/4」、画质「原画 341 KB」），底部是「保存当前视频」；「打包下载（3 张）」只收
`isImagePost`（全部变体均为 `MediaKind.IMAGE`）的 3 张图片。两者是不同入口，不是同一批内容漏了它。

![解析结果：动图作为视频条目，打包只含 3 张图片](mp4-container-fallback-2026-11-20/screens/parse-result-animated-gif.png)

## 未覆盖边界

1. **兜底档不做尾部检查**：平台解析器读不出的容器只做「盒子完整 + 轨道齐全 + 传输层
   `Content-Length` 对齐」，不验声明时长与尾部样本；结构不完整仍按 `TRUNCATED` 失败。
2. **兜底只认 ISO BMFF**：平台读不出的 WebM / 其它容器仍按 `UNSUPPORTED` 失败（现状不变）。
3. **只在本轮 API 37 模拟器验证**：该形态与平台版本相关（`media.extractor` 的实现细节），
   API 26/28/30 真机与平板/折叠屏未跑；用户真机上的这一条尚未在设备上复验。
4. **不是解析器修复**：没有改 X 的解析结果，也没有转码；保存的仍是 X 提供的原始 MP4 字节。
5. **本轮复跑中的两处环境失败与本改动无关但未做干净树对照**：
   `P2ExportFailureDeviceTest` 在 SAF 授权断言处失败（DocumentsUI 授权流程，未触碰本改动路径）；
   `BackgroundDownloadDeviceTest` 需要手工解锁/可观察通知的既有前提，在本模拟器上按前提跳过或超时。
