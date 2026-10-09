# 并发下载空间管理验证（2026-10-09）

## 实现

- 同一 DownloadEngine 中的运行任务共享线程安全空间预留账本，获得并发槽位后才申请预算。
- 预算只预留尚未写入的字节；实际已写文件由磁盘可用空间反映，避免重复计算。每个运行任务保留 32 MiB 工作余量，引擎任务按已知大小预留下载和合并两份空间。
- 直链根据响应大小更新预算，每次写入前检查；未知长度响应按即将写入的位置逐步申请空间。
- 引擎在进度回调及每 250 ms 检查任务目录，覆盖无下载进度的提取、合并阶段；发布文件及临时副本计入本任务实际占用。
- 正常完成、暂停、取消、异常退出均在 finally 释放预留；不足时保留可续传数据并记录 STORAGE_FULL。
- 正常完成和 HTTP 416 完整断点文件的复制发布分支均在复制前预留第二份空间。

## 验证

执行：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`。

- 199 项 JVM 测试通过，0 失败、0 错误、0 跳过。
- 覆盖并发抢占预算、已写字节扣减、实际大小扩大、外部空间下降、整数溢出、发布副本占用。
- 本地 HTTP 集成测试覆盖第二任务不足拒绝、暂停后恢复、完成后释放、响应大小扩大被拦截、未知长度写入上限、HTTP 416 复制预算不足时保留断点。
- Debug 应用和 Android 测试包构建成功；Lint 无错误。
- git diff --check 通过。

## 验证边界

实现阶段无连接设备；随后在 SHARK PRS-A0 真机补充以下验证，全部通过（3 项回归 + 1 项新增专项）：

- `runtimeAndDirectHlsDashDownloadsProduceVideoAndAudio`：本地 MP4、无扩展名视频、网页、HLS、多画质 HLS、DASH 均下载完成，并由 MediaExtractor 确认音视频轨道。
- `cancellingAtMergeStopsProcessGroupAndCanResume`：合并阶段取消，确认进程组退出，再恢复生成有效媒体。
- `queuedLocalExtractionCanPauseThenResumeOfflineWithoutLosingOriginal`：离线提取暂停并恢复，确认原文件长度不变和音频样本一致。
- `engineMonitorStopsForStorageFailureAndReleasedLeaseAllowsResume`：DASH 真实引擎运行时挂起进度回调，模拟可用容量降至零，由周期监控独立触发 STORAGE_FULL；确认运行任务退出、观察到的新增 runtime PID 消失、文件停止增长。恢复为只够单任务预留的容量后续传成功，验证预算已释放。

原三项 runner：`OK (3 tests)`，耗时 68.435 秒；监控专项：`OK (1 test)`，耗时 23.399 秒。测试包构建成功。证据见 [设备验证目录](storage-device-2026-10-09/)，新增测试位于 `ConcurrentStorageDeviceTest.kt`。

测试采用独立 UUID cache 目录和测试 history，未清空用户数据，未真实填满手机磁盘。用户数据备份保存在仓库外 `/tmp/videodown-storage-device-2026-10-09/user-data-backup.tar`。

预留账本协调本应用并发任务，不能阻止其他应用同时占用磁盘。未知大小的引擎输出采用周期监控，检查间隔内的突发写入仍可能超过工作余量；直链下载则在每次写入前检查。

LibraryScreen 的已有显示调整保留，未纳入本次空间管理改动。
