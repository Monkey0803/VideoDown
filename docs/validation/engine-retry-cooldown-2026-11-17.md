# 引擎重试与持久化冷却验证（I1，2026-11-17）

## 结论

I1 已完成实现与自动化验证：直链、归档、Live Photo 与 yt-dlp / HLS / DASH / 分轨任务共用
同一套有界重试判定；引擎协议在不泄露 URL、Cookie 与原始日志的前提下返回 HTTP 状态和
`Retry-After`；下一次允许重试的时间与已用预算落库，进程被杀后自动继续不会提前请求；
任务卡恢复倒计时，用户点「立即重试」可以覆盖冷却。

本轮没有可用的真实 429/503 站点，引擎侧 `Retry-After` 解析只在单元层与假引擎层验证，
真实站点限流下的端到端表现仍属未覆盖边界（见文末）。

## 实现范围

- `domain/model/DownloadRetryPolicy.kt`（新增，纯 Kotlin）：`isRetryable` / `schedule` /
  `backoffMillis` / `cooldownRemaining`。判定与 H5 一致：存储类失败不重试；4xx 只有 429 重试；
  429/503/5xx 有 `Retry-After` 按服务端时间等待，缺失时走 1.5s 起翻倍的指数退避，
  单次等待封顶 15 分钟；一轮最多 3 次重试。
- `domain/model/DownloadRecord.kt`：新增可空默认字段 `nextRetryAtMillis`、`retryAttempt`、
  `retryHttpStatus`，以及 `withRetryCleared()`；旧 `download_history.json` 反序列化为
  「没有冷却、预算为 0」。
- `data/download/DownloadEngine.kt`：
  - 直链、Live Photo、归档、引擎四条传输路径都改成「策略判定 → 冷却落库 → 等待 → 继续」，
    失败预算跨重试与跨进程累计；
  - `autoResume` 在冷却到期前跳过候选，且不消耗自动恢复预算；`enqueueResume` 在拿到最新记录后
    再确认一次冷却；
  - 用户主动继续（按钮、批量「继续」、深链恢复）清空冷却与预算；新增 `retryNow`，
    「立即重试」通过一次性信号立刻放行正在等待的任务，不重建任务、不丢已下载字节；
  - 暂停、成功、重新下载都会清空冷却；失败终态清空冷却但保留失败分类与状态码。
- `data/generic/EngineTransfer.kt`（新增）：引擎下载的最小接口，让重试与冷却逻辑能用假引擎在
  JVM 上验证；`EngineMediaDownloader` 实现它，行为不变。
- `data/generic/VideoEngineRuntime.kt`：`VideoEngineException` 携带 `httpStatus` 与原始
  `retryAfter`，并用直链同一套解析器归一成毫秒。
- `assets/video_engine.py`：`_http_error_details` 沿异常因果链取 yt-dlp 的响应对象，只回传
  状态码与 `Retry-After`；503 归为 `NETWORK`。
- `presentation/history/HistoryScreen.kt` + `HistoryViewModel.kt`：
  倒计时优先用实时进度，其次回落到记录上落库的冷却（重启后仍可见）；
  冷却期间「更多任务操作」里出现「立即重试」（`action_retry_now`，中英各一条）。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**591 个用例通过**（连续 3 次全量复跑均通过）。
- 新增 `DownloadRetryPolicyTest`（11 例）：服务端 `Retry-After` 绝对到期时间、上限截断、
  指数退避、3 次预算、4xx 不重试、429/500/503 可重试、存储类与需要用户的分类不重试、
  旧记录按网络中断处理、冷却剩余时间归零。
- 新增 `EngineRetryCooldownTest`（5 例）：
  - 引擎 429 + `Retry-After: 0` 冷却后自动重试同一任务，且第二次请求沿用落库预算；
  - 落库冷却未到期时自动恢复不发请求（250ms 窗口内 0 次），`retryNow` 之后完成；
  - 正在等待 10s 冷却时 `retryNow` 立刻放行，任务完成且预算清零；
  - 直链 429（`Retry-After: 0`）第二次请求成功，`requestCount == 2`，完成记录冷却与预算清零；
  - 直链 429（`Retry-After: 60`）在冷却期间**没有**第二次请求，记录带 `retryAttempt=1`、
    `retryHttpStatus=429`，用户暂停后冷却被清空。
- `DownloadHistoryStoreTest` 新增 2 例：冷却/预算/状态码跨实例落盘；旧版本 JSON（缺少新字段）
  反序列化为无冷却、预算 0。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

- `DownloadRetryStatusDeviceTest` **3/3 通过**：
  - 运行中任务显示「等待重试 · N 秒」（原有断言）；
  - 进程重启后只剩记录的重试冷却时，暂停任务仍显示倒计时；
  - 冷却期间「更多任务操作 → 立即重试」确实调用下载器 `retryNow`。
- 运行命令：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.DownloadRetryStatusDeviceTest`
  （只跑了该类，未跑完整设备套件）。结果 XML 归档在
  [device-api37/DownloadRetryStatusDeviceTest.xml](engine-retry-cooldown-2026-11-17/device-api37/DownloadRetryStatusDeviceTest.xml)。

### 构建

- `./gradlew compileDebugKotlin`、`./gradlew compileDebugUnitTestKotlin`、
  `./gradlew compileDebugAndroidTestKotlin` 通过。

## 未覆盖边界

1. **真实站点限流未端到端验证**：没有可用的真实 429/503 媒体链接，引擎侧
   `_http_error_details` 在真实 yt-dlp `HTTPError` 上的行为只经过代码评审；假引擎覆盖的是
   Kotlin 侧的判定与落库。真实响应里没有 `response` 对象时只能回落到指数退避。
2. **HLS / DASH / 分轨的真实 429/503** 未单独构造；它们与普通引擎任务共用同一条重试路径。
3. **多引擎进程之间的冷却共享**不在 I1 范围（属于 I5）：「落库冷却」是按任务记录的，
   同一记录不会并发跑两个引擎进程，但不同任务之间的全局限流/冷却协调仍由 I5 追踪。
4. **`Retry-After` 上限 15 分钟**：服务端要求更长等待时会被截断，之后仍可能再次收到 429。
5. 本轮一次全量复跑中出现过 `QueueReorderTest` 读历史文件为空、以及 `retryNow` 用例超时的
   偶发失败；`retryNow` 的信号登记顺序缺陷已修复（先登记再落库），修复后连续 3 次全量复跑
   与 2 次相关类定向复跑均通过。`QueueReorderTest` 的「读到空历史」现象在修复后未再复现，
   根因未定位，按未覆盖边界记录。
6. 平板 / 折叠屏未验证；倒计时只在手机竖屏布局下人工与自动核对。
