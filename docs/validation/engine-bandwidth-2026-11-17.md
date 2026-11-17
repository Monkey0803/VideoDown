# 引擎共享带宽预算验证（I5，2026-11-17）

## 结论

I5 已完成实现和自动化验证：引擎任务不再各自受一个 `ratelimit` 约束，而是由应用侧的**共享
令牌桶**统一记账；超出全局预算时，通过 `SIGSTOP`/`SIGCONT` 暂停整个引擎进程组，
多个 yt-dlp 进程合起来不超过全局限速。全局与单任务上限在任务运行中修改后，下一个进度事件
即生效；任务卡会说明当前是「任务限速」还是「全局限速」在限制它。Wi-Fi 切换、暂停、取消与
进程退出仍沿用原来的进程组终止路径，不留下后台流量。

## 实现范围

- `data/download/EngineBandwidthGovernor.kt`（新增）：
  - 跨会话共享的全局桶 + 每会话独立的单任务桶；桶允许透支，暂停时间按债务 ÷ 上限计算，
    单次暂停封顶 15 秒（只作为防病态参数的安全阀）；
  - 桶容量取 `max(16 KiB, rate/8)`，首次使用是满桶，小突发不必等待；
  - 上限为 0 时不做任何等待，行为与引入本类之前一致。
- `data/download/EngineRateControl`：一次引擎会话的限速来源，`globalLimit` / `taskLimit`
  都是每次进度事件重新读取的 provider，所以运行中改设置立即生效。
- `data/generic/VideoEngineRuntime.kt`：`execute` 接受 `rateControl`；进度事件把累计字节
  换算成增量交给预算，需要等待时 `SIGSTOP` 整个进程组、`delay`、`SIGCONT`（取消时在
  `finally` 里保证继续，随后照常终止进程组）；请求结束后释放该会话的单任务桶。
- `data/generic/EngineTransfer.kt` / `EngineMediaDownloader.kt`：接口增加 `rateControl`，
  下载请求把它传给运行时；本地音频提取不需要（没有网络）。
- `data/download/DownloadEngine.kt`：为每个引擎任务构造 `EngineRateControl`，
  全局与单任务上限都从当前设置读取；原来传给 yt-dlp 的静态 `ratelimit` 保留为单进程兜底。
- `presentation/history/HistoryScreen.kt` + `MainActivity`：任务卡在「跟随全局」时显示
  「全局限速 …」，任务自己有上限时显示「限速 …」（新增中英文案各一条）。
- `EngineBandwidthDeviceTest`（新增设备用例）：用约 2 MiB 的本地测试视频走随包 yt-dlp，
  分别验证单任务与两个任务并行时的整体速率与耗时。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**639 个用例通过**。
- 新增 `EngineBandwidthGovernorTest`（10 例）：不限速不等待；突发按债务暂停；单次大事件等待
  7.875 s（而不是被几百毫秒的上限截断）；暂停封顶；稳定在限额内不暂停；全局桶被两个会话共享；
  单任务桶不影响其它会话；运行中调高上限下一次上报即生效；释放会话后重新开始是满桶。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

新增 `EngineBandwidthDeviceTest` **2/2 通过**（真实 yt-dlp 子进程、真实 `SIGSTOP`/`SIGCONT`）：

1. 单任务、全局限速 256 KB/s：约 2 MiB 素材的整体速率被压在 1.8×上限以内，且耗时达到
   「字节 ÷ 上限」的 60% 以上——说明进程真的被暂停了，而不是只延迟了读取。
2. 两个任务并行、共享同一个 256 KB/s 全局预算：两路合计速率同样在 1.8×上限以内，总耗时
   达到「两份字节 ÷ 上限」的 60% 以上。各自限速时并行耗时只相当于一份，这条把它们区分开。

结果 XML 归档在
[device-api37/EngineBandwidthDeviceTest.xml](engine-bandwidth-2026-11-17/device-api37/EngineBandwidthDeviceTest.xml)
（2 条用例，0 失败）。

回归：`GenericVideoDeviceTest`（9 条，2 条按既有前提跳过）、`DownloadRetryStatusDeviceTest`
（3 条）、`TaskSearchDeviceTest`（2 条）通过——非限速路径不受影响。

### 构建

- `./gradlew compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin`
  通过；`assembleDebug`、`assembleRelease` 通过（未配置签名时 Release 保持未签名）。

## 未覆盖边界与已知取舍

1. **「FFmpeg 自行拉流阶段也遵守限速」在本应用中不适用**：引擎请求没有 `--downloader ffmpeg`
   （`EngineRequest.download` 与 `video_engine.py` 的选项里都没有），网络拉流始终由 yt-dlp
   自己完成，FFmpeg 只做本地合并与音频提取，不产生网络流量。若将来把拉流交给 FFmpeg，
   进度钩子拿不到字节数，这套预算无法计量，需要另做方案。
2. **暂停式节流是近似**：只在进度事件之间对齐预算，短期瞬时速率可能高于上限；测试断言用
   1.8× 上限作为容差。yt-dlp 自带的单进程 `ratelimit` 仍在，用来抹平突发。
3. **没有逐字节精确计量**：站点若不上报进度（例如某些分片或非常规协议），预算会少算这部分流量。
4. **真实平台未做并发限速测量**：只用本地 fixture 验证；真实站点/CDN 的吞吐与重连行为未测量。
5. **暂停时长上限 15 秒是安全阀**：极端小的上限 + 极大突发时仍会短暂超速，之后由后续事件偿还。
6. 本轮未运行完整 `connectedDebugAndroidTest` 套件；平板 / 折叠屏、API 26/28 未验证。
