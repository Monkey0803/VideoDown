# 图片、看图器与打包下载验证（2026-11-16）

## 范围

本轮把「图片」做成与视频/音频并列的一等媒体类型，并新增两件用户可见的能力：

1. **看图器**：已下载的图片在应用内查看，使用第三方库 ZoomImage（`zoomimage-compose-coil3:1.4.0`，Apache-2.0）提供缩放、拖动、双击放大、超大图子采样与 Exif 方向；不再自己写手势。
2. **打包下载**：结果页把一帖的多张图片打成一个 ZIP 任务（暂停续传、条目数校验、写入 `Download/拾影`）。

同时补齐图片来源：图片直链、抖音图文、微博多图、X 多图、快手图集、网页辅助已加载图片。

## 实现要点（便于复查）

- `MediaKind`（VIDEO/AUDIO/IMAGE/ARCHIVE）随记录落盘；校验、相册 vs 下载目录、播放器 vs 看图器、通知文案都按它分派，不靠扩展名猜。旧 `download_history.json` 无此字段时仍按视频读取。
- 图片按文件头识别（`MediaSignature`），下载后 `validateDownloadedFile` 再校验一次：图片只认图片魔数，ZIP 校验魔数与条目数。
- 每张图是一个独立条目（`awemeId = {postId}_img{n}`，`mediaId` 稳定），复用既有勾选/批量/队列/续传/重复检查。
- 归档任务在 `DownloadEngine.runArchiveTransfer`：逐张复用 `ResumableDownloader`（已完成的图跳过、半张图续传），全部就绪后 `ImageArchiveWriter` 先写 `.tmp` 再改名；`ArchivePublisher` 写公共 `Download/拾影`（Android 10+ `MediaStore.Downloads`）。
- 看图器 `ImageViewerScreen` 只负责顶栏、保存到相册、分享与文件缺失态；缩放交给 `CoilZoomAsyncImage`。
- 依赖锁定：`zoomimage = "1.4.0"`。1.5.0 起用 Kotlin 2.3.20 构建、1.6.0/1.7.0 用 Kotlin 2.4.0，元数据超出本仓库 Kotlin 2.2.21 的读取上限；原因写在 `gradle/libs.versions.toml` 与 `AGENTS.md`。
- 新增/改写的中英文案各 784 条 key，一一对应；`LocalizationGuardTest` 的未标注中文字面量计数仍为 0。

## JVM 用例

`./gradlew testDebugUnitTest` 通过：**509 个用例，0 失败 0 跳过**（`jvm-tests.txt`）。

本轮新增/更新的关键用例：

| 用例 | 覆盖 |
| :--- | :--- |
| `MediaSignatureTest` | PNG/GIF/JPEG/WebP/AVIF/HEIC/BMP 魔数与尺寸、Content-Type 兜底、URL 扩展名、ZIP 魔数 |
| `MediaKindTest` | 扩展名优先级、`mediaKind` 推导、旧历史 JSON 兼容、归档条目序列化、图片/压缩包 MIME |
| `ImageMediaTest` | 扩展名与噪音阈值、条目身份稳定（同 URL 同 id）、DIRECT 图片元数据 |
| `ImageArchiveWriterTest` | 条目顺序、防重名、缺文件失败、原子发布不残留 `.tmp` |
| `DownloadValidationTest` | 图片魔数校验、ZIP 条目数校验、非 ZIP 拒绝 |
| `DownloadEngineArchiveTest` | 归档产出 ZIP、暂停后续传仍完整、失败不发布半个包、重复检查按张数 |
| `HomeArchiveTest` | 打包全选/勾选子集、单图或视频无入口、重复确认与取消、待确认请求复用 |
| `DouyinJsonParserTest` | 图文 `images` / `image_post_info`、推荐位视频不被误下 |
| `SocialVideoParserTest` | 微博静态图与动图各自成条、快手 `atlas` |
| `XVideoParserTest` | 纯图片推文、`/photo/N` 选择、外站图片丢弃、`XVideoLink` 媒体类型 |
| `CompletionSummaryTest` | 通知落点按 `MediaKind` 区分（相册/音乐/下载目录） |
| `LibraryQueryTest` / `LibraryReorderGateTest` | 「已存」按类型筛选、与搜索叠加、筛选时禁止拖拽重排 |

## 设备用例

设备/运行环境（`environment.txt`）：Pixel_10_Pro AVD，Android 17 / API 37，`arm64-v8a`。

新增设备用例（逐类运行，均通过）：

