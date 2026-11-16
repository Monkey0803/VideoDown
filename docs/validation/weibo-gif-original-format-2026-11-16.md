# 微博动图 GIF 原图格式验证（2026-11-16）

## 指定帖子与原因

- URL：`https://weibo.com/5571216925/RroaXt3As`
- 标题：`好好开车，好好工作`
- 微博公开 JSON 的同一 `pic_infos` 媒体对象中同时存在：
  - `type = gif`
  - `video = ...mp4?label=gif_mp4...`（微博转码）
  - `original.url = https://wx1.sinaimg.cn/orj1080/...gif`（GIF 原图）
  - 两者标称尺寸均为 554 × 400

旧实现为了避免同一媒体重复，看到 `video` 后只产出 MP4，完全跳过图片分支，因此结果页只有 400P 视频选项。这不是源站没有 GIF。

## 实现结果

- 同一动图仍是一个内容条目，不在“查看全部”中重复。
- 变体列表同时提供默认 MP4 与 `GIF` 原图；结果页标题改为“选择下载格式”。
- GIF URL 优先使用 `original`，其次才回退 `largest / large / bmiddle / thumbnail`。
- 选择 GIF 后按 `MediaKind.IMAGE` 下载，扩展名固定来自解析元数据 `gif`，完成后进入看图器并保留动画。
- 选择 GIF 时隐藏视频的音轨/字幕说明，主按钮改为“保存这张图片”；选择 MP4 时维持原播放器与视频下载行为。
- 若某条微博动图只提供 MP4、没有有效 GIF URL，则仍只显示 MP4，不做有损的客户端转 GIF。

## 验证

| 检查 | 结果 |
|---|---:|
| 指定帖子公开元数据读取 | 确认同一媒体含 MP4 与 GIF 原图 |
| `WeiboGifParserTest` | 11/11 通过，包含指定帖子脱敏结构与 MP4-only 回退 |
| 全量 `testDebugUnitTest` | 527/527 通过 |
| `assembleDebug` | 通过 |
| `compileDebugAndroidTestKotlin` | 通过 |
| `WeiboGifDeviceTest` | 4 个离线用例通过；1 个真实下载用例按 `live_weibo_gif` 默认策略跳过 |
| `git diff --check` | 通过 |

本轮设备用例验证了解析结果页从 400P 切到 GIF、按钮转为图片保存语义，以及提交给下载器的变体为 `MediaKind.IMAGE`、扩展名为 `gif`、URL 为原图。未下载指定帖的真实 GIF 文件，因此 CDN 实际字节下载仍属于在线验收边界。
