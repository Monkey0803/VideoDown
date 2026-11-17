# 下载可靠性与一致性验证（2026-11-17）

## 结论

Phase H 的 H1、H2、H3、H5、H6 已完成实现和自动化验证。H4 已补齐真实平台 opt-in
入口与 Android 9 及以下的相册授权用例，但因本轮没有可用的抖音、微博、快手、X 作品链接，
且当前设备为 API 37，真实平台执行及 API 26/28 执行仍保持未完成。

## 实现范围

- 图片与 Live Photo 打包前逐项校验文件内容，最终 ZIP 校验条目名称、顺序、CRC 与内容类型；
  200 错误页、截断图片和错误 MOV 不再被记录为成功。
- 直链、图片打包和 Live Photo 续传持久化 ETag / Last-Modified，并用 `If-Range` 防止不同版本
  的远程内容被拼接；没有可靠校验器时从零开始。
- 图片校验新增 JPEG、PNG、GIF、WebP、BMP、HEIC/AVIF 的结构完整性检查。
- 429/503 支持 `Retry-After` 秒数和 RFC 1123 日期；任务卡显示运行时重试倒计时。
- yt-dlp 请求接入原生 `ratelimit`，取全局和单任务非零上限的较小值。

## 自动化证据

- `./gradlew testDebugUnitTest`：566 个 JVM 测试通过。
- `ImageDownloadDeviceTest`、`ImageArchiveDeviceTest`、`ImageGalleryDeviceTest`：API 37 通过。
- `DownloadRetryStatusDeviceTest`：API 37 通过，覆盖任务卡重试倒计时。
- `GenericVideoDeviceTest#engineAcceptsANativeRateLimitDuringARealSegmentDownload`：API 37 通过，
  使用本地 HLS fixture 走随包 yt-dlp 的真实下载路径。
- `LegacyImageGalleryDeviceTest`：已编译并在 API 37 正常按条件跳过；用例覆盖拒绝授权时保留
  应用内副本、授权后成功入库的 API 26–28 行为。
- `compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleRelease` 通过；未配置签名时 Release
  产物保持预期的未签名状态。

## 未覆盖边界

1. 未提供临时真实作品链接，因此未执行抖音、微博、快手、X 的联网下载回归；仓库内不保存
   易失效链接。
2. 没有 API 26/28 设备，旧版存储授权用例尚未实际运行。
3. yt-dlp 限速按进程生效，多个引擎进程不共享全局总桶；FFmpeg 自行拉流阶段不受该上限约束。
4. 本轮运行了相关设备测试类，未运行完整 `connectedDebugAndroidTest` 套件。
