# P1 剩余任务验收（2026-10-09）

原始范围见 `docs/function-backlog-2026-10-09.md`。本轮继续已有实现的真实行为验收，不能以新增测试文件或构建成功认定功能完成。

前阶段用户选择先做无需账号和手动配合的验证。2026-10-09 后续用户明确要求验证人工解锁，其他项目先标记：开始安全锁屏后正常人工解锁的 PiP 与熄屏下载验收；移动网络、真实账号登录和网站挑战验证按用户要求暂缓。不得尝试绕过安全锁屏或验证码，也不采集解锁凭据。

## 完成标准与证据要求

| 原始 P1 项 | 要求 | 当前验收状态 |
| --- | --- | --- |
| 画中画锁屏后返回 | 重复 Home、锁屏、解锁、展开和系统关闭；核对同一播放器、进度、释放 | 无锁屏 3 项已通过；安全锁屏两轮正常人工解锁、实际播放暂停及结束释放通过 |
| 后台下载失败反馈 | 前台服务启动、后台传输、熄屏、通知暂停、中断反馈、真实重试 | 前轮已验收主要流程；安全熄屏持续传输、正常人工解锁后完整下载及服务退出通过 |
| 网络变化与进程退出恢复 | 实际 Wi-Fi/移动网络切换、断网、force-stop 后恢复，断点、保存的队列顺序、无重复完成 | 真实 Wi-Fi 断开/恢复和生产 force-stop 后恢复通过；设备没有 SIM，移动网络段不可执行 |
| 真实站点及登录恢复 | 抖音/微博/快手/X/普通网页真实解析下载，网页登录态、过期链接重解析、访问受限提示 | 微博/快手/X/Chrome 下载及 MDN 真实 404 后原网页恢复下载通过；抖音要求登录，W3C 的设备提取器遭反自动化挑战；真实账号及该受限站点下载仍未通过 |

## 本轮测试入口

- `P3PictureInPictureDeviceTest`：重复 Home/返回，系统关闭和明确结束的释放检查；安全锁屏测试需明确 `manual_unlock=true`，正常人工解锁。
- `PhysicalNetworkRecoveryDeviceTest#wifiLossRetainsCheckpointAndQueueThenResumesExactlyOnce`：实际 `WifiDownloadNetworkPolicy`，隔离偏好和历史；`network_test_url` 必须是经物理网络访问的非 loopback HTTP 地址，另传 `network_test_sha256`。看到 `NETWORK_OFF_READY` 才临时断开 Wi-Fi，看到 `NETWORK_ON_READY` 才恢复。验证暂停后的文件不再增长、队列保存、三份文件哈希、每条只完成一次。
- `PhysicalNetworkRecoveryDeviceTest#forceStopRestoresProductionTasksAndSavedQueue`：生产 `ServiceLocator`、真实前台服务和持久化历史，传 `physical_process_phase=seed`；看到 `PHYSICAL_PROCESS_READY` 后由外部对应用执行 `am force-stop`，首轮应被终止，不能记为通过。随后同样 URL/hash 运行 `physical_process_phase=resume`，检查中断任务恢复为暂停、断点和队列、完整字节，最后只删除本测试 UUID 任务。
- `PublicSiteAcceptanceDeviceTest`：分别传 `public_douyin_url`、`public_weibo_url`、`public_kuaishou_url`、`public_x_url`、`public_web_url`，逐项运行。使用生产统一解析仓库及直接/通用下载器；完成必须核对完整文件及真实视频轨道和时长。未提供参数的跳过项不是验收通过。
- `PublicSiteAcceptanceDeviceTest#readableNegativeTimestampSampleIsNotAnEmptyVideo`：`negative_pts_fixture_path` 仅允许 app cache 中精确 SHA-256 的 Chrome 公开样本；检查实际编码样本可读、生产负时间戳校验，以及截断文件安全拒绝。
- `UnavailableLinkRecoveryDeviceTest#publicUnavailableLinkOffersOriginalPageReparse`：需 `public_unavailable_link=true`，创建唯一 UUID 的生产任务，实际请求旧公开源，记录真实 `LINK_EXPIRED` 或 `ACCESS_DENIED`；仅点击本任务的恢复按钮，核对 Home 输入是精确原网页地址。外部访问受限时只能验收导航及反馈，不能声称获得新地址并完成下载。
- `ExpiredPublicMediaRecoveryDeviceTest#realRemovedMdnSourceRecoversThroughOriginalPageAndAnonymousDiscovery`：需 `public_mdn_recovery=true`，使用官方 MDN 已移除的真实历史视频地址；实际 404 任务恢复至原文档，自动解析不支持时使用现有匿名网页辅助，自然发现 flower 视频再完整下载，核对原文档地址、完整远端字节及媒体轨道。不会向解析器直接输入替代视频地址，也不会改网页内容。

