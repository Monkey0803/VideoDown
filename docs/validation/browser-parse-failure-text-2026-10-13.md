# 网页解析面板不再"什么都不说" + 视频号说准（2026-10-13）

背景：用户拿一条视频号分享链接 `https://weixin.qq.com/sph/AhMkUsjhOF` 反馈"为什么不能解析"，随后贴出
网页解析面板的截图——**面板里连一句解释都没有**。这份记录处理的就是这两件事。

## 一、视频号为什么解析不了（先确认事实）

1. `weixin.qq.com` 不在 `VideoLinkExtractor.platformForHost` 的已知平台里 → 落到 `GENERIC`，交给随包引擎。
2. 随包 `yt-dlp 2026.08.19`（`assets/engine/VERSION.txt`）里**没有任何 weixin/wechat 提取器**
   （对二进制逐个字符串搜过，命中 0）。通用路径对它是"无 extractor"。
3. 用无头 Chrome 渲染分享页：正文只有标题、作者、日期、播放数据，底部一句**「可扫码前往微信观看此内容」**。
   即播放地址由微信客户端内动态签发，页面里没有 mp4/m3u8 直链；连"打开网页找媒体"也扫不到东西。

结论：不是解析流程的 bug，而是这个平台在当前能力下不可能解析。**所以正确做法是把话说准，而不是让用户
去试那条走不通的路。**

## 二、真正的 bug：失败文案是空的

`GenericVideoRepository.parse` 失败时返回的是 `AppError.Parse(message = "", reasonKey = "engine:…"/"generic:no_video")`
——文案在 `reasonKey` 里，`message` 是空串（这是仓库既有的"错误存 key 不存句子"设计）。
而 `BrowserAssistScreen` 是**唯一**一处直接把 `error.message` 当文案显示的地方：

```kotlin
is AppResult.Failure -> message = result.error.message   // ← 空串，状态行一片空白
```

于是「使用网页登录态重新解析」失败后，底部状态行什么都不显示——正是用户截图里的现象。
首页那条路没问题（`HomeError.FromApp` → `appErrorText`，统一入口）。

## 三、改动

- **`presentation/browser/BrowserAssistScreen.kt`**：
  - 失败文案改走统一入口 `appErrorText(context, error)`；
  - 扫描改为**等本轮探测全部结束再下结论**（`probes.joinAll()` + 代次号防止旧扫描串场）：有候选说
    "已检查页面资源…"，一个都没有就说「本页没有发现可下载的视频…」，不留白；
  - 状态行加测试标签 `browser-status`。
- **`presentation/browser/BrowserStatus.kt`（新）**：把"该说哪句话"抽成纯函数 `browserStatusRes`，
  优先级与四种组合可在 JVM 上穷举。
- **`data/remote/VideoLinkExtractor.isWeChatChannelsLink`（新）**：识别 `weixin.qq.com/sph/…` 与
  `channels.weixin.qq.com`；只用于**失败时说哪句话**，不拒绝解析（将来页面真给直链照样能解析）。
- **首页**：`HomeError.WeChatChannels` + `HomeScreen` 文案；解析失败且输入含视频号链接时用它。
- **网页解析面板**：解析失败目标是视频号时用同一句。
- 新增文案（中英各三处）：`engine_wechat_channels`、`browser_nothing_found`、`browser_channels_no_media`。
- `README.md`「已知限制」补两条（动过 `data/remote/`，按仓库约定必须跟进）。

## 四、验证

### JVM

`./gradlew testDebugUnitTest` → 见下方结果段。本轮新增两个类：

- `VideoLinkExtractorChannelsTest`（6 例）：真实短链/网页地址、带跟踪参数、`mp.weixin.qq.com` 不算视频号、
  形似域名（`weixin.qq.com.evil.example`）不算、垃圾输入不抛异常、视频号仍留在 `GENERIC`。
- `BrowserStatusTest`（5 例）：显式消息优先；未出结论/正在解析时只给初始提示；有候选说"已检查资源"；
  扫完为空必须说"没找到"，视频号换成专门那句。

### 设备（模拟器 API 37）

`BrowserAssistDeviceTest` **2/2 通过**，新增
`aPageWithoutMediaExplainsItselfInsteadOfStayingSilent`（本地无媒体页）：

