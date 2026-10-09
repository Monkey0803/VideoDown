# 应用更新入口验证（2026-10-09）

## 实现

- 「我的 → 存储与关于 → 检查更新」显示当前版本（`v<versionName>`），点击打开检查更新对话框；「关于拾影」同时显示版本号。
- `data/update/AppUpdateManifest.kt` 定义可容忍的 JSON 版本清单：`versionCode`、`versionName`、`downloadUrl`、`releasePageUrl`、`notes`（兼容别名 `url`、`changelog`），未知字段忽略。
- `data/update/AppUpdateChecker.kt` 用现有 OkHttp 客户端读取清单并比较版本：`versionCode` 优先，缺失时按点分数字 `versionName` 比较；返回 `UpdateAvailable` / `UpToDate` / `NotConfigured` / `Failed`，失败信息为中文提示。
- 应用只读取清单并打开外链，不下载、不申请安装权限、不静默安装。
- 更新源可配置：构建期 `-PupdateManifestUrl=…` 写入 `BuildConfig.DEFAULT_UPDATE_MANIFEST_URL`，也可在「检查更新 → 更新源」中按设备修改并保存到 `AppPreferences.updateManifestUrl`。
- 本次不包含解析引擎的独立热更新，引擎仍随 APK 固定更新。

## 验证

由于共享工作区同时存在其他未完成的改动（`StorageUsage.kt` 等），本次在 `git archive HEAD` 的干净导出中只应用本功能的改动进行验证，避免用无关改动掩盖结果。

执行：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。

- JVM 测试 220 项通过，0 失败、0 错误、0 跳过；其中 `AppUpdateCheckerTest` 9 项：
  - 更高 `versionCode` 返回 `UpdateAvailable`，并带出更新说明与下载/发布页链接；
  - 相同版本返回 `UpToDate`；
  - 仅 `versionName` 的清单支持更高（`1.2.0`）与更低（`0.9.9`）两种判断，`url` 别名生效；
  - HTTP 500 返回带状态码的失败提示；
  - 非法 JSON、缺少版本号、非 http(s) 地址与空地址分别返回明确失败或未配置；
  - `compareVersionNames` 覆盖 `1.0.1 < 1.0.2`、`1.0.1 < 1.10.0`、相等与更长段数。
- Debug 应用与 Android 测试包构建成功。
- 设备测试 `AppUpdateEntryDeviceTest` 在 `emulator-5554`（sdk_gphone16k_arm64）执行 `OK (3 tests)`，耗时 4.528 秒，覆盖入口可见、当前版本显示、发现新版本（含更新说明与「下载新版」）、对话框内配置更新源后立即复查，以及同版本「已是最新版本」。

## 验证边界

- 未接入真实发布托管地址：清单由测试内 OkHttp 拦截器提供，未对公网版本清单做端到端验证；发布方需自行提供可访问的 JSON 地址（`-PupdateManifestUrl` 或应用内设置）。
- 「下载新版 / 查看发布页」只验证按钮出现与链接解析，未真实打开浏览器下载和安装 APK。
- 版本比较只按点分数字段；预发布后缀（如 `-beta`）会被忽略，非数字段按 0 处理。
- 更新源为构建期常量与设备偏好，未做签名校验或清单完整性校验；应用不自动安装，最终安装由系统包安装器确认。
