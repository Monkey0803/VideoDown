# 后台下载稳定性与失败反馈（2026-10-09）

## 改动

- 下载任务在传输前等待本次前台服务启动确认。每次启动有独立请求标识，启动被系统拒绝、进入前台失败或超过 4 秒未收到确认均明确失败，不继续发起 HTTP 请求。
- 服务中断时保存最后进度；直链以已关闭写入流后的实际断点长度为准，避免失败百分比沿用节流落盘前的旧值。
- 服务启动异常不再被忽略；任务记录为 `BACKGROUND_SERVICE`，任务页显示“后台下载已停止”、具体原因和重试入口，保留已有断点。
- 运行中的服务意外销毁或通知更新失败会取消下载；正常任务结束/全部暂停后的服务退出不记录为失败。空闲退出短暂等待并复查当前任务，使用 `stopSelfResult` 避免旧启动请求停止更新的服务。
- 采用 `START_NOT_STICKY`，避免系统无任务重启服务；系统停止应用进程后仍沿用启动时恢复为暂停的机制。
- 增加 `onTimeout(int, int)`：收到系统前台下载时限通知后停止服务并记录可重试原因。此回调来自 [Android 官方前台服务时限说明](https://developer.android.com/develop/background-work/services/fgs/timeout)，不能以 API 30 真机测试代替 Android 15 系统时限验收。

## 自动化检查

- 204 项 JVM 测试通过，0 失败、0 错误、0 跳过。
- 新增检查覆盖独立启动确认、取消后过期标识、启动拒绝、确认超时、进入前台失败通知所有等待者、启动失败零 HTTP 后重试、运行中中断保留断点并恢复。
- Debug 应用与 Android 测试包构建成功，Lint 无错误。最后断点计数修正的 P1DownloadIntegrationTest 12 项专项复验通过。

## 真机结果

设备：SHARK PRS-A0，arm64，API 30；使用本地 8 MiB 慢速 MP4 和隔离 UUID 任务。

完整 runner：`OK (3 tests)`，87.359 秒，无用例跳过：

1. Home 后后台前台服务状态成立，进度从 8192 增至 188416 字节；发送真实通知的“全部暂停” PendingIntent，保留 196608 字节断点并停止写入。HTTP Range 续传后完整字节一致，通知退出。
2. `ContextWrapper.startForegroundService` 注入 SecurityException，确认包装后的中文失败类型、零 HTTP 请求和既有断点保留。
3. 主动 `stopService` 导致 FAILED/BACKGROUND_SERVICE；真实任务页出现标题、原因及重试按钮。点击真实按钮后等待重试开始，续传完成且完整字节一致。

原始日志及界面证据：[设备结果](background-device-2026-10-09/RESULTS.md)。最终恢复首页、history 空列表、cache 无测试残留、前台下载服务退出；未发现本轮应用崩溃。

初轮有两项测试问题：按钮点击后立即读到旧 FAILED；厂商通知更新后不保留标准 FGS flag。分别改为等待重试真实进度增长，以及通过 dumpsys 的实际 `isForeground=true` 判断服务状态；保留首轮结果，修正后整组复验通过。未以失败测试结果直接修改生产逻辑。

断点计数最后修正后，第 3 项单独复验 `OK (1 test)`，44.584 秒：确认失败记录 downloadedBytes 与 147456 字节实际断点等值，UI 显示 1%，真实按钮续传完成。截图已更新，日志见 `background-device-2026-10-09/instrumentation-checkpoint.txt`。

前阶段手机启用了安全锁屏，未获得人工解锁配合时没有执行熄屏段，也没有修改锁屏配置。后续用户明确授权并配合正常解锁，追加专项 `secureScreenOffTransferCompletesAfterNormalManualUnlock`，权威 runner `OK (1 test)`，43.649 秒，见 [人工解锁日志](p1-remaining-device-2026-10-09/background-manual-unlock.txt)。

真实安全熄屏时 `interactive=false/secureLocked=true`，进度从 139264 增至 401408 字节。正常人工解锁返回后任务为 `RUNNING`，随后完整下载 8388608 字节，与服务器文件逐字节一致且生产视频校验通过；下载通知和前台服务正常退出。未采集解锁凭据或绕过锁屏。当前真机仍不覆盖 Android 15 系统时限回调或长时间待机限制。

## 边界

前台服务提高后台运行可靠性，不能保证强制停止、系统杀进程或厂商限制下仍持续传输；已存断点可在返回应用后恢复。本轮不修改用户后台/电池配置，不清空用户库。
