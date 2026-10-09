# 通用视频解析与下载实施验证（2026-09-29）

> 2026-10-09 更新：后续已在 arm64/API 30 真机验证公开站点解析、完整下载、音视频播放、网页恢复及 Wi-Fi/进程退出恢复，详见 [P1 后续验收](p1-remaining-2026-10-09.md) 和 [P2 真机验收](p2-device-2026-10-09.md)。下文“未连接物理手机”属于 2026-09-29 当轮状态；账号登录、网站挑战和移动网络仍未完成验收。

## 已落地

三个能力层均已接入：原有四平台解析器与通用 yt-dlp 回退、本机分片/分轨下载及 FFmpeg 合并、用户主动使用的 WebView 网页辅助。输入保留抖音纯 ID，多视频页面由用户选择单项。任务状态、暂停/继续/取消、Wi-Fi 限制、通知、历史兼容、分享 MIME 和相册容器检查均接入现有流程。

固定依赖为 youtubedl-android library/ffmpeg 0.18.1、yt-dlp 2026.08.19（含 EJS）。实际启动检查同时运行 QuickJS、FFmpeg 并导入 EJS。原 FFmpeg 包中五个 WebP 动态库在 16 KB 页面设备上无法加载，已使用上游源码为 arm64/x86_64 重编译并固定校验和；详见 [引擎说明](../research/universal-video-engine.md)。

## 环境与结果

设备为 `emulator-5556`，`sdk_gphone16k_arm64`，Android 37.2，系统页大小 16384。它是模拟器，不是真机。

| 层级 | 结果 |
|---|---|
| JVM | 112 项通过，无失败；包含旧历史兼容、四平台解析、过期地址刷新、ETag 断点策略、HTTP 416、空间不足和媒体选择身份 |
| 构建 | arm64-v8a 与 x86_64 Debug APK 成功，保留 minSdk 26 |
| lint | 无错误，56 条警告、1 条提示，主要为项目现有风格、版本与 App Link 提示 |
| 原生资产 | 核心 SHA-256、EJS 文件和 10 个替换动态库的 SHA-256 / ELF 16 KB 对齐校验通过 |
| Android 完整回归 | 启用微博、快手、X 和 YouTube 联网参数，32 项全部通过，约 205 秒 |
| YouTube 完整下载 | 单独用例通过，37 个格式，选中 144P 格式 602，输出 MKV 12,288,046 字节，具有视频和音频轨道，约 114 秒 |
| 进程退出 | 通过：下载中强制停止应用，确认 Python / FFmpeg 进程不残留；重新启动后任务恢复为暂停，继续下载产物 12,288,046 字节并校验音视频轨道（第二轮约 126 秒） |

完整回归覆盖 MP4、无扩展名直链、普通 HTML 视频、HLS 点播和 master 多候选、DASH 音视频分轨；使用设备上的 FFmpeg 生成真实音视频测试资源。网页辅助通过 Espresso 的真实指针点击网页播放按钮，检测动态加载的资源，再选择、下载并进入 ExoPlayer；验证播放进度前进且同时存在音视频轨道。

异常覆盖包括分片任务因 Wi-Fi 策略变化暂停并恢复、合并阶段取消后继续、子进程组清理、Cookie 跨主机重定向隔离及清除、CENC 加密媒体拒绝、旧断点与过期地址。磁盘不足采用 ENOSPC 故障注入，不占满设备磁盘。历史恢复另有 JVM 测试。

## 公开网站样例

| 来源 | 此次结果 |
|---|---|
| YouTube `aqz-KE-bpKQ` | 解析出 37 个格式；完整下载及音视频轨道检查通过，按实际容器保存为 MKV |
| 微博 `RklMe8iOM` | 完整下载 540P，8,754,435 字节，540×960，约 67 秒 |
| 快手 `X3uQi2UzJmqN2fq` | 完整下载 720P，75,746,030 字节，720×1280，约 261 秒 |
| 微博动图用例 | 下载 885,907 字节 MP4，720×1280；真实界面播放有画面且播放位置前进 |
| X 既有样例 | GraphQL 真实 UI 下载 1080P，17,618,131 字节，1920×1080，含音频；备用接口与多视频选择通过 |
| YouTube 旧样例 `BaW_jenozKc` | 返回未找到可下载视频；没有认定为支持，也没有证明具体站点失败原因 |
| 抖音 | 现有解析及路由的 JVM 回归通过；本轮未追加抖音真实联网下载样例 |

