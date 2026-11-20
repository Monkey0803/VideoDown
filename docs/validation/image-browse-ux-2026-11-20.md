# 图片浏览与预览体验收敛验证（2026-11-20）

## 范围

本轮把「图片浏览/预览」审计里的 P0–P2 全部落到代码，不新增平台、不改下载协议：

- **P0-1 浏览上下文**：看图器进入时固定「已存」的搜索、平台/类型/收藏筛选与排序（深链与通知没有列表上下文，退回整库最新顺序），删除入口图后仍在同一份列表里继续。见 `LibraryQuery.matches/ordered`、`ImageViewerPolicy.imageViewerRecords(..., query)` 与 `MainActivity` 的图片路由。
- **P0-2 加载/失败态**：主图解码期间给延迟出现的加载指示；解码失败显示「这张图片无法显示」、文件名/大小/格式，以及「用其他应用打开 / 删除」，不再是一块纯黑。见 `ImageViewerDetails.ViewerImageLoading/ViewerImageFailure`。
- **P0-3 动图**：接入 `coil-gif` 3.3.0 并在 `VideoDownApp` 安装单例 ImageLoader（API 28+ 用 `AnimatedImageDecoder`，26/27 退回 `GifDecoder`），GIF/动态 WebP 在封面、缩略图与看图器里真正播放。见 `core/ImagePipeline.kt`。
- **P0-4 日期网格定位**：网格用 `rememberLazyGridState(initialFirstVisibleItemIndex = imageDateGridIndex(...))` 打开就滚到当前图片，列数随窗口宽度（手机 3 / 中等 4 / 大屏 6）。
- **P1-5 看图器操作对齐已存**：「更多」补齐收藏、重命名与分类、移入/移出保险箱、导出到文件夹，复用既有 `LibraryOrganizationViewModel`、`LibraryOrganizationDialog`、`FileExportViewModel`、`FileExportDialog`；移出当前分区后像删除一样前进或退出。
- **P1-6 批量选择**：已存多选支持长按区间选择（`selectionRange`，纯逻辑 + JVM 用例）；日期网格支持多选后批量保存到相册或删除，删除仍走确认框。
- **P1-7 同帖多图标识**：按原帖地址分组（缺原帖地址时用记录 id，避免把占位 awemeId 误并），看图器顶栏显示「本作品 N / M」，已存网格显示「N 张」角标。
- **P1-8 存前预览**：图片作品的结果页中心按钮改为应用内全屏预览：左右翻页、逐张勾选并直接写回批量选择，附操作提示；选择弹层里的图片封面改为 1:1，作者行补「打开原帖」。见 `presentation/home/ImagePostPreview.kt`。
- **P1-9 响应式与大字号**：底部动作行改为等宽 `weight` + 大字号两行标签；短横屏/分屏收起 64dp 缩略图带；日期网格列数随窗口。判定集中在 `ImageViewerLayoutPolicy`。
- **P2-10 主线程 I/O**：看图器与已存的全库 `File.isFile` 扫描移入 `remember`，只在记录/筛选变化时执行，点按、控制栏与幻灯片计时不再触发全库 stat。
- **P2-11 角标**：Live Photo 在已存封面、看图器缩略图与日期网格里有标识；保险箱图片在看图器顶栏带锁标识。
- **P2-12 Live Photo 点按**：单击只切换控制栏，停止只由顶栏按钮触发，避免「想隐藏控制栏却误停实况」。
- **P2-13 信息面板可行动**：可复制文件路径、GPS 坐标可「在地图中打开」；原图尺寸优先取已解码内容，未解码时读文件头（`ImageFileProbe`，只读前 512 KiB），不再显示「未知」。
- **P2-14 无障碍**：主图语义播报「第 N 张，共 M 张：标题」，并提供「放大 / 还原缩放」自定义动作（`ZoomableState.switchScale/reset`）。单张图片不再同时挂两份 contentDescription。

## 自动化结果

环境：Pixel 10 Pro AVD，Android 17 / API 37，arm64（`emulator-5554`）。

```text
./gradlew testDebugUnitTest
```

结果：**761 / 761 通过**，0 失败（最后一次干净的串行运行；并行的第一次运行中 `VideoSizeProbeTest` 因本地 HEAD 服务时序失败一次，单独重跑通过）。本轮新增/扩展：

- `ImageViewerPolicyTest`：已存筛选子集与排序进入看图器、深链无上下文仍能浏览私密图片、自定义排序、日期网格下标。
- `ImageViewerLayoutPolicyTest`：网格列数、短窗口收起缩略图、大字号两行标签。
- `LibrarySelectionPolicyTest`：区间选择（正向/反向/无锚点/未知目标）。
- `ImagePostGroupingTest`：同帖计数、作品内位置、单张无角标、缺原帖地址不误并。
- `ImageFileProbeTest`：从 PNG 文件头读尺寸、缺失/非图片返回 null。
- `LocalizationGuardTest` 继续通过：中英字符串 key 一一对应，未新增 Kotlin 中文文案。

设备用例（真实渲染 + 手势 + 截图）：

```text
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.ImageViewerDeviceTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.LibraryImageBrowseDeviceTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.ImagePostPreviewDeviceTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.CoilImageLoadingDeviceTest
```

结果：

