# 媒体尾部与轨道完整性验证（I2，2026-11-17）

## 结论

I2 已完成实现与自动化验证：在原有「容器可打开、首样本存在、无 DRM」之上，成品媒体还要满足
「任务要求的轨道齐全」与「声明时长接近结尾处仍有有效样本」；分轨合并结果缺音轨、服务器提前
断流、截断的 MP4 / WebM / 音频都会失败。失败时删除未发布成品、保留仍可复用的分片，
不做逐帧完整解码。

## 实现范围

- `domain/model/MediaIntegrityPolicy.kt`（新增，纯 Kotlin）：
  - `expectedTracks(media)`：音频任务只要音轨；明确请求画面 + 声音（`+` 选择器、单独音频
    URL/格式、`SEPARATE` 类型）要求两条都有；单一格式只要求视频轨，避免把合法的纯视频流
    误判成缺音轨。
  - `tailThresholdUs(durationUs)`：声明时长的 90%。
  - `evaluate(expected, present, durationUs, tailSampleUs)` → `Complete` /
    `MissingTracks` / `Truncated` / `UnknownDuration`（容器没有声明时长时明确报告未知，
    不假装校验过）。
- `data/generic/EngineMediaDownloader.kt`：
  - 新增 `validateMediaFile(file, expected, requireAudioOnly)`：轨道缺失 → `MISSING_TRACK`；
    首样本读不到 → `NO_VIDEO` / `NO_AUDIO`；尾部目标点之后读不到样本 →
    `TRUNCATED`；加密轨仍是 `DRM`。
  - `readTailSample`：`seekTo(90% 处, SEEK_TO_PREVIOUS_SYNC)` 后向后最多推进
    `MAX_TAIL_SAMPLES = 512` 个样本，关键帧间隔大时也不会退化成全文件扫描。
  - 引擎下载与本地音频提取在成品校验失败时删除 `media.*` 成品、
    保留 `.part` / `.ytdl` 分片（`discardUnpublishedProduct`），下一次重试可以安全续传。
- `data/download/DownloadValidation.kt`：直链下载按记录类型分派——图片 / 压缩包不变，
  音频按 `AUDIO`，其余视频按 `expectedTracks(record.media)`，因此未知 `Content-Length`
  或服务器提前断开后「看起来下完」的截断文件不再落成成功记录。
- `data/generic/VideoEngineRuntime.kt`：`MISSING_TRACK` 与 `TRUNCATED` 归为
  `DownloadFailureKind.NETWORK`（可重试），`NO_VIDEO` 归为不支持；新增文案
  `engine_missing_track`、`engine_truncated_media`（中英各一条）。
- `app/src/androidTest/assets/faststart-fixture.mp4`（新增素材）：把
  `landscape-fixture.mp4` 的 `moov` 前移并同步修正 `stco` 偏移（2421 字节），
  使截掉尾部后容器仍可打开、声明时长不变——只有近结尾的样本缺失，正好覆盖
  「未知长度 / 提前断流」的真实形态。素材说明已写进同目录 `README.md`。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**615 个用例通过**。
- 新增 `MediaIntegrityPolicyTest`（8 例）：音频任务只要音轨、单一格式只要视频轨、
  三种「明确合并」都要求两条轨；90% 阈值（含短文件）；完整通过；缺音轨/缺视频轨报告具体缺失项；
  无尾部样本或尾部早于阈值判截断；无声明时长报告未知而不是截断。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

新增 `MediaIntegrityDeviceTest` **5/5 通过**（真实 `MediaExtractor`）：

1. 完整素材全部通过：faststart MP4（无声，只要求视频轨）、AAC 视频（视频 + 音轨）、
   WebM（VP8 + Opus）、纯音频 m4a；——正向对照，确认没有把合法结果误杀。
2. 合并结果缺音轨：无声 MP4 按「视频 + 音频」校验报 `MISSING_TRACK`；纯音频按视频校验同样报
   `MISSING_TRACK`。
3. 截断到 60% 的 faststart MP4：报 `TRUNCATED`（容器可打开、时长声明仍在）。
4. 截断到 60% 的 WebM：校验失败。
5. 截断到 60% 的音频 m4a：校验失败。

受影响的既有设备类另行复跑并全部通过：`GenericVideoDeviceTest`（9 条，其中 2 条按既有前提跳过）、
`P2AudioDeviceTest`（6 条）、`SubtitleDownloadDeviceTest`（2 条）、
`PlaylistCollectionDeviceTest`（2 条）。

- 结果 XML 归档在
  [device-api37/MediaIntegrityDeviceTest.xml](media-integrity-tail-2026-11-17/device-api37/MediaIntegrityDeviceTest.xml)
  （5 条用例，0 失败）。

### 构建

- `./gradlew compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin`
  通过；`assembleDebug`、`assembleRelease` 通过（未配置签名时 Release 保持未签名）。

## 未覆盖边界

1. **容器没有声明时长的文件只保留首样本校验**：`UnknownDuration` 按通过处理，直播、未知时长
   流属于这一档；这是明确的取舍，不是遗漏。
2. **不检测「时长声明正确但中间有洞」**：只检查近结尾处仍有样本，不逐帧解码，也不校验
   `Content-Length` 与实际字节数（未知长度的场景依赖尾部检查）。
3. **分轨合并缺音轨的重试收益未量化**：`MISSING_TRACK` 归为可重试，会走 I1 的三轮有界重试；
   如果站点本身只给纯视频流，用户会在三次重试后看到失败。
4. **字幕轨 / 多音轨**：只要求至少一条音轨，不校验语言或轨道数量与请求一致。
5. **真实平台截断**：没有构造真实 CDN 提前断流的在线用例，截断素材是本地 fixture。
6. 平板 / 折叠屏、API 26/28 设备未验证；本轮只在 API 37 模拟器上运行。
