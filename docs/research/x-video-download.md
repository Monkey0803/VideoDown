# X（Twitter）视频下载调研

调研日期：2026-09-29。范围：为当前 Android「拾影」评估视频解析和下载接入方式。下文保留调研时的比较和桌面验证记录。

实施状态：已按用户选择接入原生 Kotlin 的匿名 GraphQL 主路径及嵌入页备用接口，并加入多视频选择。92 项单元测试及 3 项 Android 设备测试通过，设备上已从实际解析界面完整下载指定推文的 1080P 视频，包含音视频轨道；强制主接口失败时，备用接口仍返回四档可下载地址。当前范围为 MP4，HLS 与登录态支持未接入。详情见 [README](../../README.md#xtwitter解析)。

## 指定链接实测

用户示例：[碧蓝航线官方推文](https://x.com/azurlane_staff/status/2096916397947257248?s=20)。

| 检查 | 结果 |
| --- | --- |
| 匿名嵌入数据接口 | HTTP 200，作者 `azurlane_staff`，1 个视频 |
| 匿名 Guest + GraphQL | 两次请求均 HTTP 200，返回目标推文和 1 个媒体对象 |
| MP4 画质 | 1920×1080、1280×720、640×360、480×270 |
| 其他格式 | 还包含 HLS 地址 |
| 1080P 实际文件大小 | 17,618,131 字节，约 16.8 MiB |
| 断点续传 | 首次请求 `bytes=0-65535`；后续请求 `bytes=65536-`；均返回 206，范围连续 |
| 完整性检查 | 文件长度等于 Content-Range 声明的总长度 |
| 媒体检查 | macOS AVFoundation 读取到 1920×1080、约 38.015 秒、1 条视频轨道、1 条音频轨道 |

验证不使用用户登录 Cookie，不调用收费开发者 API。未进行 Android 端接入测试或播放器全程播放测试。结果说明此链接当前可下载，不代表所有推文均可匿名解析。

探测文件位于 `/tmp/videodown-x-research/`，属于临时调研产物。其中 `user-sample-results.json`、`user-graphql-results.json`、`user-download-results.json` 为摘要，`azurlane-2096916397947257248-1080p.mp4` 为样例下载文件。

## 可选方案

| 方案 | 接入方式 | 取舍 |
| --- | --- | --- |
| 原生 Kotlin 解析网页接口 | Guest + GraphQL 获取媒体；嵌入数据接口作为备用 | 可复用现有 OkHttp、画质选择和续传；需要跟进网页接口变化 |
| Android 封装 yt-dlp | 使用 youtubedl-android 获取格式或执行下载 | 可跟进上游解析器；引入 Python/native 运行环境，需要额外验证安装包体积、Android 兼容性和更新机制 |
| 官方 X API v2 | 查询推文，展开 `attachments.media_keys` 并请求 `media.fields=variants` | 文档化接口，但需要开发者凭据和计费；应用密钥应由服务端保管 |

依据：[yt-dlp Twitter 提取器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py)、[youtubedl-android 官方仓库](https://github.com/yausername/youtubedl-android)、[X 媒体数据字段](https://docs.x.com/x-api/fundamentals/data-dictionary)、[X API 计费](https://docs.x.com/x-api/getting-started/pricing)。体积与维护成本为根据依赖方式作出的工程判断，本次未构建或比较 Android 封装方案。

## 建议的解析流程

1. 识别 `x.com`、`twitter.com` 及对应移动域名的推文链接，提取数字 ID；保留 `/video/2` 等媒体选择索引。短链展开后重新校验主机和路径。
2. 请求匿名 Guest Token，然后通过 `TweetResultByRestId` 读取目标推文的媒体信息。请求使用浏览器 User-Agent；本次未设置该头的 Guest 请求返回 404，添加后成功。
3. 对匿名接口失败或返回不完整媒体的情况，尝试 `https://cdn.syndication.twimg.com/tweet-result`。参数包含推文 ID 和按现行算法生成的 token。该接口并非稳定的开发者 API；应严格验证目标 ID、可见性状态和媒体字段。
4. 提取媒体的 `video_info.variants`，优先使用 `content_type=video/mp4` 的地址。分辨率从对应变体路径/元数据获取，避免把媒体原始尺寸误当作每一档尺寸。
5. 交给现有大小探测和下载引擎，使用 X 的 Referer。当前指定链接的 MP4 已同时包含音视频，无需合并。

yt-dlp 当前实现提供 GraphQL、legacy、syndication 三种选择，并明确提醒嵌入接口不保证完整媒体和元数据。因此备用成功不能等同于完整覆盖。另一个 NASA 旧推文样例本次仅返回正文，没有媒体；一个已不可用的样例返回 `TweetTombstone`，需要作为不可用状态显示，不能显示成功或无限重试。

依据：[当前解析实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py)、[接口选择参数](https://github.com/yt-dlp/yt-dlp#extractor-arguments)。不建议新增依赖已过时的 legacy 接口。

## 当前项目需要改动的部分

- 在 `VideoPlatform`、`VideoLinkExtractor` 和 `MultiPlatformVideoRepository` 中加入 X 分支，并新增 HTTP 客户端和 JSON 解析器。
- 将 X 的来源平台、原推文链接、作者主页、媒体 ID 和下载请求头保存到记录中；继续兼容已有三平台的历史记录。
- **一条推文可能包含多个视频。** 当前 `DouyinVideo.variants` 表示同一个视频的不同画质，不能把多个视频混在该列表中；否则按画质去重会丢视频。应先返回媒体列表，让用户选择视频，再选择该视频的画质。单视频保持现有体验。
- 首版处理公开视频 MP4、转推/引用的明确归属、`/video/N` 指定视频、动态图片的 MP4 资源。仅有 HLS 时显示明确提示；HLS 分段下载及封装需要独立工作，当前 Range 下载器不能直接将播放列表当作 MP4 保存。
- 对登录要求、内容不可用、无视频、429 限流、网络不可达和格式不支持分别提示。用户有权访问但需要登录的内容，应另行设计授权会话流程；不把受限作品自动当成可匿名下载。

## 实施验收建议

- 以本次指定链接完成 Android 端四档解析、默认 1080P 下载、暂停续传、播放、保存相册验证。
- 补充多视频、指定索引、GIF、引用推文、仅图片、已删除及匿名受限的解析用例。
- 两条解析路径分别测试：主路径成功、备用成功、两者均失败；确保显示错误并结束加载。
- 回归抖音、微博、快手、旧历史加载、Wi-Fi 限制与完成通知。

建议先采用原生 Kotlin 的匿名解析方案接入公开 MP4，与当前应用结构一致；在需要更多网站或 HLS 等格式时，再评估引入 yt-dlp Android 运行环境。
