# 运行中单任务限速修复验收（2026-11-18）

## 问题与修复

- 引擎任务的 `EngineRateControl.taskLimit` 原先捕获任务启动时的 `DownloadRecord`；运行中从任务菜单
  修改限速后，历史记录虽然短暂写入新值，下载进程仍持续读取旧值。
- 下载进度也会用启动时的局部记录回写历史，存在把刚设置的 `speedLimitKbps` 覆盖回旧值的竞态；
  直链与引擎任务都会经过这条回写路径。
- 修复后，运行中任务以 `taskSpeedLimits` 保存当前值。直链令牌桶、引擎动态预算与所有历史回写都
  合入同一份值；任务结束后释放，暂停任务仍以持久化记录为准。

## JVM 验证

命令：

```bash
./gradlew testDebugUnitTest
```

结果：`BUILD SUCCESSFUL`，703 条 JVM 用例通过。新增
`RunningTaskSpeedLimitTest` 验证以下边界：

1. 引擎已启动且初始为「跟随全局」时，切换为 512 KB/s 后动态 provider 立即返回 524288 B/s；
2. 随后的引擎进度回写不会把限速覆盖回 0；
3. 任务完成后的历史记录仍保留 512 KB/s。

## 设备验证

设备：Pixel 10 Pro AVD，Android 17（`emulator-5554`）。只覆盖安装测试 APK，未卸载应用、未清数据。

命令：

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.EngineBandwidthDeviceTest#changingTaskLimitWhileEngineIsRunningPacesTheRemainingBytes
```

结果：1/1 通过，耗时 11.356 秒。用约 2 MiB 的本地素材启动随包 yt-dlp，首个下载进度到达后才把
单任务上限从 0 改为 256 KB/s；测试确认剩余数据量足够，并且剩余下载耗时达到按新上限计算理论耗时
的至少 50%，最终产物存在且通过引擎校验。

结果文件：
`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10_Pro(AVD) - 17.xml`。
