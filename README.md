# 拾影 — 视频下载器

使用 Kotlin + Jetpack Compose 实现的 Android 应用，支持解析抖音、微博、快手、X（Twitter）分享链接及通用 HTTP/HTTPS 视频网址，选择分辨率下载视频、
记录下载历史、打开原视频与作者主页并播放已下载的视频。界面使用 Compose Material 3，在「我的 → 外观模式」中可选择跟随系统、浅色或深色，确认后立即生效并在重启后保留。支持 Android 12+ 壁纸动态配色；较旧系统使用橙色品牌主题。颜色、文字样式与圆角统一由 `MaterialTheme` 管理。

主导航分为「解析」「任务」「已存」「我的」：解析页支持粘贴分享文案和选择画质；任务页显示实时进度及暂停、续传、删除操作；已存页展示文件仍存在的已完成视频；「我的」可设置默认画质、仅在 Wi-Fi 下下载和完成时通知，偏好在重启后保留。

开启「仅在 Wi-Fi 下下载」后，非 Wi-Fi 网络下的新建、继续和重试任务会暂停；下载期间离开 Wi-Fi 或在移动网络上开启该限制，也会停止传输并保留断点。连接 Wi-Fi 后需点击「继续」。开启「完成时通知」会按需申请通知权限；拒绝授权或关闭系统通知渠道时，页面会提示前往系统设置。每次成功完成下载后发送独立通知，点击进入「已存」；关闭该偏好不影响下载期间的前台服务进度通知。

## 重复提醒、批量操作与搜索

- 下载前按平台、媒体 ID 和所选画质/格式检查历史。已有任务或已保存文件时显示提醒，可查看已有视频/任务，或明确选择「仍然下载」。文件已丢失的完成记录不会阻止重新下载，不同画质可分别保存。
- 多视频解析结果顶部显示当前视频和已选数量；点击「查看全部」打开自适应网格。点击封面切换预览，勾选框独立控制批量选择，支持全选/取消全选。默认不勾选，底部按钮保存当前视频；勾选后按各视频保留的画质保存所选项目。存在重复项时，可跳过重复项并下载其余视频，或仍然下载全部所选视频。封面高度随窗口调整，长标题默认两行，可展开查看全文。验证截图见 [结果页布局验证](docs/validation/result-selection-2026-09-30.md)。
- 任务页支持多选暂停、继续、删除；全部暂停与全部继续可分别使用。批量控制只处理符合当前状态的项目，批量删除先确认。
- 已存页可按标题/作者搜索、按平台筛选，并按最新保存、最早保存、文件大小排序。两种布局均支持多选删除和保存到相册。「全选当前结果」仅选择当前可见的筛选结果，改变筛选条件会清除选择。
- 批量操作逐项执行并显示进度和成功、跳过、失败数量。单项失败不会中断其余项目；可查看具体失败视频和原因，保存/删除完成后仅保留失败项选中，便于重试；删除只影响本地下载及记录，不删除相册副本。Android 8/9 保存到相册仍需存储权限。

## 存储、队列与失败恢复

- 「我的 → 存储空间」显示下载视频、临时文件、可用空间和可清理残留。清理需要确认，并在执行前重新检查文件指纹和任务引用；保留有效视频、相册副本，以及暂停、失败或进行中任务引用的断点和合并片段。清理对象仅限未引用的下载临时文件、引擎任务目录和文件已丢失的完成记录；已存视频请在「已存」中选择删除。
- 下载开始前检查可用空间，直链收到响应后根据实际大小再次检查。预留 32 MiB 工作空间；已知大小的引擎任务还按下载与合并两份空间估算。大小未知或多任务同时下载时，此检查不能保证全程空间充足，后续空间不足会保留恢复提示。
- 「我的 → 同时下载数量」可设置 1–5 个任务，默认 3，重启后保留。提高数量立即开放名额；降低数量时正在下载、合并或校验的任务继续，新任务等待名额。
- 等待任务显示排队位置，并支持前移、后移和优先下载。仅调整等待任务，顺序保存到历史；重新开始或单独继续的任务加入队尾。进程退出后任务恢复为暂停，点击「全部继续」按保存顺序加入队列。
- 失败提示区分网络、登录/验证、访问受限、链接过期、存储空间、文件写入、画质变化、网站限流和引擎故障。按原因提供重试、重新解析原网址、打开网页登录、管理空间或检查下载设置。HTTP 403 保留“过期或访问受限”的含义，避免直接当作需要登录。
- 网页辅助与重新解析使用原始页面网址；旧记录缺少原网址时明确提示重新粘贴，并禁用对应操作。DRM 和直播显示不支持，不提供反复重试入口。