- `ImageViewerDeviceTest` **19 / 19**：既有的沉浸切图、控制栏、删除前进、保险箱隔离、Live Photo、幻灯片、EXIF 全部通过；新增筛选上下文、损坏图片失败态、更多菜单能力、日期网格多选、日期网格定位到当前图（24 张跨 6 天）、实况单击语义、动图逐帧刷新、信息面板复制/地图、无障碍「第 N 张」语义、同帖位置。
- `LibraryImageBrowseDeviceTest` **2 / 2**：同帖「2 张」角标 + 动态照片标识；长按区间选择「已选 3 项」。
- `ImagePostPreviewDeviceTest` **1 / 1**：图片作品存前预览翻页、勾选后回写「已选 1 个」。
- `CoilImageLoadingDeviceTest` **2 / 2**：既有远程封面；新增 GIF 解码为动图 drawable（沿 Coil 的尺寸包装链取到 `AnimatedImageDrawable`）。

回归范围（与本轮改动直接相关的页面与路径，登录隔离用例按仓库约定未纳入）：

```text
ImageViewerDeviceTest                 19/19
LibraryImageBrowseDeviceTest            2/2
ImagePostPreviewDeviceTest              1/1
CoilImageLoadingDeviceTest              2/2
LibraryKindFilterDeviceTest            ✓
LibraryActionsDeviceTest               ✓
LibraryFeedColumnsDeviceTest           ✓
LibraryReorderDeviceTest               ✓
LibraryFileHealthUiDeviceTest          ✓
ImageGalleryDeviceTest                 ✓
GalleryExportReceiptDeviceTest         ✓
ResultSelectionDeviceTest              ✓
HomeResultBackDeviceTest               ✓
QualityCardsDeviceTest                 ✓
AdaptiveContentDeviceTest              ✓
AdaptiveLayoutDeviceTest               ✓
Material3NavigationDeviceTest          ✓
ImageLiveDeviceTest                    ✓
LegacyImageGalleryDeviceTest            ✓
```

以上 19 个类在本轮修改后的同一份安装包上逐类运行，全部 `BUILD SUCCESSFUL`。

```text
./gradlew assembleDebug     # 通过，videodown-arm64-v8a-debug.apk
./gradlew assembleRelease   # 通过，未配置发布密钥时按设计产出未签名 release
```

设备用例在连续反复运行时曾出现一次日期网格用例的空消息失败；单条重跑与随后两次全类重跑均通过，记录为 AVD 高负载下的时序波动，不作为功能回归。

## 验证中发现并修复

- **删除入口图片会掉进「文件不存在」**：首版把入口有效性放进候选计算，删除当前图片后入口 id 不再存在 → 列表变空。改为进入时固定分区与入口有效性，之后只按分区 + 列表上下文列图片，删除后照常前进。
- **同帖分组误并**：先按 `awemeId` 兜底会把旧记录/测试夹具里相同的占位 id 当成一个作品。改为按原帖地址分组，没有原帖地址时用记录 id（等价于不分组）。
- **日期网格与看图器两套底部工具条叠在一起**：打开日期网格时先收起控制栏，弹层下沿不再透出保存/分享/详情。
- **系统剪贴板浮层混进截图**（Android 13+）：用例间清理剪贴板，并在截图前等待系统浮层消失。
- **GIF 断言过严**：Coil 会给动图套一层 `ScaleDrawable`，直接判 `drawable is AnimatedImageDrawable` 会假失败；改为沿包装链判断。

## 证据

截图来自上述设备用例，保存在 `docs/validation/image-browse-ux-2026-11-20/`：

| 文件 | 内容 |
| :--- | :--- |
| `image-viewer-filtered.png` | 从「仅收藏」筛选进入，顶栏 `2 / 2` 且只剩收藏图片 |
| `image-viewer-failed.png` | 损坏 PNG 的失败态（原因、文件名/大小/格式、打开方式/删除） |
| `image-viewer-date-grid-select.png` | 日期网格多选「已选 2 张」及批量保存/删除 |
| `image-viewer-date-grid-located.png` | 24 张跨 6 天时打开网格即滚到当前图（最旧一张，高亮） |
| `image-viewer-live-photo.png` | Live Photo 自动播放与顶栏「停止」 |
| `image-viewer.png` | 常规看图器（基准） |
| `library-image-group.png` | 同帖「2 张」角标 + 动态照片标识 |
| `library-image-range-select.png` | 已存多选长按区间「已选 3 项」 |
| `image-post-preview.png` / `image-post-preview-selected.png` | 图片作品存前预览、勾选状态与操作提示 |

## 未覆盖边界

- 横屏、平板与折叠屏只由 `ImageViewerLayoutPolicyTest` 与代码路径覆盖；本轮 AVD 固定在 1280×2856 竖屏，没有对「短横屏收起缩略图带」「大屏 6 列日期网格」做设备端视觉断言。
- HEIC/AVIF 仍取决于系统解码器（API 26/27 无 HEIF 支持）。失败时会进入本轮新增的错误态，但没有用真实 HEIC/AVIF 样本验证。
- GIF 动画用例用连续截图的像素差判定「在动」，不是逐帧时序断言；极慢帧率或单帧 GIF 可能判为静态。
- 日期网格的批量保存/删除走真实 MediaStore 与批量任务，但只验证了选择与入口，没有做上百张的耗时与失败注入。
- TalkBack 自定义动作只由 Compose 语义断言覆盖，未在真机 TalkBack 下人工走查。
- 未运行完整设备套件：`XLoginSessionDeviceTest`、`XPostRecoveryDeviceTest`、`BilibiliLoginDeviceTest` 等会清空模拟器网页登录态，按仓库约定不纳入日常模拟器回归。