设备操作和证据保存于 `docs/validation/p1-remaining-device-2026-10-09/`。测试结束必须恢复原有网络设置，保留用户文件、历史、偏好和网页登录态；不得清空应用数据。

## 当前已验证结果

- 最终生产媒体校验及恢复弹层修复后 JVM 全套 207 项通过，Debug app/测试 APK 构建通过，见 `build-browser-recovery-final.txt`。
- Python 共 12 项通过：失败分类 8 项、HTML 注释处理 4 项（含实际打包 yt-dlp 桥接集成）。使用已配置的 Codex bundled Python，见 `python-green.txt`；系统 Python 3.9 不满足打包 yt-dlp 的版本要求，不能以该环境的失败推断产品故障。
- 真机画中画 Home/返回 3 轮和播放暂停均通过，使用同一播放器，进度推进；明确 Activity 结束释放检查通过。见 `pip-no-lock.txt`。
- MIUI 真实系统关闭按钮点击后，播放器停止并达到 `STATE_IDLE`，播放断点保留；最终单项通过（7.872 秒）。进度断言再补充循环播放的 `AUTO_TRANSITION` 累计位置及同一 mediaItem 检查后，无锁屏整组 3 项通过（17.766 秒），见最终 PiP 整组记录。
- 实际 Wi-Fi 下载路径使用 Mac LAN 地址 `192.0.2.10:8766`，没有使用 loopback 或 adb reverse。真实 `svc wifi disable`/`enable` 后，断点与落盘均为 81920 字节，断网时不再写入；主机收到 `Range: bytes=81920-` 续传。三个任务恢复后的 SHA-256 全部与源文件一致，保存的队列顺序和每项仅一次完成断言通过（72.285 秒）。见 `physical-network.txt`、`lan-server.txt`、`lan-sha256.txt`。Wi-Fi 恢复开启，移动数据原为关闭且未修改；双 SIM 均为 `ABSENT`，不能推断移动网络切换通过。
- 生产 `ServiceLocator` 的 force-stop 两阶段验收通过：seed 在非零断点保存后被真实强制停止（`Process crashed` 为预期终止，不能计作通过）；新进程检查三个任务恢复为 `PAUSED`，队列顺序保留、非零断点和文件保留，继续后三个完整文件哈希正确（65.335 秒）。主机记录 `Range: bytes=876544-`，不是在原进程重新创建引擎模拟退出。见 `physical-process-seed.txt`、`physical-process-resume.txt` 和 `lan-server.txt`。只清理本轮三个 UUID 任务，恢复运行时并发配置。
- 通用引擎的公开 YouTube 样例 `https://www.youtube.com/watch?v=aqz-KE-bpKQ` 也完成真实 force-stop 两阶段：新进程检查暂停任务及引擎目录保留，恢复后完成 12288046 字节并核对视频和音频轨道（43.565 秒）。见 `engine-process-public-seed.txt`、`engine-process-public-resume.txt`；这是真实引擎下载路径，不能由 LAN MP4 的 Range 结果替代。
- 初次关闭失败来自测试：MIUI 不支持根节点 `ACTION_DISMISS`；小窗菜单会自动隐藏；未推进 Compose 测试帧时截图仍显示原页面。测试改为推进帧并根据当前系统窗口层级重新唤出、点击“关闭”，保留释放与进度断言。未据此修改生产播放器，也不能从上述无锁屏结果推断安全锁屏通过。

## 公开样例来源

微博、快手和 X 沿用仓库已有 opt-in 测试中的公开样例。抖音候选取自 [yt-dlp 官方 DouyinIE 测试样例](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/tiktok.py)，`https://www.douyin.com/video/6982497745948921092`。上游有测试样例不代表当前无登录即可访问，必须以真机执行结果为准。

