测试素材均自行生成，不使用用户视频或网络内容。

- `landscape-fixture.mp4`：320×180、60 秒 H.264 无声双色动画，用于真实解码、宽高比、横竖屏切换和无音轨失败测试。
- `faststart-fixture.mp4`：`landscape-fixture.mp4` 的 faststart 版本（`moov` 移到 `mdat` 之前、`stco` 偏移同步调整），
  用于 I2 的尾部完整性测试：截掉文件末尾后容器仍可打开且声明时长不变，只有接近结尾的有效样本会缺失。
- `aac-video-fixture.mp4`：320×180、3 秒 MPEG-4 视频和 440 Hz AAC 音轨，用于离线提取、原文件保留、AAC 编码采样一致性及队列暂停恢复。
- `audio-fixture.m4a`：同一 AAC 音轨，用于应用内播放、系统分享、MediaStore 音乐导出和 SAF 文件复制。
- `background-audio-fixture.m4a`：30 秒 440 Hz AAC 音轨，用于后台持续播放、锁屏和通知会话测试。
- `opus-video-fixture.webm`：320×180、3 秒 VP8 视频和 Opus 音轨，用于提取后转 AAC。

素材使用本机 FFmpeg 生成；应用测试执行 APK 内置的 FFmpeg，二者分别提供素材和受测运行时。
