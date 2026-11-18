# 图片浏览页进出闪屏验证（2026-11-18）

## 问题与根因

- 设备：Pixel 10 Pro AVD，Android 17。
- 覆盖入口：任务页已完成图片、已存图片网格；覆盖左上返回与系统 Back。
- 修复前打开是约 30–34ms 的硬切；关闭时浅色系统栏、底栏和底层页面比大图退场提前约 118–133ms 出现。
- 图片浏览页缺少与来源封面匹配的共享元素目标；退出时又在导航动画结束前恢复系统栏。进一步逐帧确认：即使系统栏已隐藏，浅色 `Scaffold`/`NavHost` 根背景与底栏仍会从上下安全区提前穿出。

## 修复

- 图片浏览页与首页最近保存、任务卡、已存网格使用同一封面共享元素，打开和关闭均由原图连续承接。
- 所有关闭路径先请求隐藏系统栏，等待 `rootWindowInsets` 确认真正隐藏后再开始 `pop`，并防止重复关闭。
- 图片路由及其 250ms 退出过渡期间，`Scaffold` 与 `NavHost` 根背景保持黑色，底部导航/侧栏延后恢复。
- 共享图片退场完成后，再恢复主题背景和系统栏；关闭系统动画时立即恢复。

## 自动化验证

- `./gradlew testDebugUnitTest`：709/709 通过；包含 `ImageViewerTransitionPolicyTest` 3 条。
- `./gradlew assembleDebug assembleDebugAndroidTest`：通过。
- `ImageViewerDeviceTest`：9/9 通过。
- `./gradlew installDebug`：覆盖安装成功，未卸载、未清数据。

## 最终逐帧结果

- 已存打开：`0.509–0.840s` 网格，`0.957s` 共享图片放大，`1.006s` 浏览页完成；无黑帧或白闪。
- 已存左上返回：`3.206s` 仍为完整黑底浏览页，`3.341s` 共享图片收缩且目标网格同步接管，`3.373s` 完成。
- 已存系统 Back：`6.841s` 仍为完整黑底，`6.957s` 共享图片与目标页同步交接，`6.991s` 完成。
- 任务页打开/关闭：`1.316s` 来源页，`1.490s` 浏览页；关闭时 `3.391s` 仍为黑底，`3.521s` 共享图与任务页同步交接，`3.555s` 完成。
- 全部路径均无纯黑/全屏白闪、孤立白色系统栏、底栏提前穿出、重叠或裁切。

## 证据

- 修复前：`/tmp/image-viewer-flash.mp4`、`/tmp/image-viewer-flash-contact.png`、`/tmp/image-library-flash.mp4`、`/tmp/image-library-flash-contact.png`。
- 最终已存路径：`/tmp/image-final4-library-both.mp4`、`/tmp/image-final4-library-both-contact.png`。
- 最终任务路径：`/tmp/image-final4-task.mp4`、`/tmp/image-final4-task-contact.png`。