## 真实公开站点结果

以下使用生产统一解析仓库、真实远端下载、完整文件长度和媒体轨道检查，文件及隔离历史在测试后删除。

| 平台 | 样例 | 结果与证据 |
| --- | --- | --- |
| 微博 | `https://weibo.com/1234567890/RklMe8iOM` | 完整 540P，8754435 字节，生产媒体校验及真实视频轨道和正时长；4.228 秒，`public-weibo-final.txt` |
| 快手 | `https://www.kuaishou.com/f/X3uQi2UzJmqN2fq` | 完整 720P，75746030 字节，生产媒体校验及真实视频轨道和正时长；23.575 秒，`public-kuaishou-final.txt` |
| X | `https://x.com/azurlane_staff/status/2096916397947257248?s=20` | 完整 270P，623461 字节，生产媒体校验及真实视频轨道和正时长；10.480 秒，`public-x-final.txt` |
| 抖音 | 上述官方 extractor 样例 | 生产首页真实 `LOGIN_REQUIRED` 及“打开网页登录/验证”按钮可见；匿名 WebView 可以加载公开页面，真实点击“使用网页登录态重新解析”后终态为“未找到可下载的视频，可打开网页登录或播放后查找”，见 `douyin-anonymous-terminal.png/xml`。不能计作视频下载或账号登录恢复通过 |
| 普通网页 | `https://googlechrome.github.io/samples/muted-autoplay/` | 完整 258137 字节 MP4，H.264/AAC 轨道及正时长、最终生产校验通过；15.364 秒，`public-chrome-final.txt` |
| W3C 受限网页 | `https://www.w3.org/2010/05/video/mediaevents.html` | 原 OGG/注释格式缺陷已修复，但最终同页真机提取器被 Cloudflare 403 challenge 阻断，生产分类 `ACCESS_DENIED`；`public-w3c-fixed.txt`、`w3c-bridge-diagnostic.txt`。不能计为下载通过 |

普通网页缺陷证据：首轮测试缺少生产 `validateFile` 回调，误将非空 OGG 文件记作完成，后续媒体检查失败。补齐生产校验后同样选择立即失败并保留了诊断样本；设备 FFmpeg 识别为 OGG、Theora 854×480、Vorbis 48kHz、52.25 秒（`w3c-invalid-codecs.txt`）。实际容器不是 MP4，不能靠换一个样例或手动选择 MP4 掩盖。`GenericMetadataParser` 现在过滤明确不在现有视频容器白名单中的格式，保留没有扩展名元数据的 opaque 视频兼容；新增回归测试先确认 RED，再进行修复后的 GREEN 与同页设备验收。

同一 W3C 页还暴露了第二个真实问题：通用提取器把 HTML 注释里的旧 `<source>` 当成有效地址，实际请求返回 404。`video_engine.py` 使用 HTMLParser 识别并剔除真正的注释，保留 script/style 中的原始内容和原有 extractor identity；在解析和下载分支均使用同一处理。打包桥接集成测试核对只返回有效 source，真机同页复验结果另列。

Chrome 官方 autoplay 示例 `https://googlechrome.github.io/samples/muted-autoplay/` 暴露第三个真实问题：258137 字节完整 H.264/AAC 文件被生产校验误判失败。真机读取到首帧时间戳 -107700 微秒、视频轨道索引 0，并成功读取编码样本；旧校验仍抛“下载结果没有完整的视频数据”（`negative-pts-red.txt`）。`validateVideo` 改为检查选中视频轨道是否有当前样本，允许合法负时间戳，保留 DRM/加密检测；不分配固定大小的生产帧缓冲区。回归测试使用精确 SHA-256 的公开样本，并同时要求 32 字节截断文件仍被拒绝。

新增损坏文件及 CENC 保护内容回归进一步发现本机系统媒体库原生崩溃，栈为 `NuMediaExtractor::getFileFormat → MediaExtractor.getDrmInitData → validateVideo`（`validator-crash.txt`）。生产校验顺序调整为先检查有效视频轨道、选中样本及加密标记，再读取文件级 DRM 元数据；所有保护检测保留。这属于实际产品校验路径的崩溃，不能归为测试框架问题；此前带 `Process crashed` 的 runner 记录不计通过，最终回归结果另列。

