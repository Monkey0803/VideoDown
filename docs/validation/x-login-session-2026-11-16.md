# X（Twitter）登录态复用与有效期验证记录（2026-11-16）

> 关联：[README 的「X（Twitter）解析」](../../README.md#xtwitter解析) ｜ [TODO](../TODO.md) ｜ 计划：X 登录态缓存与自动复用
>
> 本轮目标：登录一次 X 后，需要授权的推文自动复用本机登录态；**有效期完全跟随站点**，失效时才提示重新登录。

## 1. 结论

- 功能已完成：识别、导出、界面入口、解析路由、登录后重试、失效提示俱全。
- JVM 462 用例通过；设备用例 `XLoginSessionDeviceTest` **6/6** 通过；两阶段 force-stop 持久化验证通过。
- 全量设备回归 207 用例中 13 项失败，**全部与本改动无关**；其中 3 项在纯 HEAD 基线复现，其余是环境/前置条件缺失（详见第 6 节）。
- **未覆盖**：真实 X 账号登录、真实受保护推文下载、真机（本轮只有 API 37 模拟器）。第 8 节给出手动步骤。

## 2. 设计要点（有效期部分）

1. **唯一权威是系统 WebView 的 CookieManager**。`XSession.signedIn` 只判断 `auth_token` + `ct0` 是否存在且非空，**不判断、也不记录有效期**：Cookie 过期后系统不再返回它，判定自然为 false。
2. **App 侧没有任何 TTL**：没有「最近校验时间」、没有缓存布尔值；`BrowserSessionStore.xSignedIn()` 每次现查。
3. **不提前清除**：启动（`ServiceLocator.bootstrap()` 只删临时 Cookie 导出文件）、升级、解析失败、下载失败都不删除 Cookie；唯一清除入口仍是设置里的「清除网页登录态」。
4. **不缩短导出有效期**：`BrowserSessionStore.export()` 写出的临时 Netscape 文件里 `expires=0`（会话语义），文件只服务一次请求、`finally` 删除；随包 yt-dlp 以 `ignore_discard/ignore_expires=True` 载入，不会因此提前失效，也**不会把引擎侧的 Set-Cookie 写回 WebView**。
5. **落盘时机**：登录网页每次 `onPageFinished` 先 `CookieManager.flush()` 再判状态，关闭弹窗时再 flush 一次，保证刚拿到的 Cookie 能活过进程退出。
6. **失效提示分两种**：
   - Cookie 已过期/消失 → 设置显示「未登录」，解析给 `x_login_required` ＋「登录 X 后重试」；
   - Cookie 还在但服务端拒绝 → `x:session_auth_failed`，文案同时说明「登录可能已失效，或该账号没有查看权限」并指引重新登录（网络层无法区分这两种成因，因此不谎称「已过期」）。

## 3. 改动清单

主代码（`app/src/main/java/com/videodown/douyin/`）：

- 新增 `data/generic/XSession.kt`：登录页与来源、Cookie 名、纯函数 `signedIn(cookieHeader)`。
- 新增 `data/generic/SessionVideoParser.kt`：`fun interface`，`null` 表示没有可用登录态。
- `data/generic/BrowserSessionStore.kt`：新增 `suspend fun xSignedIn()`（现查，不缓存）。
- `data/remote/XParseFailure.kt`：新增 `SESSION_AUTH_FAILED`（key `x:session_auth_failed`）。
- `core/ErrorResources.kt`：该失败类型的文案映射（穷尽 `when` 强制）。
- `data/repository/XVideoRepository.kt`：新增 `resolve(url)`（`t.co` 展开成推文地址，供登录态重试与结果合并）。
- `data/repository/MultiPlatformVideoRepository.kt`：`parseX` 改为「原生匿名 → 引擎匿名 → 登录态重试」的顺序调度；登录态仅在匿名拿不到结果时使用；登录态被 AUTH 拒绝时返回 `x:session_auth_failed`。
- `core/ServiceLocator.kt`：接线 `sessionFallback = { if (browserSessions.xSignedIn()) genericRepository.parse(url, browserSession = true) else null }`。
- 新增 `presentation/login/XLoginScreen.kt`：全屏 WebView 登录页，安全设置与网页辅助一致；每次加载完成 flush + 判状态。
- `presentation/home/HomeScreen.kt`：失败恢复区新增「登录 X 后重试」，登录完成后用同一份输入重新解析。
- `presentation/settings/SettingsScreen.kt`：引擎卡片新增「X（Twitter）登录」行（已登录/未登录）＋有效期说明；清除登录态后刷新状态。
- `res/values/strings.xml`、`res/values-en/strings.xml`：新增 11 条文案（中英成对），`x_login_required` 改为指向登录。

测试：`XSessionTest`（JVM 5 例）、`MultiPlatformXSessionTest`（JVM 9 例）、`XVideoRepositoryResolveTest`（JVM 4 例）、`XLoginSessionDeviceTest`（设备 6 例）、`XLoginPersistenceDeviceTest`（两阶段，opt-in）。

文档：`README.md`、`docs/TODO.md`、本文件。

## 4. 验证环境

| 项 | 值 |
| :--- | :--- |
| 主机 | macOS，JDK 用 Android Studio JBR（`openjdk 25.0.3`），Gradle 9.7.1，AGP 9.4.1 |
| 设备 | AVD `Pixel_10_Pro`，Android 17 / API 37，ABI universal（`-Pabi=universal`） |
| 应用 | debug（`com.videodown.douyin.debug`） |
| 网络 | 设备用例全部离线：Cookie 直接写入系统 CookieManager，登录网页用 `about:blank` 代替 |

> 验证在主工作区完成，工作区里同时包含本轮并行开发的另一项改动（图片/压缩包/查看器）。早期该改动未完成时 `compileDebugAndroidTestKotlin` 不通过，因此曾先在 `HEAD + 本改动` 的独立 worktree 中跑过一轮；第 5、6 节记录的是**主工作区最终结果**（两项改动都在），两次结果一致。

## 5. 验证结果

### 5.1 JVM 单测

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
# BUILD SUCCESSFUL — 510 tests completed（含并行开发的图片/压缩包用例）
```

本轮新增 18 例（`XSessionTest` 5、`MultiPlatformXSessionTest` 9、`XVideoRepositoryResolveTest` 4），全部通过。

> 偶发说明：之前在独立 worktree 并行执行时，`QueueReorderTest` 与 `XRepositoryIntegrationTest > guest failure graphql rate limit and malformed data use embed fallback` 各出现过一次失败，单独重跑立即通过；属既有用例的时序偶发，与本改动无关。主工作区本轮 510 例为一次全绿。

### 5.2 设备用例 `XLoginSessionDeviceTest`（6/6 通过）

```bash
./gradlew connectedDebugAndroidTest -Pabi=universal \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XLoginSessionDeviceTest
# tests 6  real failures 0
```

| 用例 | 验证内容 |
| :--- | :--- |
| `signedInFollowsLiveCookieStateAndSiteExpiry` | 只有 `auth_token` 不算已登录；补 `ct0` 才算；把 `auth_token` 设为已过期时间后立即变回未登录（站点到期即失效，App 无 TTL） |
| `bootstrapNeverClearsStoredSession` | 调 `ServiceLocator.bootstrap()` 后登录态仍在（启动流程不提前清除） |
| `exportCarriesSessionForXAndApiOrigins` | 带 `HttpOnly` 的 Cookie 能被 `getCookie` 读到；临时文件里同时出现 `x.com` 的 `auth_token` 与 `api.x.com` 的 `ct0` |
| `settingsRowWordsUnsignedState` | 设置行在无登录态时显示「未登录」 |
| `settingsRowReportsStoredSession` | 写入登录态后设置行显示「已登录」 |
| `parseErrorOffersXLoginAndRetriesAfterIt` | 解析失败出现「登录 X 后重试」→ 弹窗认出登录态 → 点「完成」用同一份输入重试，成功显示结果（仓库层被调用 2 次） |

### 5.3 跨进程持久化（两阶段 force-stop）

`connectedDebugAndroidTest` 在每次任务结束时卸载 APK，无法在两次 Gradle 调用之间保留数据，因此两阶段用手动安装 + `am instrument`：

```bash
./gradlew installDebug installDebugAndroidTest -Pabi=universal

# 阶段 1：写入 Cookie 并 flush
adb shell am instrument -w -e class com.videodown.douyin.XLoginPersistenceDeviceTest -e phase seed \
  com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner

# 真实杀掉进程
adb shell am force-stop com.videodown.douyin.debug
adb shell pidof com.videodown.douyin.debug     # 输出为空

# 落盘证据
adb shell run-as com.videodown.douyin.debug ls -l app_webview/Default/Cookies
adb exec-out run-as com.videodown.douyin.debug cat app_webview/Default/Cookies | strings | grep -E "auth_token|ct0"

# 阶段 2：新进程里断言仍然已登录
adb shell am instrument -w -e class com.videodown.douyin.XLoginPersistenceDeviceTest -e phase verify \
  com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner
```

实际输出（节选）：

```
app_webview/Default/Cookies  24576 bytes
x.comct0csrf/
x.comauth_tokenfixture/
OK (1 test)          # seed
pid after force-stop: (空)
OK (1 test)          # verify
```

结论：登录态确实按站点 Cookie 落盘并跨进程存活；App 没有在启动时把它清掉，也没有引入自己更短的有效期。

### 5.4 构建产物

```bash
./gradlew assembleDebug -Pabi=universal
# app/build/outputs/apk/debug/videodown-universal-debug.apk
```

## 6. 全量设备回归（220 用例）

主工作区执行 `./gradlew connectedDebugAndroidTest -Pabi=universal`：

- 220 个用例，**23 项按 opt-in 跳过**（`AssumptionViolated`：`live_*`、两阶段 force-stop、release UI、人工解锁、物理网络等）；
- **9 项失败，全部落在本改动未触碰的模块**：
  - **早前已在纯 HEAD 基线复现**：`EmptyStateIllustrationDeviceTest > theSettledShapeIsTheRoundedSquare`（终态仍近似圆形）；同一轮的 `WeiboGifDeviceTest > selectingAnotherGifDownloads…` 与 `BackgroundDownloadDeviceTest > unexpectedServiceStop…` 本轮已通过，属既有偶发。
  - **前置条件未满足**：
    - 需要已安装 release APK / 前台为应用：`P2ReleaseUiBridgeDeviceTest` 的 `independentlyReadActuallyExportedReleaseMedia`、`preciselyEnterPublicSourceInVisibleReleaseUi`、`actualReleaseUiClickAndCapture`；
    - 需要人工解锁 opt-in：`P2PhysicalBackgroundAudioDeviceTest > actualSystemAudioControlsAndSecureScreenOffReturnPreservePlayback`；
    - 通知环境：`P2CompletionNotificationDeviceTest > productionCompletionSetting…`（10s 超时）、`TaskNotificationDeviceTest > independentCardsPauseOnlyTheirOwnTask…`（15s 超时）。
  - **测试间状态污染（非回归）**：`DownloadSettingsDeviceTest > enabledCompletionPreferencePostsIndependentNotification` 期望中文「下载完成」却拿到英文 `Download complete`——整类单独执行时**通过**（`exit=0`），说明是同一批次里语言类用例切换 App 语言后未复原，与本改动无关。
  - **环境相关**：`DynamicColorDeviceTest > dynamicColorIsOfferedOnlyWhereSupportedAndRepaintsTheTheme`（Material You 品牌色断言，干净 worktree 上也同样失败）。
- 与本改动直接相关的用例全部通过：`XLoginSessionDeviceTest` 6/6（整类单独执行同样 6/6）、`XVideoDeviceTest`（非 live 部分）、`HomeResultBackDeviceTest`、`ResultSelectionDeviceTest`、`P1RecoveryNavigationDeviceTest`。

## 7. 未覆盖边界（如实记录）

1. **真实 X 账号登录**：登录流程涉及 X 的验证码/邮件验证与账号风控，无法自动化；设备用例只验证「本机已有登录态」之后的全部行为。
2. **真实受保护/敏感推文**：需要真实账号与真实网络，本轮未跑。因此「登录态确实能让引擎拿到受保护内容」属于待手动确认项。
3. **服务端拒绝的成因**：`auth_token` 尚在但被拒绝时，接口不区分「过期」与「账号无查看权限」，所以只能用一条同时说明两种可能的文案。
4. **真机**：本轮只有 API 37 模拟器；Android 11 / API 30 真机未跑（WebView Cookie 行为与 `flush()` 语义相同，但未实测）。
5. **仅登录态路径可用时**，多视频推文的 `/video/N` 可能回落到第一条（引擎按整页枚举），原生路径不受影响。
6. **其他平台**：登录态自动复用只接在 X 上；微博/快手等仍走网页辅助手动重解析。

## 8. 手动验证步骤（有真实账号时）

1. 「我的 → X（Twitter）登录」，在内置网页完成登录，看到「已检测到登录态，可以完成」后点「完成」。
2. 杀掉 App 再打开 →「我的」里该行仍显示「已登录」（有效期由 X 决定，App 不设过期时间）。
3. 粘贴一条需要登录/受保护或敏感内容的 X 链接 → 解析成功，选择画质下载并播放，确认文件完整。
4. 在系统浏览器或内置网页里退出 X / 让会话失效后，再解析同一条链接：应出现「已使用本机保存的 X 登录态，但仍无法访问…」并可用「登录 X 后重试」重新登录；若 Cookie 已过期，则该行先变为「未登录」，解析给出「需要登录」并同样提供登录入口。
