# 图片浏览优化验证（2026-11-16）

## 范围

- 图片入口升级为全屏图片中心：同一普通库或保险箱内按更新时间倒序浏览全部有效图片。
- 支持横向翻页、缩略图定位、缩放状态下避免 Pager 抢手势，以及 3 秒后自动隐藏控制栏。
- 支持保存、分享、详情、打开来源和确认删除；删除后优先显示下一张，末尾回退，最后一张退出。
- 保险箱图片进入时调用系统认证，并与普通图片严格分区。

## 自动化验证

- JVM：`ImageViewerPolicyTest` 覆盖排序、普通/私密分区、入口文件缺失、入口记录删除后保持分区，以及删除后的前进/回退/退出策略。
- 设备：`ImageViewerDeviceTest` 覆盖真实 PNG 渲染、文件缺失、多图翻页与缩略图、删除前进、控制栏自动隐藏/轻点恢复、沉浸状态下翻页保持隐藏、Live Photo 自动循环播放，以及私密认证和公开图片隔离。

## 本轮结果

- `./gradlew testDebugUnitTest`：通过，**521** 条 JVM 用例全部成功；包含新增 `ImageViewerPolicyTest`。
- `./gradlew compileDebugAndroidTestKotlin assembleDebug`：通过；设备用例可编译，arm64 Debug APK 成功产出。
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.ImageViewerDeviceTest`：在 Pixel_10_Pro AVD（Android 17 / arm64）通过，**7/7** 条成功。
- `anImageRecordRendersInTheZoomableViewer` 会把真实渲染截图写入测试应用私有目录 `files/image-evidence/image-viewer.png`，避免把占位图当作渲染证据。

## 未覆盖边界

- 尚未在实体设备验证超大图子采样、横竖屏切换、系统分享目标、Android 8/9 存储权限和生物识别取消路径。
- 删除只处理应用下载记录及其私有文件；不会追删用户已另存到系统相册的副本，也不提供“最近删除”。