详细验证和运行边界见 [P1 验证记录](docs/validation/p1-features-2026-09-30.md)。

## 批量链接、音频与文件整理（P2）

- 「解析 → 批量导入多个链接」接受分享文案或多条网址，去重后逐条解析，最多 50 条。每个页面中的媒体可以独立选择画质或音轨；支持全选、停止、重试未完成项，部分失败不会丢失成功结果。加入队列前再次检查重复，可跳过已有项或明确选择仍然下载。
- 通用引擎报告独立音轨时，解析结果显示「单独保存音轨」，可单独下载为 M4A。未提供独立音轨时，可以先保存视频，再在影像库长按选择「提取音频」。本地提取不需要联网；AAC 优先保留原编码采样，其他编码转 AAC，保留原视频。音频任务沿用队列及暂停、继续、重试、取消；无音轨的视频显示明确原因。
- 音频可在应用内播放、通过系统分享、保存到系统音乐目录或自定义文件夹。音频不会进入视频相册；后台播放能力见下方 P3。
- 影像库长按可收藏、实际重命名本地文件并设置分类；支持收藏及分类筛选。分类为库内标签，不移动文件。重命名保留扩展名，写入失败时回滚，并在启动时恢复中断的重命名事务。
- 单个文件和多选文件均可「导出到文件夹」，使用 Android 系统目录选择器取得并保留授权，记住上次目录。同名副本增加序号，保留原文件；逐项显示结果，失败项可以单独重试，停止时清理当前未完成副本。
- 导出界面旋转保留本次任务；进程重建后提示上次中断并尝试清理未完成副本。无法访问的旧目录保留待清理记录，不阻止导出到新目录；整批导出不承诺在进程退出后自动继续。

验证记录见 [P2 验证记录](docs/validation/p2-features-2026-09-30.md)。

## 续播、播放设置与后台播放（P3）

- 视频和音频记住播放位置，再次打开继续播放；播放结束后从头开始。进度按文件大小和修改时间校验，更换文件后不沿用旧进度。
- 播放页可选择 0.5×、0.75×、1×、1.25×、1.5×、1.75×、2×，并开启单曲循环；音频与视频分别记住设置。横屏倍速列表可滚动。
- 音频由媒体前台服务播放，返回其它页面、Home 或锁屏时仍继续。系统媒体通知显示标题和播放控制，点击返回当前音频；通过通知暂停后返回页面保持暂停和进度。支持系统媒体键、耳机播放键和音频焦点处理，拔下耳机时暂停。打开视频会暂停后台音频。
- 视频播放页提供「画中画」；支持的设备在播放中按 Home 自动进入小窗，暂停时不自动进入。小窗隐藏应用内操作，系统小窗可暂停、继续、展开及关闭。返回保持播放器和进度，关闭释放视频播放器。普通后台视频暂停。
- 后台服务不保证强制停止应用后继续播放；进度和音频/视频偏好保存在本地，重新启动后可继续。

实现、验证证据和真机边界见 [P3 验证记录](docs/validation/p3-features-2026-09-30.md)。

## 通用网址与网页辅助

粘贴 HTTP/HTTPS 网址或分享文案即可尝试解析。已知平台优先使用原有解析器，失败后进入 yt-dlp；X 推文即使原生解析成功，也会补充通用引擎找到的媒体和画质。自动解析失败时点击「打开网页查找视频」，在内置网页中登录或播放，再选择检测到的媒体资源，也可使用当前网页登录态重新解析。

MP4/WebM 直链继续断点下载；HLS、DASH 和分离音视频通过本地 Python、yt-dlp 与 FFmpeg 下载、合并并校验。任务页区分下载、合并和文件校验；只有校验完成才进入「已存」。不同格式按实际扩展名分享，MKV 等不适合相册的容器会提示使用文件分享。

「我的」显示实际引擎版本，并可清除网页登录态。登录态只保存在本机；默认匿名解析，不自动读取其他浏览器账户。引擎随 APK 固定更新，不依赖解析服务器。

