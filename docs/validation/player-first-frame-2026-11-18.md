# 播放页首帧闪黑验证（2026-11-18）

## 问题与根因

- 设备：Pixel 10 Pro AVD，Android 17。
- 路径：任务页已完成视频 → 播放 → 本地播放页。
- 修复前 60 fps 逐帧结果：`0.383–0.433s` 显示封面，`0.450–0.567s` 视频区域纯黑，`0.583s` 出现首帧；闪黑持续约 117–133 ms。
- 根因：Media3 `PlayerView` 默认使用独立的 `SurfaceView`。它附着后以黑色表面盖过仍在 Compose 中的封面，直到视频首帧到达，因此仅检查封面语义节点无法发现真实像素黑帧。

## 修复范围

- 本地视频、站点在线播放、Live Photo 动态视频统一复用 `AppPlayerView`。
- 公共 `PlayerView` 使用 `TextureView` 并保留 reset 前内容，使视频与 Compose 封面处于同一合成层。
- 本地视频和在线播放封面、Live Photo 静态图均保留到 `onRenderedFirstFrame()`；音频、全屏和画中画沿用原有显示策略。
- 任务页已完成视频的封面加入共享元素过渡，播放器首屏异步加载 record/大图期间继续由任务卡现有封面承接。
- DRM/安全视频输出不在本应用现有能力范围，本次没有引入需要 `SurfaceView` 安全输出的路径。

## 自动化结果

- `./gradlew testDebugUnitTest`：通过，706/706。首次整套运行中既有随机队列性质测试失败 1 次，单类复跑通过，随后完整套件复跑通过。
- `./gradlew assembleDebug assembleDebugAndroidTest`：通过。
- `PlayerPosterPolicyTest`：3/3，通过。
- `PlayerCoverPosterDeviceTest`：3/3，通过；覆盖 `TextureView` 类型与首帧前封面像素不被黑色 surface 遮挡。
- `SiteHubDeviceTest#localHlsPlaysWritesProgressAndDownloadsTheExactVariant`：通过；确认站点播放器使用 `TextureView`。
- `ImageViewerDeviceTest#livePhotoAutomaticallyPlaysItsPairedMotionClipInALoop`：通过；确认 Live Photo 播放器使用 `TextureView`。
- `P3PlaybackDeviceTest#advancedControlsCycleScaleAndOpenTrackPanel`：通过；确认公共组件按最新状态更新画面比例。
- `SubtitleSidecarDeviceTest`：通过。
- `./gradlew installDebug`：覆盖安装成功，未卸载、未清数据。

## 已知回归环境问题

- 扩展回归中，`P3PlaybackDeviceTest#completedPlaybackOffersReplayAndStartsFromBeginning` 在重播后等待播放状态超时；该路径发生在视频播放结束后，与进入页面到首帧的修复路径不同。
- `PlayerLandscapeDeviceTest` 的 3 个旋转用例等待模拟器进入横屏超时，未观察到源码断言失败；随后画中画/手势扩展套件受同一设备窗口状态影响未作为本次验收证据。
- 上述失败均未改写为通过，后续应在重置设备窗口/方向状态的独立会话中复核。

## 人工逐帧验收

- 修复前证据：`/tmp/videodown-playerflash.mp4`、`/tmp/videodown-playerframes/fine-contact.png`。
- 第一版仅改 `TextureView` 后仍发现页面初始数据窗口：点击约在 `0.500s`，播放页 `0.700–0.917s` 视频区纯黑，`0.933s` 才显示封面/画面。证据：`/tmp/videodown-final-flash-2.mp4`、`/tmp/videodown-final-contact.png`。
- 加入任务页共享封面后的最终结果：任务页保持到 `0.617s`；`0.633s` 播放页首次出现时已显示共享封面与加载圈；封面保持到 `1.283s`；`1.300s` 切到首个解码帧。全程由任务页或封面承接，没有视频区纯黑、整屏黑帧或白闪。
- 最终证据：`/tmp/videodown-final-flash-3.mp4`、`/tmp/videodown-final-contact-3-transition.png`、`/tmp/videodown-final-contact-3-first-frame.png`。
