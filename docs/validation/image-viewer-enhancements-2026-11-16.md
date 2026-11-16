# 图片浏览增强验证（2026-11-16）

## 范围

- 3 / 5 / 10 秒循环幻灯片，默认 5 秒并持久化间隔。
- 弹层、后台、缩放或手动翻页期间暂停计时，播放期间保持屏幕常亮。
- 按 `DownloadRecord.updatedAt` 的本地日期分组的三列快速定位网格。
- AndroidX ExifInterface 1.4.2 按需读取 EXIF/XMP 和 GPS；只做会话缓存，不上传、不落库、不写日志。
- 哔哩哔哩仅登记在 `docs/TODO.md` Phase E，本轮未修改解析或登录链路。

## 自动化结果

环境：Pixel 10 Pro AVD，Android 17 / API 37，arm64。

- `./gradlew testDebugUnitTest`：532/532 通过。
  - `ImageViewerEnhancementPolicyTest`：间隔清洗、弹层/后台/缩放暂停条件、保存日期分组。
  - `ImageMetadataTest`：空字段过滤、GPS 与海拔格式化。
  - 首轮发现 `MediaKindTest` 的序列化往返测试依赖两次当前时间落在同一毫秒；固定测试时间戳后消除竞态，未改生产语义。
- `ImageViewerDeviceTest`：9/9 通过。
  - 新增幻灯片自动翻页、暂停后日期网格定位。
  - 使用真实 JPEG 写入并读取 Make、Model、经纬度，验证读取器与信息面板入口。
  - 原有沉浸式切图、控制栏隐藏、删除前进、保险箱隔离与 Live Photo 循环保持通过。
- `./gradlew assembleDebug`：通过，产出 arm64 Debug APK。
- `./gradlew assembleRelease`：通过；未配置发布密钥时按仓库设计产出未签名 Release APK。
- 中英文字符串 key 由全量 JVM 的 `LocalizationGuardTest` 校验一致。

## 验证中发现并修复

- 自动翻页开始时 `HorizontalPager.isScrollInProgress` 会变为 `true`。若把它作为 `LaunchedEffect` 的 key，动画会立即取消自身。现改为计时结束后只检查是否存在用户手势，自动动画不再触发自我取消。
- 用户手动滑动时不会抢手势；即使滑回原页，也会等页面重新稳定后重新计满一个间隔。
- 删除到只剩一张图片会自动停止幻灯片，避免控制入口消失后仍持有常亮标记。

## 未覆盖边界

- 未在实体手机、平板或折叠屏验证三按钮工具行与日期网格布局。
- 后台切换、真实双指缩放暂停与 `FLAG_KEEP_SCREEN_ON` 的系统状态只由策略/JVM 和实现检查覆盖，未做设备端系统级断言。
- 设备夹具覆盖 JPEG 常见字段和 GPS；HEIC、厂商 MakerNote、超长 XMP、损坏但可部分读取的真实相机样本未逐一验证。
- 信息面板长列表的实际滚动手感与大量图片日期网格性能尚未人工真机复核。