此功能面向点播视频，不保证所有网站可用；不支持 DRM、直播录制或整站采集。部分网站会限制 WebView 登录。详细架构、依赖校验和、16 KB 原生库修复与许可来源见 [通用视频引擎说明](docs/research/universal-video-engine.md)，当前通过及未验证项见 [实施验证记录](docs/validation/universal-video-2026-09-29.md)。

## 功能对照

| # | 需求 | 实现位置 |
|---|------|----------|
| 1 | 解析抖音、微博、快手、X 链接、下载视频、**支持选择分辨率** | `data/repository/MultiPlatformVideoRepository.kt`（平台路由）+ `data/remote/`（链接解析）+ `data/download/`（下载）+ `presentation/home/HomeScreen.kt`（分辨率选择 UI） |
| 2 | 记录下载历史 | `data/local/DownloadHistoryStore.kt` + `presentation/history/` |
| 3 | 打开原视频与对应作者主页 | `core/DouyinIntents.kt`（抖音优先唤起客户端，其余平台打开对应网页） |
| 4 | 断点续传 | `data/download/ResumableDownloader.kt`（HTTP Range + `If-Range`）+ `DownloadEngine`（偏移量落盘） |
| 5 | 播放视频、横屏全屏 | `presentation/player/PlayerScreen.kt`（标题栏与播放器内均可切换横屏；按视频比例显示，切换保留进度和暂停状态，返回键先退出全屏） |
| 6 | 画质显示文件大小 | `data/remote/VideoSizeProbe.kt`（后台读取 CDN 响应头；不可获取时明确显示大小未知） |
| 7 | 转存到相册 | `data/gallery/GalleryExporter.kt`（通过 MediaStore 复制已完成视频） |
| 8 | 已存视频支持瀑布流与列表切换，并记住布局选择 | `presentation/library/LibraryScreen.kt` + `core/AppPreferences.kt`（点击「我的影像库」右侧按钮切换；瀑布流按封面比例排列，列表显示封面、标题、作者和保存时间） |
| 9 | 已存视频长按常用操作 | `presentation/library/LibraryActions.kt`（两种布局均可长按播放、分享、保存到相册、删除；删除需再次确认，保存期间防止重复操作） |
| 10 | 输入页键盘避让与自动收起 | `presentation/MainActivity.kt` + `presentation/home/HomeScreen.kt`（输入框和解析按钮避让键盘；解析、完成键、粘贴、点击空白和离开页面时收起，保留输入内容） |
| 11 | X 推文多视频选择 | `domain/model/ParsedVideos.kt` + `presentation/home/`（每个视频独立选择画质；保留 `/video/N` 指定的媒体索引） |
| 12 | 微博动图保存为 MP4 | `data/remote/WeiboJsonParser.kt`（读取微博提供的动图 MP4；多张动图独立选择，支持转发微博，保存后可播放和分享） |
| 13 | 通用网址、网页辅助、HLS / DASH / 分轨合并 | `data/generic/` + `presentation/browser/` + APK 内置 yt-dlp / FFmpeg |
| 14 | 存储占用、残留清理与空间检查 | `data/local/DownloadStorageRepository.kt` + `data/download/StorageSpaceGuard.kt` + `presentation/settings/StorageDialog.kt` |
| 15 | 并发设置与等待任务排序 | `data/download/DownloadQueueScheduler.kt` + `core/AppPreferences.kt` + `presentation/history/` |
| 16 | 按失败原因恢复与页面跳转 | `domain/model/DownloadFailure.kt` + `presentation/home/` + `presentation/history/` + `presentation/MainActivity.kt` |

## 环境要求

- JDK 17 或以上（Android Studio 自带的 JBR 可直接使用）
- Android SDK：`compileSdk 36`、`build-tools 36.1.0`、`platform android-36`
- 在 `local.properties` 中指向本机 SDK：

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

## 构建与测试