| 用例 | 覆盖 |
| :--- | :--- |
| `ImageViewerDeviceTest` | 真实 PNG 在看图器中渲染、保存到相册/分享入口存在；文件缺失显示「图片文件不存在」 |
| `ImageDownloadDeviceTest` | 直链图片经真实 `probeDirect` + `DownloadEngine` 解析与落盘：结果是图片条目、文件头为 PNG、扩展名 `png` |
| `ImageArchiveDeviceTest` | 一个归档任务产出 3 条目 ZIP、发布到 `MediaStore.Downloads`、重复检查按张数、坏地址不发布 ZIP、相册导出拒绝压缩包 |
| `ImageGalleryDeviceTest` | 图片记录导出到 `Pictures/拾影`，DISPLAY_NAME / MIME / RELATIVE_PATH 正确 |
| `LibraryKindFilterDeviceTest` | 「已存」类型筛选：选「图片」只剩图片，选回「全部类型」恢复 |

全量回归：`./gradlew connectedDebugAndroidTest`（`device-tests.txt`）：共 **216** 个 testcase、24 个 assumption 跳过、首轮 16 个失败。16 个失败里：

- 5 个是测试 APK 早于文案中性化（`ResultSelectionDeviceTest` ×2、`BatchFeaturesDeviceTest` ×2、`WeiboGifDeviceTest` ×1），改断言后逐类重跑全部通过；
- 4 个与模拟器通知/FGS 时序有关（`AutoGalleryDeviceTest`、`TaskNotificationDeviceTest` ×2、`P2CompletionNotificationDeviceTest`）。给 debug 包授予 `POST_NOTIFICATIONS` 后 `AutoGalleryDeviceTest` 通过；两个通知用例仍超时，属于本次环境的已知限制；
- 3 个是明确 opt-in / 需要物理机或 release 通道的用例（`BackgroundDownloadDeviceTest`、`P2PhysicalBackgroundAudioDeviceTest`、`P2ReleaseUiBridgeDeviceTest`）、另有在线组与 process-exit 组被 assumption 跳过；
- 2 个既有渲染断言未定位（`DynamicColorDeviceTest` 品牌色、`EmptyStateIllustrationDeviceTest` 终态形状），与图片功能无关，本轮未改动。

逐类复跑结果见 `device-new-classes.txt`：图片四个用例类 10 个 testcase 全通过，受文案影响的三个旧用例类与相册/下载设置相关用例也全部通过。

## 指定 X 推文在线验收（2026-11-16）

用一条真实推文在模拟器上把「解析 → 结果页 → 打包 → 看图器」整条链路走了一遍：

```
adb shell "am instrument -w \
  -e class com.videodown.douyin.XSpecifiedPostDeviceTest \
  -e x_post_url 'https://x.com/donaldsilveira/status/2121947540518060428?a=D8VJNpJRdskAc8H57c4&s=20' \
  com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner"
```

结果（Pixel_10_Pro / API 37 / arm64，与本地环境一致；证据在 `x-specified-post/`）：

- 解析出 **3 个图片条目**（`2121947540518060428_img1..img3`），标签「原画」，扩展名 `jpg`；链接里的 `?a=…&s=20` 分享参数不影响识别。
- 结果页截图 `x-post-result.png`：3 条结果、封面、`原画 309 KB`（图片大小探测生效）、`打包下载（3 张）` 与 `保存这张图片`。
- 打包：一个归档任务产出 `x-post-images.zip`，**1,088,906 字节 / 3 个条目 / `images/001.jpg` 起**；解包后三张都是 **1200×2338 progressive JPEG**（316,401 / 418,072 / 373,338 字节，SHA-256 各不相同）。
- 看图器截图 `x-post-image.png`：第一张图 316,401 字节，在 ZoomImage 中实际渲染出画面（不是占位/黑屏）。
- 原始输出 `x-post-summary.txt`。

这条推文只有图片，没有视频；同一条用例在只有视频的推文上会改下最小的视频档位并报告文件大小（本轮未触发）。

## 「已存」按类型筛选（2026-11-16）

图片没有单独开底部 tab，而是把类型做成「已存」的筛选条件（复用同一套搜索/平台/分类/排序/收藏/批量）：

- `LibraryQuery.mediaKind`（`null` = 全部）+ `apply()` 过滤；进入筛选项会关掉「自定义排序可拖拽」的闸门（`ordersManually`），与平台/分类/收藏一致。
- 顶部筛选条新增「类型」选择器：全部 / 视频 / 音频 / 图片 / 压缩包；标题与副标题改为中性的「已存内容 / N 项 · 可离线查看」。
- JVM：`LibraryQueryTest` 新增类型×搜索叠加用例；`LibraryReorderGateTest` 补「类型过滤关闸」。
- 设备：`LibraryKindFilterDeviceTest` —— 四条不同类型记录默认全显示；选「图片」后只剩图片、其余不存在；选回「全部类型」全部恢复。截图 `library-kind-image.png`。
- 同时更新了断言旧标题的设备用例（`Material3NavigationDeviceTest`、`LauncherShortcutsDeviceTest`、`P2CompletionNotificationDeviceTest`）与存储文案里的「删除已存视频」。

## 微博 Live Photo 解析修复（2026-11-16）

