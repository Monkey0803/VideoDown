# 站点专区与 KUBB.TV 在线播放验收（2026-11-16）

## 范围

- 解析首页「站点专区」与站点中心。
- KUBB.TV 16 个非直播频道、受限 WebView 与媒体嗅探。
- 进程内在线播放会话、独立观看历史、断点续播与播放器内下载。
- 匿名公开内容边界：不导出浏览器 Cookie，不处理登录、会员、支付、DRM 或直播。

## 自动化结果

执行环境：Pixel_10_Pro AVD，Android 17 / API 37，arm64。

```text
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
BUILD SUCCESSFUL

./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.SiteHubDeviceTest,com.videodown.douyin.TaskSearchDeviceTest
BUILD SUCCESSFUL
```

JVM 新增 11 条站点层用例，覆盖：

- 注册表可扩展性、KUBB.TV 16 个非直播频道和能力声明。
- HTTP/HTTPS、导航域名、媒体域名与登录路径边界。
- 选集 `?id=` 保留、fragment 删除与 URL 规范化。
- 观看历史序列化、同集覆盖、最新优先、条数上限和完成后清零断点。
- WebView JavaScript 结果解码、页面标题/选集组合、通用媒体文件名过滤和封面 URL 安全校验。
- 在线会话转下载模型时保留真实标题/封面，并把可重新解析的站点页面 URL 写入下载记录，不持久化临时媒体 URL。
- 暂停或失败的旧站点任务重新识别后可原地回填真实标题、封面和播放页地址，不改变下载字节与任务状态。

设备新增 5 条本地 fixture 用例，均通过：

- `parseHomeOpensSiteHubAndLocalChannelsSwitchInsideWebView`：解析首页进入站点中心；本地站点在原生电影/电视剧 Chip 间切换；离开页面再返回后断言复用同一个 WebView 实例，当前页面不重新加载。
- `localHlsPlaysWritesProgressAndDownloadsTheExactVariant`：本地 VOD HLS 出现视频轨并进入 READY；2 秒周期写入含选集参数的观看记录；下载按钮把同一个 `DouyinVideo` / `VideoVariant` 交给下载器。
- `pageMetadataReplacesGenericHistoryTitleAndAddsCoverAndEpisode`：本地播放页通过固定适配器脚本读取 Open Graph 标题、相对封面和当前选集，把旧 `chunklist` 记录更新为“标题 · 选集”，同时保留规范页面 URL。
- `historyCardsUseFallbackProgressAndDeleteWithoutOpening`：旧通用媒体名显示“在线视频”兜底；有进度记录显示 4dp 进度条；点击独立删除按钮只删除记录，不触发卡片跳转。
- `listCardClickCapturesRealTitleCoverAndPlayPageBeforeNavigation`：模拟 KUBB 列表页的 Angular 导航时序，在媒体请求前捕获用户点击卡片的真实片名、封面和 `/play/...` 地址，避免下载任务退化为 `chunklist`。

站点中心观看记录卡片改为 112×68 的横向封面、双行标题、“KUBB.TV · 已观看 xx%”辅助信息和独立 48dp 删除热区。没有封面时显示播放器占位图，零进度记录不绘制误导性的进度条。

下载任务卡同步处理站点媒体：匿名 KUBB.TV 下载显示站点名而非“未知作者”，84×52 封面区在图片缺失时显示播放占位。KUBB 列表页会在捕获阶段记录用户点击卡片的真实标题、封面与播放页地址，再把这些元数据绑定到嗅探出的媒体；`chunklist` / `master` 只作为无法恢复的旧记录兜底，不再作为新任务标题。旧的暂停/失败任务在重新打开同一影片并命中重复任务时会原地修复元数据。`kubbTaskUsesFriendlyTitleSourceAndCoverPlaceholder` 验证旧记录不会继续暴露媒体文件名；在线播放下载用例同时断言下载器收到包含规范页面 URL 的视频模型。

首次设备运行暴露了 `shouldInterceptRequest` 后台线程读取 `WebView.settings` 的问题；修复为在主线程创建 WebView 时缓存 User-Agent，复跑通过。

后续返回路径复核发现仅保存 `WebView.saveState()` 仍会在 `restoreState()` 时重新请求页面。最终实现把 WebView 保留在站点页对应的 `SiteBrowserViewModel` 生命周期内：进入播放器等子路由时只从视图树分离，返回后重新挂载同一实例；真正退出站点路由时随 ViewModel 销毁，配置切换时也主动销毁旧 Activity Context，并退回状态恢复路径。新增同一实例断言后复跑通过。

## 安全与架构检查

- `domain/` 新增模型没有 Android / AndroidX import。
- 页面从 `ServiceLocator` 获取注册表、会话和观看历史，没有直接构造数据实现。
- WebView 禁用文件/内容访问、混合内容、多窗口及第三方 Cookie；只放行 HTTP/HTTPS，站内导航按域名与路径白名单判定。
- 在线 Media3 数据源使用无 Cookie 的独立 OkHttp client，每次请求保留 Referer/User-Agent，并在网络拦截器再次删除 `Cookie`。
- 签名媒体 URL 和请求头只存在 `OnlinePlaybackSessionStore` 内存中；JSON 历史只持久化规范页面 URL 与展示/进度字段。
- 在线播放器复用既有控制层和手势层；截图、抽帧预览与本地文件分享未接入在线模式。

## 未执行与边界

- `KubbLiveDeviceTest` 已编译但默认跳过；本轮没有执行真实 KUBB.TV 联网验收，避免把站点瞬时可用性当作离线回归结果。启用 `live_kubb=true` 后会从电影、电视剧、动漫和小视频各找一个公开页面并解析，首条短时播放；`live_kubb_download=true` 只额外创建下载任务并立即取消，流量随站点内容变化。
- 外部浏览器实际拉起、无手势弹窗、登录/VIP 页面、配置切换、WebView 返回栈、PiP 系统窗口和会话丢失提示已有实现，但本轮未逐项做设备 UI 自动化；域名/路径边界由 JVM 覆盖。它们仍是后续真机回归重点。
- KUBB.TV 页面结构、媒体 CDN、地区策略或反自动化规则变化时可能暂不可用；适配器不回退到私有签名 API，也不会尝试登录、付费、DRM 或直播绕过。