```bash
# 单元测试（覆盖四平台解析、匿名访问与备用接口、多视频切换、断点续传、历史兼容、Wi-Fi 限制及完成回调）
./gradlew testDebugUnitTest

# 设备接口测试（偏好保存、系统网络判断、通知开关；通知发送用例需允许应用通知）
./gradlew connectedDebugAndroidTest

# 可选联网测试：用两个公开示例执行实际解析、完整下载及视频元数据校验
# 需在线 Android 设备和网络，会下载约 81 MiB；默认设备测试跳过此组
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.SocialVideoLiveDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_platforms=true

# X 真实 UI / 联网测试：解析、1080P 完整下载、主接口故障时备用接口、多视频切换
# 下载约 17 MiB；省略 live_x 时仅执行离线 UI 用例
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XVideoDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_x=true

# X 通用引擎补全：真实推文的分轨画质、下载合并及音视频轨道
./gradlew connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XVideoDeviceTest#liveUnifiedXAddsEngineFormats' \
  -Pandroid.testInstrumentationRunnerArguments.live_x_engine=true \
  -Pandroid.testInstrumentationRunnerArguments.live_x_engine_download=true

# 微博 GIF 真实 UI / 联网测试：解析示例、完整保存 MP4、应用内播放，以及多动图选择
# 下载约 865 KiB；省略 live_weibo_gif 时仅执行离线 UI 用例
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.WeiboGifDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_weibo_gif=true

# 生成 arm64 Debug APK（默认目标架构就是 arm64-v8a）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk

# 生成 arm64 签名 Release APK
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/videodown-arm64-v8a-release.apk

# 需要通用包（含 x86_64，给模拟器用）时显式指定
./gradlew assembleRelease -Pabi=universal
# 产物：app/build/outputs/apk/release/videodown-universal-release.apk

# 安装到已连接设备
./gradlew installDebug
```

### arm64 打包

目标架构通过 `-Pabi=<值>` 控制，**默认 `arm64`**，所以 `assembleDebug` / `assembleRelease`
产出的就是 arm64 APK。构建脚本用 `ndk.abiFilters` 限制打包进 APK 的 native 库。

| `-Pabi=` | 打包的 ABI | 适用 |
|---|---|---|
| `arm64`（默认，等价 `arm64-v8a`） | `arm64-v8a` | 2017 年后几乎所有真机 |
| `universal`（等价 `all`） | `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` | 兜底，含 x86_64 模拟器 |
| `armeabi-v7a` / `x86` / `x86_64` | 对应单一 ABI | 特殊设备 |

产物文件名会带上架构标记（`base.archivesName`），例如
`videodown-arm64-v8a-release.apk`、`videodown-universal-release.apk`。

接入通用引擎后，APK 包含 Python、QuickJS 和 FFmpeg，体积比原来增大。arm64 与 x86_64 构建分别验证，`universal` 会打包所有 ABI。组件版本固定，不需要用户另外安装运行环境。

> ⚠️ 使用 `abiFilters` 意味着**一次构建只产出一个架构的 APK**：先构建 `universal` 再构建 `arm64`
> 时，AGP 会清掉上一次的产物。需要两种包就分两次构建。
>
> 这里刻意**没有**使用 ABI splits：开启 splits 后，把 `testDebugUnitTest` 与
> `assembleDebug`/`assembleRelease` 放在同一条 Gradle 命令里执行时，会在 AGP 的
> `PackageAndroidArtifact$IncrementalSplitterRunnable` 里随机失败（本机实测 3 次中失败 2 次）。
> 改用 `abiFilters` 后同样的命令连续 4 次全部成功。

### 签名

- `keystore/debug.keystore`：签入仓库的调试签名（口令均为 `android`），让 `assembleDebug` 在任何
  机器与 CI 上都能复现。
- Release 签名从仓库根目录**被 git 忽略**的 `keystore.properties` 读取，模板见
  `keystore.properties.example`。若该文件不存在，`assembleRelease` 会产出未签名包。

```properties
# keystore.properties（不要提交）
storeFile=keystore/release.keystore
storePassword=<your-password>
keyAlias=<your-alias>
keyPassword=<your-password>
```

> ⚠️ 本仓库**没有提交任何 release 密钥**：`keystore/release.keystore` 与 `keystore.properties`
> 都已加入 `.gitignore`。正式发布前请生成你自己的密钥：

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -storetype PKCS12 \
  -alias <your-alias> -keyalg RSA -keysize 2048 -validity 10000
```


## 架构

单模块 Clean Architecture 分层，依赖方向严格单向：

```
presentation  ──▶ domain ◀── data        core 被所有层使用
   (Compose)      (纯 Kotlin)   (网络/磁盘/下载)