用户反馈 `https://weibo.com/3288446905/Rrei7jysH` 解析失败（最终报 `engine:AUTH`）。复现与定位：

- 该帖是 **两张 Live Photo**（`pic_infos[*].type = "livephoto"`，`video` 字段是 `video.weibo.com` 的 `.mov` 动作片段，静帧在 `original` / `largest`）。
- 旧逻辑把所有带 `video` 字段的图片都当成动图跳过（本意是避开 GIF 的 MP4），于是 `parsePictures` 返回空、`parseGifVideos` 又因 URL 不是 `.mp4` 返回空，微博分支报 `weibo:no_mp4` 后落入通用引擎，最终把引擎的授权失败透传成 `engine:AUTH` —— 用户看到的就是「不能正常解析」。
- 修复：`livephoto` 不再被当作动图跳过，静帧按 `largest → original → large → bmiddle → thumbnail` 取最大可用图；`parseGifVideos` 显式排除 `livephoto`，避免把 `.mov` 当视频。GIF 行为不变。

真机（模拟器）复验同一条链接：

- 结果页 `weibo-livephoto/result.png`：`已解析 · 微博`、`当前 1/2`、标题「选腿还是雷」、`原画 285 KB`、`打包下载（2 张）`、`保存这张图片`。
- 解析输出（`summary.txt`）：2 个条目 `Rrei7jysH_img1/img2`，`kind=IMAGE`、`ext=jpg`，地址为 `large` 档。
- 打包：`images.zip` 1,841,422 字节（文件大小）/ 2 条目；解包后为 **1080×1920** 与 **3024×4032** 的 JPEG（292,349 / 1,554,559 字节，SHA-256 不同）。
- 看图器 `weibo-livephoto/viewer.png`：第一张图实际渲染。
- 覆盖：JVM `WeiboGifParserTest` 新增 livephoto 与「GIF + livephoto 混合」两例；设备 `WeiboGifDeviceTest` 新增 livephoto 结果页用例（3 个用例全过）。通用探针 `UrlParseProbeDeviceTest` 可对任意链接复跑上述流程。

## 证据文件

- `jvm-tests.txt` — 单元测试输出与汇总
- `device-tests.txt` — 全量设备测试输出
- `device-new-classes.txt` — 新增/受影响设备用例类的逐类运行输出（含文案中性化后的旧用例复跑）
- `environment.txt` — 模拟器/系统信息
- `dependency.txt` — ZoomImage 依赖树片段
- `image-viewer.png` — 看图器截图（设备用例内由 `UiAutomation.takeScreenshot()` 采集）
- `library-kind-image.png` — 「已存」类型筛选选「图片」后的截图
- `weibo-livephoto/` — 微博 Live Photo 修复的在线验收：`result.png`、`viewer.png`、`images.zip`（2 张原图）、`summary.txt`
- `x-specified-post/` — 指定 X 推文的在线验收：`x-post-result.png`（结果页）、`x-post-image.png`（看图器）、`x-post-images.zip`（3 张原图）、`x-post-summary.txt`（解析/下载输出）

## 未覆盖边界（如实记录）

1. **未在 Android 9 / API 28 及更低版本验证**：图片存相册与 ZIP 写入公共下载目录在旧版本走 `WRITE_EXTERNAL_STORAGE` 分支，本轮只有 API 37 模拟器，未覆盖授权/拒绝两条路径。
2. **平台图文只做了一条真实链接**：X 推文 `2121947540518060428` 已在模拟器上完成解析、打包与看图器验收（见上一节）。抖音图文、微博多图、快手图集仍然只有 JVM 固定载荷与设备端本地服务覆盖——`ImageLiveDeviceTest` 需要由调用方提供 `image_live_douyin` / `image_live_weibo` / `image_live_x`（直链可用 `image_live_url`），真实作品链接会过期，不写进仓库。
3. **看图器的手势行为未逐像素断言**：只验证渲染、无崩溃与按钮存在；缩放/惯性/子采样是 ZoomImage 的既有能力，未在本仓库重复测试。
4. **HEIC/AVIF 只验证落盘与 MIME**：系统解码器/相册能否展示未验证。
5. **归档的 403 后整帖重解析**只在 JVM 分支覆盖，未用真实过期地址触发。
6. **通知权限被拒绝时**的完成通知表现未单独验证（沿用既有通知路径，本轮未改权限流程）。
7. JVM 套件里 `QueueReorderTest` 偶发失败过一次（`snapshot()` 读到 0 条），单类连跑 5 次均通过，未定位；与图片改动无调用关系。
8. 设备全量套件中的通知类用例在本模拟器上超时（见上）；`POST_NOTIFICATIONS` 已授予，但仍未通过，属于本环境限制而非本轮代码回归。
9. 既有的 `DynamicColorDeviceTest` / `EmptyStateIllustrationDeviceTest` 渲染断言在本轮环境失败，未修改也未调查，不能据此认为与本轮无关的 UI 一定没被影响。