W3C 排查边界：主机使用实际打包 yt-dlp 与生产 HTML 注释处理后，仅提取出三项有效格式（ogv/webm/mp4），不再包含两个注释地址，见 `w3c-host-extractor.txt`；这不是设备通过证据。真机无 Range 的普通 HTTP 请求能获取完整网页，但实际提取器返回明确 Cloudflare challenge 403（`w3c-bridge-diagnostic.txt`），不是从安全分类文案猜测根因。没有安装 impersonation 依赖或绕过网页挑战。

最终媒体回归 `validator-final-green.txt` 明确 `OK (3 tests)`，57.206 秒：

- 负时间戳完整 MP4 生产校验接受，32 字节截断 MP4 安全拒绝，不再触发原生崩溃。
- 真机 MP4、opaque 地址、HTML 页、HLS、HLS 主子流和 DASH 共六种下载全部完成，媒体视频和音频轨道检查通过；这些是本地协议 fixture 回归，不能代替公开站点验收。
- CENC 加密样本在本机 `trackCount=0`，要求生产校验精确拒绝为“下载结果不包含有效的视频轨道”，没有调用危险文件级元数据接口。不能声称本机成功识别了 DRM 轨道或 `kind=DRM`；其他能识别轨道的设备分支仍严格要求 DRM 异常。

## 原网页恢复弹层

恢复 UI 测试先发现等待条件只检查通用失败标题，错误命中了任务卡原有标题；修正为必须同时出现 Home 输入、精确原网页地址和解析终态。随后真实任务恢复仍超时：点击前层级包含本轮唯一 UUID 的任务和恢复按钮，点击后层级为旧抖音网页弹层（`unavailable-overlay-after-reparse.xml`）。旧浏览器会遮住新的原网址解析结果；此时无法从上层节点读取底层输入，不能假称已读到该值。

`HomeUiState` 保留单调的解析请求 ID，`HomeScreen` 在新请求时重置旧浏览器显示状态；同 URL 重解析和瞬时失败均生效，显式打开网页请求仍保留。新增 JVM 回归检查同 URL 连续快速受限失败后的新请求仍可观察，以及后续网页辅助请求正常。

最终同路径明确 `OK (1 test)`，18.474 秒，见 `unavailable-link-overlay-green.txt`：`unavailable-browser-before-green.png/xml` 确认真实匿名抖音网页弹层打开；本轮唯一 UUID 任务的恢复按钮被实际点击；最终 Home 输入是精确 W3C 原网页地址，终态 `ACCESS_DENIED`，旧抖音弹层不再出现。见 `expired-link-evidence/expired-reparse-terminal.png/xml`。这验证真实受限反馈与恢复导航，不能推断 404/410、网页登录完成或获取新地址后下载已通过。

## 真实 404 后原网页恢复下载

