# X 失败归因与原生登录态解析验证记录（2026-11-18）

> 关联：[README 的「X（Twitter）解析」](../../README.md#xtwitter解析) ｜ [X 登录态复用验证](x-login-session-2026-11-16.md) ｜ [TODO](../TODO.md)
>
> 本轮目标：一条「X 纯图片、且被敏感内容门禁挡住」的推文被显示成「当前内容暂不支持 / DRM 和直播无法保存」。
> 三件事一起修：① 失败原因归因，不再让兜底引擎的通用结论盖掉原生的具体原因；② 原生 GraphQL 支持本机
> WebView 登录态，让这类推文（含纯图片帖）真的能解析；③ `TweetTombstone` 归到「需要登录」，给登录/重登入口。

## 1. 结论

- 三项改动全部完成：失败分类、原生登录态解析、登录恢复入口。
- JVM **673** 例通过（新增/修改的 X 用例见第 5.1 节）；设备 `XPostRecoveryDeviceTest` **3/3** 通过。
- 同一台模拟器、同一份 APK、**同一条真实链接**（未登录）现在显示「需要登录或验证」＋「登录 X 后重试」，
  不再是「DRM/直播不支持」（截图见第 5.4 节）。
- `XLoginSessionDeviceTest` **6/7**：`settingsRowWordsUnsignedState` 仍是既有失败，与本改动无关——
  在纯 HEAD 上同样失败（第 5.3 节给了复现记录），原因是「哔哩哔哩登录」行复用了同一个「未登录」字符串。
- **未覆盖**：真实账号登录、已登录 GraphQL 在真实 X 上的接受度、真实敏感视频的下载、真机与 API 26/28、全量设备回归（第 6 节）。

## 2. 根因（对同一条链接的取证）

链接：`https://x.com/ShexyoArt/status/2122404334675665059`（`?a=…&s=20` 只影响分享来源，不参与解析）。

1. 帖子本身**没有视频**，只有一张 1300×1300 的图片，且被标记为敏感内容：
   第三方镜像返回 `media.all = [{type: photo, 1300×1300}]`、`possibly_sensitive: true`。
2. 匿名 GraphQL（App 里同一个 guest token、同一套 `variables/features`）对这条只返回
   `{"__typename":"TweetTombstone"}`；对照组（同 token 请求一条普通视频推文）正常返回 `__typename: "Tweet"`，
   说明游客通道本身没坏，是这条内容被门禁挡住。嵌入页接口 `cdn.syndication.twimg.com/tweet-result` 同样返回 tombstone。
3. 原生解析把它判成 `RESTRICTED`（`allowFallback = false`），随后兜底引擎 yt-dlp 2025.10.14 报
   `ERROR: [twitter] 2122404334675665059: No video could be found in this tweet`。
4. 引擎错误被 `classify_failure` 归到 `UNSUPPORTED`，而原来的失败优先顺序是「引擎失败优先」，
   于是引擎的通用结论盖掉了原生的 `x:restricted`，界面显示成 `failure_unsupported_*`——正是那句
   「DRM 和直播无法保存」。日志里既没有 DRM 证据，也没有直播证据，属于**归因错误**。

## 3. 设计要点

1. **失败分类新增 `NO_VIDEO`**（`domain/model/DownloadFailure.kt`）：页面/帖子里没有视频与「DRM/直播/不支持的格式」
   分开。`failure_no_video_title/body` 中英成对，重试策略把 `NO_VIDEO` 当作不可自动重试（重试不会变出视频），
   任务卡也不再提供「重新下载」。
2. **引擎也会说「没有视频」**：`video_engine.classify_failure` 把 `no video could be found` 归为 `NO_MEDIA`，
   `VideoEngineRuntime` 映射到 `NO_VIDEO`，`engineKindRes` 给出专用文案。`NO_VIDEO` 这个引擎类别保留给
   「下载结果缺少视频轨」（`EngineMediaDownloader`），两者不混用。
3. **原生 typed 原因优先**：`MultiPlatformVideoRepository.parseX` 在引擎只给出「不支持 / 没有视频」时，
   保留原生更准确的原因（`x:no_video` / `x:login_required` / `x:restricted` / `x:unavailable` / `x:not_found` /
   `x:unsupported_format`）；引擎给出具体原因（授权、访问受限、限流……）时仍以引擎为准，避免退化成另一种误报。
4. **tombstone = 需要登录**：`XParseFailure.RESTRICTED` 的分类改为 `LOGIN_REQUIRED`（原因文案仍是「受限」），
   `needsLogin()` 也认这个原因。界面因此出现「登录 X 后重试」；有登录态仍被拒时升级成 `x:session_auth_failed`，
   提示登录可能失效或账号看不到。
5. **原生 GraphQL 带登录态**：`XVideoHttpClient` 增加可选的登录 Cookie 提供者；`XVideoRepository.parseWithSession()`
   用 `Cookie` + `x-csrf-token`（取自 `ct0`）+ `x-twitter-auth-type: OAuth2Session` 请求同一个 `TweetResultByRestId`。
   `BrowserSessionStore.xGraphqlCookieHeader()` 只取 `https://x.com/` 这一源（不把 `twitter.com` 域的 Cookie
   发给 `x.com`），并要求 `auth_token` + `ct0` 同时存在；不完整等同未登录。
6. **解析顺序**：原生匿名 →（需要登录时）原生登录态 → 引擎登录态 → 引擎匿名。公开推文保持完全匿名，
   只有原生明确说「需要登录」时才动 Cookie；Cookie 不缓存、不落库、不进日志，Posts 内容仍由图片/媒体直链下载。

## 4. 改动清单

主代码（`app/src/main/java/com/videodown/douyin/`）：

- `domain/model/DownloadFailure.kt`：新增 `DownloadFailureKind.NO_VIDEO`，动作与 `canRetryDownload` 一并归类。
- `domain/model/DownloadRetryPolicy.kt`：`NO_VIDEO` 列入不可自动重试。
- `presentation/FailureText.kt`：新分类的标题/正文映射（穷尽 `when`）。
- `core/ErrorResources.kt`：引擎 `NO_MEDIA` 文案映射。
- `data/remote/XParseFailure.kt`：`RESTRICTED` 的语义说明 + 各原因的界面分类（`NO_VIDEO` / `LOGIN_REQUIRED`）。
- `data/remote/XVideoHttpClient.kt`：可选登录 Cookie 提供者、`sessionCookieHeader()`、`graphqlWithSession()`、
  `XSessionCookies`；匿名路径行为不变。
- `data/repository/XVideoRepository.kt`：`parseWithSession()`；所有 X 失败带上平台与分类。
- `data/repository/MultiPlatformVideoRepository.kt`：四段式顺序、原生登录态尝试、失败优先级的归因规则。
- `data/generic/BrowserSessionStore.kt`：`xGraphqlCookieHeader()`（只取 x.com 源，要求 auth_token + ct0）。
- `data/generic/VideoEngineRuntime.kt`：`NO_MEDIA` → `NO_VIDEO`。
- `core/ServiceLocator.kt`：接线 `xRepository` 与 `xNativeSession`。
- `assets/video_engine.py`：`classify_failure` 增加 `no video could be found` → `NO_MEDIA`。
- `res/values/strings.xml`、`res/values-en/strings.xml`：新增 `failure_no_video_*`、`engine_no_media`（中英成对）。

测试：

- `XSessionParseTest`（JVM 6 例）：无 Cookie 不发请求、残缺 Cookie 视为未登录、认证请求头、纯图片帖解析成图片条目、
  会话下 tombstone 的原因、401 归为 `x:session_auth_failed`。
- `MultiPlatformXSessionTest`（JVM 14 例，+5）：受限帖先走原生登录态、无登录态时保留 `x:restricted`、
  无视频帖保留 `x:no_video`、登录态确认无视频时仍保留 `x:no_video`、原生登录态被拒升级成登录问题；
  原有顺序用例全部保持通过。
- `XVideoParserTest`（JVM 15 例，+1）：类型与界面分类的映射。
- `DownloadFailureGuidanceTest` / `DownloadRetryPolicyTest`：新分类的文案完整性与重试边界。
- `app/src/test/python/test_video_engine_errors.py`：`no video could be found` → `NO_MEDIA`。
- `XPostRecoveryDeviceTest`（设备 3 例）：受限帖的登录入口、无视频帖的专用文案、WebView Cookie + CSRF 的
  已登录请求与图片条目。

文档：`README.md`（X 解析与已知限制）、`docs/TODO.md`、本文件。

## 5. 验证

### 5.1 环境

| 项 | 值 |
| :--- | :--- |
| 主机 | macOS，JDK 用 Android Studio JBR（`openjdk 25.0.3`），Gradle 9.7.1，AGP 9.4.1 |
| 设备 | AVD `Pixel_10_Pro`，Android 17 / API 37，arm64；debug 包（`com.videodown.douyin.debug`） |
| 引擎 | 随包 yt-dlp 与 `video_engine.py`（本轮只改失败分类） |
| 网络 | 设备用例全部离线；第 5.4 节的真实链接测试使用模拟器网络 |

### 5.2 JVM

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
# BUILD SUCCESSFUL — 673 tests completed, 0 failures
```

分项：`XSessionParseTest` 6、`MultiPlatformXSessionTest` 14、`XVideoParserTest` 15、`DownloadFailureGuidanceTest` 3。

Python 失败分类（本轮新增/改动的用例；整个文件在 HEAD 上就有 6 个既有错误——测试用 AST 抽取函数时
没有带上 `_http_error_details`，与本轮无关，已在 HEAD 上复现）：

```bash
python3 - <<'EOF'
import importlib.util, unittest
spec = importlib.util.spec_from_file_location("t", "app/src/test/python/test_video_engine_errors.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
names = ["test_posts_without_video_keep_their_own_category",
         "test_format_protection_and_unknown_cases",
         "test_http_categories_are_distinct",
         "test_storage_access_failures_keep_storage_recovery"]
suite = unittest.TestSuite([m.EngineErrorClassificationTest(n) for n in names])
unittest.TextTestRunner(verbosity=2).run(suite)
EOF
# Ran 4 tests ... OK
```

### 5.3 设备

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XPostRecoveryDeviceTest
# tests 3, failures 0

./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XLoginSessionDeviceTest
# tests 7, failures 1 —— settingsRowWordsUnsignedState（既有失败）
```

`settingsRowWordsUnsignedState` 的失败与本次改动无关，依据：

- 该断言用 `onNodeWithText("未登录")` 匹配唯一节点，但设置页的「哔哩哔哩登录」行复用同一个字符串，
  当前页面必然有两个节点；
- 把工作区 stash 回纯 HEAD 后单独运行该用例，同样以「Expected exactly '1' node but found '2'」失败；
- 本轮没有修改 `SettingsScreen`、设置页文案或该测试。

### 5.4 真实链接（匿名）

模拟器上用 `ACTION_SEND` 把该链接送进 App（未登录 X）：

```
text="Sign-in or verification needed"
text="Open the original page, sign in or verify, then parse again using the web session and pick a video."
text="Sign in to X and retry"
```

对比修复前的用户截图：同一链接显示「This content is not supported / DRM-protected and live content cannot be saved」。
证据：`home-anonymous-login-required.png` 与同名 `.txt`（uiautomator 文本转储）。

## 6. 未覆盖边界

- **真实账号登录后的已登录 GraphQL 未被验证**：本机没有可用于自动化的 X 账号，`x-csrf-token` 与
  `x-twitter-auth-type` 按网页客户端的行为实现，用本地 stub 断言了请求头与解析结果；真实 X 是否接受
  这套请求、是否还要求 `x-client-transaction-id` 等头，需要在真实账号上复验（失败时会落到 `x:session_auth_failed`）。
- 真实敏感内容视频（非图片）的下载未验证；这类媒体是否要求额外请求头取决于 X 的 CDN。
- 登录后重试的端到端界面（点「登录 X 后重试」→ 登录页 → 自动重试）由既有 `parseErrorOffersXLoginAndRetriesAfterIt`
  覆盖（用 `about:blank` 代替真实登录页），真实登录页未驱动。
- 真机、API 26/28、平板/折叠屏未验证；全量设备回归未运行（只跑了与本次改动直接相关的两个类）。
- `TweetTombstone` 区分不出「未登录」「账号无权查看」「地区限制」，文案按需要登录处理；属于已知取舍。

## 7. 手动复验

1. 未登录 X 时解析该链接：应显示「需要登录或验证」＋「登录 X 后重试」，而不是「当前内容暂不支持」。
2. 「我的 → X（Twitter）登录」登录后重试：应解析出 1 张图片条目（`pbs.twimg.com/...?name=orig`），可保存/打包/看图。
3. 若登录后仍被拒：应显示 `x:session_auth_failed` 的文案（登录可能失效或账号看不到），不显示网络错误。