这些结果只说明测试时的指定网址可用，不代表全站支持。第三方登录失效/验证码提示有处理，但没有使用真实账号验证各站点的全部登录流程。

## X 媒体补全验证

同日继续修复 X 原生解析返回部分 MP4 后停止的问题：对单条推文继续调用 yt-dlp，并按媒体 ID 合并新画质和卡片视频；指定 `/video/N` 仍保留原生选择。浏览器会话会按 `x.com` 和 `api.x.com` 各自的目标 URL 查询 Cookie。单元测试覆盖原生结果与 HLS/卡片结果合并、相同媒体去重、Cookie 来源扩展和路由实际调用。

在 `emulator-5556` 上，原 X GraphQL UI 及 1080P 完整下载回归通过。新增的真实 X 引擎用例针对公开推文 `2096916397947257248` 验证：yt-dlp 返回 4 档直链和 4 档音视频分轨格式；统一解析把 4 档分轨加入同一媒体，并完成其中一档分轨的实际下载、合并和 Android 音视频轨道校验。该样例未返回 HLS，因此 X HLS 格式补全仍只有结构化测试与通用 HLS 设备测试证据，没有 X 站点真实样例。真实 X 登录、受保护内容和视频卡片仍未使用账号或对应链接做设备验证。

补全后完整 JVM 套件为 119 项通过、零失败；arm64-v8a 和 x86_64 Debug APK 重新构建并通过 SHA-256 核对，产物及 `SHA256SUMS` 位于 `.tooling/validation/`。x86_64 仅完成构建，没有相应设备运行验证。

## 产物与复现

最终安装包、完整联网日志、YouTube 下载日志和网页点击截图保存在本地 `.tooling/validation/`（该目录已忽略，不进入源码）。arm64 包约 81.7 MiB，x86_64 包约 84.4 MiB；对应 SHA-256 保存在该目录的 `SHA256SUMS`。最终包另外通过一次网页辅助 UI 回归和一次原生引擎/加密媒体拒绝烟测。核心测试入口：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:assembleDebug -Pabi=x86_64
python3 tools/verify-engine-assets.py

adb -s emulator-5556 shell am instrument -w \
  -e live_platforms true -e live_weibo_gif true -e live_x true \
  -e generic_live_url 'https://www.youtube.com/watch?v=aqz-KE-bpKQ' \
  com.videodown.douyin.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`GenericVideoDeviceTest#optionalPublicPageParse` 额外传 `generic_live_download=true` 可完整下载选中的最低画质，测试结束删除其隔离文件。进程恢复用例 `GenericVideoDeviceTest#processExitResumeCheckpoint` 分两次运行：先传 `process_exit_phase=seed` 与 `generic_live_url`，看到 `PROCESS_EXIT_READY` 后对调试应用执行 `am force-stop`；再传 `process_exit_phase=resume`，由第二轮验证恢复并清理该测试任务。首轮预期显示 `Process crashed`，不能当作普通通过用例。

## 尚未完成的设备验收

- 未连接 arm64 物理手机，因此真机解析、完整下载、音视频播放及网页点击验收仍待执行。
- x86_64 完成构建和原生 ELF 检查，当前 Apple Silicon 主机没有可运行的 x86_64 模拟器，未做该架构运行验证。
- Android 8.0 的 minSdk 兼容目标保留，未在 API 26 设备上运行。
- 未穷举所有站点、全部真实账号登录流程、所有容器的系统相册支持或物理设备蜂窝/Wi-Fi 切换。实际空间耗尽未执行，只做故障注入。

因此当前为三个能力层实现并通过上述模拟器回归，不能将构建或模拟器结果表述为完整真机验收。