```

```
app/src/main/java/com/videodown/douyin/
├── core/                     # 结果类型、格式化、Intent 跳转、ServiceLocator（手写 DI）
├── domain/
│   ├── model/                # DouyinVideo / VideoVariant / VideoPlatform / DownloadRecord（纯 Kotlin，可序列化）
│   └── repository/           # VideoRepository / DouyinRepository / Downloader / DownloadHistoryRepository 接口
├── data/
│   ├── remote/               # 平台链接识别、抖音/微博/快手解析器、匿名网页访问
│   ├── download/             # ResumableDownloader、DownloadEngine、DownloadPaths、DownloadService
│   ├── local/                # DownloadHistoryStore（JSON 原子写入）
│   └── repository/           # 接口实现
└── presentation/
    ├── home/                 # 解析 + 分辨率选择 + 下载进度
    ├── history/              # 历史列表、继续/暂停/重试/删除
    ├── player/               # ExoPlayer 播放
    └── theme/
```

`domain` 不依赖任何 Android/框架类，可被纯 JVM 单元测试直接覆盖。

## 抖音解析策略

抖音 Web API 依赖会失效的请求签名（`a_bogus` / `X-Bogus`），因此解析按**可靠性从高到低**逐级降级：

1. **匿名分享页（首选）**：`GET https://www.iesdouyin.com/share/video/{aweme_id}/`
   该页面为 SSR 渲染，正文内嵌 `window._ROUTER_DATA` JSON，无需签名即可拿到完整视频信息。
2. **签名 Web 接口**：`/aweme/v1/web/aweme/detail/`（在具备有效 Cookie/签名时可用）。
3. **旧版 v2 接口**：`/web/api/v2/aweme/iteminfo/`（仅作最后兜底）。

短链 `v.douyin.com/xxxx` 通过 HTTP 302 展开后再提取 19 位 `aweme_id`。

解析 JSON 时不写死字段路径，而是**广度优先搜寻找第一个「像 aweme」的对象**（同时含有 `aweme_id`
与 `video.play_addr`）。抖音每年会数次调整 `_ROUTER_DATA` 的键名（`video_(id)/page`、`note_(id)/page`、
`videoInfoRes`、`aweme_detail` 等），这种写法可以在键名变动后继续工作。

### 分辨率来源与优先级

1. `video.bit_rate[]` —— 官方逐档码率，作为权威分辨率列表；
2. `video.play_addr` —— 默认（通常最高画质、无水印）；
3. `video.play_addr_h264` —— H.264 转码，兼容性更好；
4. `video.download_addr` —— 带水印，排序最后；
5. `ratio=` 改写派生档位 —— 当接口只给出单一码率时补充 720P/540P/360P。

分辨率标签按**短边**命名（竖屏 1080×1920 → `1080P`），与抖音自身的 `ratio` 字段一致。
最终按标签去重，界面上每一档只出现一次。

## 微博与快手解析

- 微博支持正文链接（`weibo.com/{uid}/{id}`、`m.weibo.cn/status/{id}`、`m.weibo.cn/detail/{id}`）及可展开的 `t.cn` 短链。通过匿名访客流程获取临时 Cookie，再读取正文视频信息，不读取浏览器或账号 Cookie。画质来自 `playback_list`，过滤图片预览资源。
- 快手支持 `kuaishou.com/f/...` 分享短链、`/short-video/{id}` 和移动端 `/fw/photo/{id}`。跟随分享页跳转并解析内嵌 JSON，按目标视频 ID 匹配，避免下载推荐列表中的其他视频。
- 两个平台复用画质选择、文件大小、下载/续传、历史、播放和转存相册流程；下载请求及续传保留各平台的 Referer。已有抖音历史记录仍可读取。
- 这些专用解析器提供 MP4 快速路径；未返回视频时会进入通用引擎。需要登录或验证时可打开网页辅助。直链地址失效会尝试重新解析一次，能够确认所选媒体和格式时刷新链接；断点不兼容则重下，身份无法确认时提示重新选择。

