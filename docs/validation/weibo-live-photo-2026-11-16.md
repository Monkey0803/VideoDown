# 微博 Live Photo 完整语义验证（2026-11-16）

## 范围

- 指定帖子：`https://weibo.com/3288446905/Rrei7jysH`。
- 微博 `pic_infos[*].type = livephoto` 解析为一个成对媒体条目：静帧 JPG 与 `picture.video` 的原始 MOV。
- 两份文件共同参与下载完成判定、进度与空间估算、续传、失效地址刷新、重复任务判断、重命名、清理、分享和导出。
- 图片中心进入或翻到 Live Photo 时自动循环播放 MOV，并可手动停止/恢复；打包下载分别写入 `images/NNN.*` 与 `livephotos/NNN.mov`。
- 缺少 MOV 的 Live Photo 不退化为普通静态图；导出时 MOV 丢失也会整体失败并清理已创建的静帧副本。

## 自动验证

环境：Pixel_10_Pro AVD，API 37 / arm64（`emulator-5554`）；JDK 使用 Android Studio JBR。

| 命令 / 测试类 | 结果 | 覆盖 |
|---|---:|---|
| `./gradlew testDebugUnitTest` | 526/526 通过 | 指定帖脱敏载荷解析、缺 MOV 不降级、JPG+MOV 单任务完成/取消、ZIP 成对条目、成对重命名与扩展名路径安全 |
| `./gradlew assembleDebug compileDebugAndroidTestKotlin` | 通过 | 调试 APK 与全部设备测试源码 |
| `ImageViewerDeviceTest` | 7/7 通过 | 图片中心基础行为、沉浸翻页保持隐藏及 MOV 自动循环播放/停止 |
| `WeiboGifDeviceTest` | 4 通过、1 个联网用例按默认策略跳过 | Live Photo 结果条目与打包入口、微博动图 GIF 原图格式选择、11 图长图文结果页；未启用 `live_weibo_gif` |
| `P2FileExportDeviceTest` | 8/8 通过 | JPG+MOV 同目录导出；缺 MOV 时不留下静帧 |
| `ImageGalleryDeviceTest` | 3/3 通过 | 静帧进入 `Pictures/拾影`、动态片段进入 `Movies/拾影`；缺 MOV 时回滚静帧 |
| `git diff --check` | 通过 | 无空白错误 |

## 语义与平台边界

- 下载保存的是微博提供的两份原始字节，不转码、不抽帧，因此源文件中的 Live Photo 配对元数据不会被应用改写。
- 应用内用同一条 `DownloadRecord` 的 `livePhotoMotion` 明确保存关联，任何生命周期操作都按一对文件执行。
- Android `MediaStore` 没有跨厂商统一的 Live Photo 配对字段。保存到系统相册后，JPG 与 MOV 分别位于 Pictures 和 Movies 集合；能否被某个图库重新识别为“实况照片”取决于该图库是否理解源文件元数据。拾影自身仍保留两者的配对关系。
- 本轮没有启用会产生真实网络流量的 `live_weibo_gif`，也没有再次下载指定帖的线上文件；真实 URL 的变化、登录限制或 CDN 过期仍属于在线验收边界。