继续自动验收后补齐 MDN 样例闭环。旧地址 `https://interactive-examples.mdn.mozilla.net/media/examples/flower.mp4` 确实曾用于 MDN video 文档，[MDN 官方问题记录 #1674](https://github.com/mdn/interactive-examples/issues/1674) 可核对历史来源；不是人为拼造不存在的文件路径。当前真机实际返回 404，见 `mdn-device-http.txt`。

专属测试 `ExpiredPublicMediaRecoveryDeviceTest` 明确 `OK (1 test)`，21.110 秒，见 `mdn-expired-recovery.txt` 和 `expired-public-media-evidence/`：

1. 生产下载旧地址失败为 `LINK_EXPIRED`，错误保留 HTTP 404，原文档地址保留。
2. 点击本轮唯一 UUID 任务的“重新解析原网址”，Home 输入是精确 `https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/video`。动态示例页的自动解析返回 `UNSUPPORTED`，这一限制没有改写成自动解析成功。
3. 实际点击“打开网页查找视频”，匿名 WebView 正常加载原文档并自然检测到嵌入演示的 flower WebM，选择候选后解析成功；没有直接输入替代媒体地址、注入网页脚本或选其他 archive.org 视频。
4. 实际点击“保存到本地”，生产记录 `sourcePageUrl` 仍为原 MDN 文档，下载源是网页自然发现的 `*.mdnplay.dev` 的 `/flower.webm`。
5. 完整下载 554058 字节，与同发现地址的完整服务器字节一致；生产媒体校验、真实视频轨道、正时长 5059 毫秒均通过。SHA-256 为 `c6f8a348953395598a9a73b9bab1676436410797bce9f398f4be1531d6e76dda`。

这是通过现有网页辅助恢复的真实失效源下载，不代表所有动态网页均可自动解析，也不代表账号登录恢复已通过。测试 APK 编译通过；本次未修改生产实现，207 项 JVM 和 12 项 Python 的此前最终通过结果仍适用于当前生产代码。

## 安全锁屏后正常人工解锁

PiP 最终专项 `pip-manual-unlock-final.txt` 明确 `OK (1 test)`，25.155 秒，包含两轮真实安全锁屏和正常人工解锁。锁屏时 `interactive=false/keyguardLocked=true`，播放器及视频实例均保留；返回后两轮均为 `STATE_READY`、暂停状态、Activity 未销毁，进度分别从 2301 到 2875 毫秒、5960 到 6536 毫秒，未重置。实际点击原生播放按钮后进度推进，再次暂停/播放通过；最后结束 Activity，播放器停止并释放，保存的断点大于零。

保留前两次未通过记录：`pip-manual-unlock.txt` 因 120 秒内没有检测到人工解锁结束，不是产品故障；`pip-manual-unlock-retry.txt` 在正常解锁后被测试要求自动播放的预期阻断。生产 `PlayerScreen` 的 `ON_STOP` 明确暂停视频，测试修正为核对原实例、媒体、就绪状态和断点，再通过实际 UI 恢复播放，未修改生产自动播放语义。

后台专项 `background-manual-unlock.txt` 明确 `OK (1 test)`，43.649 秒：安全熄屏时 `interactive=false/secureLocked=true`，下载从 139264 增至 401408 字节；正常人工解锁返回时仍为 `RUNNING`，随后完成全部 8388608 字节，与服务器源文件逐字节一致，生产视频校验通过，前台下载服务及通知正常退出。这是本地慢速 MP4 的真机熄屏验收，不替代长时间待机、厂商后台限制或 Android 15 系统时限验收。

## 仍需条件的验收

- 安全锁屏后正常人工解锁 PiP 两轮及熄屏后台下载专项已通过。
- 【按用户要求暂缓，未验收】Wi-Fi/移动网络切换：真机无 SIM；已通过真实 Wi-Fi 断开/恢复，不能替代移动网络。
- 【按用户要求暂缓，未验收】抖音及其他平台真实账号登录态恢复：目前仅验证匿名访问及受限提示，不填写账号、密码或验证码。
- 【按用户要求暂缓，未验收】W3C 网站挑战完成后的重新解析及完整下载。

暂缓项目保持未完成；本轮不将完整 P1 标为已验收。

## 收尾核对

最终代码审查无阻断发现，`git diff --check` 通过。本轮没有暂存、提交或推送。

真机恢复为空输入首页、无浏览器弹层及弹窗（`after.png/xml`）；Wi-Fi 为开启、移动数据仍关闭、双 SIM 为空、分辨率/密度/主题保持原值，应用前台服务为空。仅清理本轮 UUID 任务和测试缓存，测试任务历史及其下载目录为空，WebView/Cookies 和崩溃记录保留；未清空应用数据或卸载，LAN 8766 已停止。最终校验安装后没有新增目标应用崩溃，历史修复前的两个原生崩溃仍保留为 RED 证据，见 `final-device-state.txt`。

MDN 闭环后再次收尾：`after-mdn.png/xml` 确认真机空输入首页且无网页弹层；`final-mdn-device-state.txt` 记录测试任务、下载媒体和专属缓存均已清理，下载服务退出，原网络、显示尺寸、密度、主题未变；MDN 验收期间没有新增目标应用原生崩溃。新测试已审查，无阻断发现；本次同样未提交或推送。

人工解锁专项收尾：`after-manual-unlock.png/xml` 确认真实首页、空输入及无弹层，`final-manual-unlock-device-state.txt` 记录安全锁屏仍启用、当前正常解锁，原网络、主题和显示尺寸保持；仅清理测试记录、媒体和专属缓存，下载服务退出，无新增崩溃。两处专项测试最终审查无阻断发现，`git diff --check` 通过；本轮未修改生产行为，也未暂存、提交或推送。
