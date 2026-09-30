# 手机端通用视频引擎

解析顺序：已有平台解析器 → 直链探测 / yt-dlp → 用户主动打开 WebView 登录或播放。X 推文额外将原生结果与 yt-dlp 结果按媒体 ID 合并；引擎解析一条推文的媒体列表，不自动下载整页。
下载顺序：直链走 ResumableDownloader；HLS、DASH、分轨走 Python 子进程，FFmpeg 合并后验证视频轨道。

## 固定依赖

- youtubedl-android library / ffmpeg：0.18.1（Maven Central），https://github.com/yausername/youtubedl-android/tree/0.18.1 。包含 Python、QuickJS 和 FFmpeg。
- yt-dlp：2026.08.19，https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19 。APK 内置官方 zipimport 文件，SHA-256 为 `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6`，已对照发布方 SHA2-256SUMS 校验。该文件同时包含 yt-dlp-ejs。
- yt-dlp 运行时不在线更新；APK 初始化按 VERSION.txt 覆盖旧核心。更换版本须同时运行设备测试。
- 包装库的 FFmpeg 压缩包包含 5 个 4 KB 对齐的 WebP 动态库，原包在 16 KB Android 上实测无法启动。使用上游 libwebp 1.5.0 源码、NDK r27c、Android API 26 重编译 arm64-v8a 和 x86_64 版本，链接参数 `-Wl,-z,max-page-size=16384`。复现脚本为 `tools/build-webp-16k.sh`；输出固定保存在 `app/src/main/assets/engine/native/`，初始化时替换对应解包库。无需修改系统或模拟器页大小。
- 引擎版本检测实际执行 QuickJS 和 FFmpeg 并导入 EJS；初始化失败不会显示成可用。

## 会话和任务

网页登录由 WebView CookieManager 管理，不导入其他浏览器账号。默认匿名解析；只有网页辅助结果设置 useBrowserSession。X 网页辅助会分别向页面域名及 `api.x.com` 查询其可见 Cookie，以便 yt-dlp 在实际 API 请求上识别授权会话；不跨域复制原始 Cookie 请求头。真实 X 登录尚需账号验收。
每个 HTTP 请求及重定向按目标 URL 获取 Cookie。yt-dlp 使用任务专属 Netscape Cookie 临时文件，精确到已知主机；文件在 finally 删除，进程意外终止留下的文件在下次启动清理。持久化媒体描述不包含 Cookie 或 Authorization。

Python 通过 stdin 接收 JSON 参数，不使用 shell 拼接。启动后首先返回独立进程组 PID，Kotlin 确认后才发送任务。暂停、取消或 Wi-Fi 限制同时终止 Python 和 FFmpeg 进程组；Python 还监视父进程，应用进程退出后自动结束整组子进程。桥接只输出结构化事件，原始提取器日志不进入应用日志。

每个任务拥有独立 engine-UUID 目录，分片断点由 yt-dlp 管理。再次解析的媒体/格式/轨道 URL 指纹变化会清理该任务旧分片。直链 HTTP 401/403/410 仅刷新一次；仅相同媒体格式且存在 ETag 时保留旧断点，否则清空重下。分片鉴权失败也只按原网页刷新一次；不能确认媒体和格式一致时提示重新选择。网页辅助记录保留原网页，动态资源无法确认身份时需要重新进入网页选择。清空历史会先停止任务并清理各自分片目录。

下载完成后必须获得完整文件、真实视频轨道和可读取的视频样本；存在 DRM 初始化信息或加密样本时拒绝成功状态。合并产物在校验后统一发布并写入历史。MP4 优先无损合并；不兼容时使用 MKV 等原生容器，分享 MIME 跟随真实文件。相册仅导出 MP4/M4V/WebM/MOV，其他容器提示分享文件。

## 边界

面向点播视频；不录制直播、不解密 DRM、不做整站采集。候选列表只下载选中的视频。WebView 不提供 JavaScript 原生桥，不忽略 SSL 错误，不开放 file/content 访问。blob 本身不作为下载地址，依赖检测到底层媒体请求。部分网站禁止 WebView 登录或使用设备绑定鉴权，仍可能无法解析。

默认继续使用已有 arm64 构建方式；额外 x86_64/其他 ABI 构建参数仍保留。增加 Python 与 FFmpeg 后包体明显增大，具体数值以最终 APK 为准。

## 第三方许可

- youtubedl-android：GPL-3.0，https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE
- yt-dlp：Unlicense，附带 EJS 及其第三方许可，https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/THIRD_PARTY_LICENSES.txt
- FFmpeg：实际许可取决于所用构建，本项目沿用上述包装库构建，来源说明：https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md
- libwebp：BSD，版权及许可见 APK assets/engine/native/COPYING.libwebp，源码来源：https://github.com/webmproject/libwebp/tree/v1.5.0 。NDK 编译脚本固定源码校验和。

发布时须保留相应许可与可获取源码信息；本次工程修改不包含商店发布。

## 验证记录

见 [2026-09-29 实施验证](../validation/universal-video-2026-09-29.md)。构建、模拟器、联网和真机验收分别记录。