1. 页面加载完自动扫描 → 断言出现「本页没有发现可下载的视频…」（**新行为：以前这里什么都不说**）；
2. 点「使用网页登录态重新解析」→ 断言出现被解析过的失败文案，且状态行（`browser-status`）非空白。

截图：[browser-nothing-found.png](browser-parse-failure-text-2026-10-13/browser-nothing-found.png)（扫描结论）、
[browser-parse-failure-text.png](browser-parse-failure-text-2026-10-13/browser-parse-failure-text.png)（失败文案，
正是用户说"没有提示"的那句「未找到可下载的视频，可打开网页登录或播放后查找」）。

**红灯**：只把失败文案那行改回 `result.error.message`（其余不动），该用例失败
（30s 等不到解析后的文案），另一条用例照旧通过。恢复后重新跑绿。

### 回归

模拟器 API 37 全量 `connectedDebugAndroidTest`：**200 条，36 条非通过 = 23 条按约定跳过的 opt-in +
13 条真实失败**，逐条判定如下（没有本轮引入的失败）：

| 失败 | 判定 |
| :--- | :--- |
| `AutoGallery`、`P2CompletionNotification`、`TaskNotificationDeviceTest` ×2 | 环境：**冷启重装后通知授权丢了**（本轮为跑通知用例给应用开过 `POST_NOTIFICATIONS`/appop，重装即失效），这几条一直以"通知必须真的开着"为前置断言 |
| `P2PhysicalBackgroundAudioDeviceTest` | 环境：需要 `manual_unlock` 人工解锁选项 |
| `P2ReleaseUiBridgeDeviceTest` ×3 | 环境：模拟器上没装 release 包 |
| `BackgroundDownloadDeviceTest#unexpectedServiceStop…`、`DynamicColor`、`EmptyStateIllustration`、`WeiboGif` | 既有：前几轮已用回退/基线逐条证明与设备状态相关 |
| `P2LibraryOrganizationDeviceTest#actualRenameAndCategorySurviveRotationAndFilterSavedFile` | **全量里的偶发**：同一台机器单独重跑该类 **4/4 通过**（该断言是上一批"弹窗页脚不被键盘挡住"的断言，与本轮改动无关） |

本轮改动集中在解析文案与网页面板，`BrowserAssistDeviceTest` 在这份全量里同样 **2/2**。

## 五、边界（未覆盖，如实记录）

- **视频号页面本身在设备用例里没跑**：它需要真实微信页面（网络 + 微信侧行为），用例用的是本地无媒体页。
  视频号那句话的**选择逻辑**在 JVM 上穷举（`BrowserStatusTest` + `VideoLinkExtractorChannelsTest`），
  但"真机打开视频号页面时显示这句话"没有端到端证据。
- **视频号解析本身没有实现，也没有立 TODO**：需要微信登录态与客户端内签名，第三方应用拿不到
  （视频号没有网页登录入口），因此按不可行动处理，只在 README「已知限制」里写明。若将来要做，先出
  `docs/research/` 的可行性结论。
- **`BrowserMediaDetector` 的分片过滤没动**：`.m4s`/`.ts` 仍被排除（单个分片不是完整视频），视频号即便
  被嗅探到分片也不会被当成候选——本轮没有改这个策略。
- 内置网页登录对视频号无意义（无网页登录入口）；面板照样提供按钮，不做特殊禁用。

## 六、证据清单

```
docs/validation/browser-parse-failure-text-2026-10-13/
├── browser-nothing-found.png                 扫描完一个候选都没有：给出结论
├── browser-parse-failure-text.png            解析失败：显示被解析过的失败文案
└── emulator-api37/
    ├── BrowserAssistDeviceTest.xml           2/2（含新增的无媒体页用例）
    ├── BrowserAssistDeviceTest.before.xml    回退失败文案解析后的红灯
    ├── jvm-summary.xml                       全量 JVM
    └── connectedAndroidTest.full.xml         全量回归
```

复现：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests "com.videodown.douyin.data.remote.VideoLinkExtractorChannelsTest" \
  --tests "com.videodown.douyin.presentation.browser.BrowserStatusTest"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.BrowserAssistDeviceTest
```
