# 视频播放器进阶优化验收（2026-11-16）

## 范围

- 视频改用沉浸式 Compose 控制层；后台音频播放器未改。
- 新增横向拖动进度与按需关键帧预览、三种画面比例、真实音轨/字幕轨选择、原始帧截图。
- ExoPlayer 启用解码器回退；播放错误提供重试和外部播放器入口。

## 自动验证

- `./gradlew testDebugUnitTest`：522 项 JVM 测试通过，包含手势方向锁定、横向 seek、缩放模式循环与时间格式。
- `./gradlew assembleDebug`：通过，产物为 `app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk`。
- `./gradlew lintDebug`：本次播放器代码无新增错误；任务仍被既有的 `ArchivePublisher.kt:50` API 29 `NewApi` 错误阻断。
- API 37 Pixel 10 Pro 模拟器：
  - `VideoFrameToolsDeviceTest`：1/1，通过真实 MP4 抽帧并把 JPEG 写入 `Pictures/拾影`，校验 MIME、路径与可解码尺寸后清理。
  - `P3PlaybackDeviceTest`：9/9，通过续播、重建、倍速、循环、播放控制、播放完成后重新播放，以及画面比例面板的三种模式和轨道面板。
  - `PlayerGestureDeviceTest`：双击 ±10 秒、亮度、音量、横向 seek、长按 2× 通过。
  - `PlayerLandscapeDeviceTest`：4/4，播放器实例、暂停与位置保留、系统栏恢复、系统返回和离开释放通过。
  - `SubtitleSidecarDeviceTest`：2/2，无字幕时不误显开关，有 sidecar 时轨道与持久开关正常。
  - `P3PictureInPictureDeviceTest#homeEntersRealPipHidesSpeedDialogAndReturnsWithoutResettingVideo`：1/1，连续三轮 Home / 展开保持同一播放器、进度和播放控制。

## 已知边界

- 平板与折叠屏仍无实机覆盖；控制层按手机竖屏和横屏验证。
- 截图保存的是视频原始帧，不包含字幕和播放器按钮。
- 预览抽帧失败会只显示目标时间，不阻断实际 seek。