2026-09-29 在 Android 模拟器中完成两个指定链接的实际网络下载和视频轨道校验：微博 `RklMe8iOM` 提供 1080P/720P/540P，实测下载 540P（8,754,435 字节、约 67 秒）；快手 `X3uQi2UzJmqN2fq` 提供 720P，实测下载 75,746,030 字节、约 261 秒。此项为设备端接口测试，不代表手动点击 UI 或所有平台视频均已验证。

## X（Twitter）解析

- 主路径为匿名 Guest + GraphQL，失败或缺少可用媒体时自动尝试嵌入页数据接口。匿名会话只保存在内存中，过期后重建，不读取或保存登录 Cookie。
- 支持 `x.com`、`twitter.com`、移动域名和指向推文的 `t.co` 短链；短链只允许跳转到已识别的 X 域名。`/video/N` 按原媒体位置选择，图文混排不会错选其他视频。
- 一条推文有多个视频时，结果页先选择视频，再选择该视频的画质；引用视频单独标识。下载记录保留各媒体的独立 ID、来源链接和请求头。
- 原生路径读取页面提供的 MP4（包括动画 GIF 对应的 MP4）；对同一推文继续用 yt-dlp 补充 HLS、分轨画质及视频卡片，按媒体 ID 合并候选并保留 `/video/N` 的选择。用户选择分片画质后由引擎下载与合并。受保护或要求登录的内容可在网页辅助中使用本机授权会话重新解析；实际能否访问取决于账号和网站限制。
- `XVideoHttpClient` 集中维护网页接口版本与参数；`XSyndicationToken` 的浮点转 36 进制行为有 JavaScript 对照测试。相关接口不是稳定的开发者 API，平台调整时仍需更新适配。

2026-09-29 验证：92 项单元测试通过；3 项 Android 设备测试通过，包括真实界面输入/解析/选择 1080P/保存、离线多视频切换及故障注入后的真实备用接口访问。指定推文 `2096916397947257248` 提供 1080P/720P/360P/270P，完整下载 17,618,131 字节，Android 读取到 1920×1080、约 38 秒、包含音频。截图和日志位于 `app/build/reports/x-device/`。本次未验证 X 视频的转存相册或全程播放。

新增的 X 补全单元测试覆盖原生 MP4 与引擎 HLS 合并、视频卡片追加和指定媒体保留；真实 X 引擎结果以设备联网用例 `XVideoDeviceTest#liveUnifiedXAddsEngineFormats` 单独验收。网页登录态按实际请求主机查询 Cookie，额外覆盖 `api.x.com`；真实账号登录仍需单独验证。直播录制、纯音频 Spaces 与 DRM 不在点播下载范围内。

## 断点续传实现

`ResumableDownloader` 的行为与 `curl -C -` 一致：

- 已有 `.part` 文件时发送 `Range: bytes=<n>-`，并带上首次响应记录的 `ETag` 作为 `If-Range`，
  远端文件变化时自动从头重下而不是拼出损坏文件；
- `206 Partial Content` → 追加写入；`200 OK`（服务端忽略 Range）→ 截断重写；
  `416 Range Not Satisfiable` → 判定本地已完整，直接改名为成品；
- 网络中断时保留已落盘的字节，记录 `PAUSED`（可继续），并对瞬时错误做 3 次指数退避重试；
- 传输过程中每 2 秒把字节偏移量写入历史文件，进程被杀后仍能从断点恢复；
- 应用启动时会把上次遗留的 `RUNNING`/`PENDING` 记录降级为 `PAUSED`，界面显示「继续」而不是卡住的进度条。

前台服务 `DownloadService` 在下载期间保活进程并在通知栏显示进度与「全部暂停」。

## 存储位置

视频保存在应用私有外部目录，无需任何存储权限，并通过 `FileProvider` 供播放/分享使用：

```
Android/data/com.videodown.douyin/files/Movies/Douyin/
```

历史记录保存在 `files/download_history.json`（写临时文件后 rename，保证原子性；文件损坏时会
保留为 `.corrupt` 而不是静默丢弃）。

## 已知限制

- 抖音接口变动频繁。若三级解析全部失效，界面会给出明确错误提示（已识别视频 ID 但取不到信息），
  此时需要更新 `DouyinJsonParser` 的字段适配。
- 图文（图集）作品没有视频流，会提示「没有可下载的视频流」。
- `ratio=` 派生档位属于兜底策略；若接口已提供完整 `bit_rate` 列表，则以官方档位为准。
