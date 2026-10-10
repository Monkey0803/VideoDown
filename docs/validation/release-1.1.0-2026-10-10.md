# 拾影 1.1.0 发版记录（2026-10-10）

## 版本与产物

| 项 | 值 |
| :--- | :--- |
| 版本 | `versionName = "1.1.0"`，`versionCode = 5`（上一版 1.0.3 / 4） |
| applicationId | `com.videodown.douyin`（debug 仍为 `…debug`） |
| minSdk / targetSdk | 26 / 36 |
| 桌面名 | 中文 `拾影`，英文 `Keepsake` |

产物（本地副本在 `.tooling/release-1.1.0/`，该目录与 `*.apk` 都在 `.gitignore` 里）：

| APK | 大小 | ABI | SHA-256 |
| :--- | ---: | :--- | :--- |
| `videodown-arm64-v8a-release.apk` | 78,833,795 B（75.2 MiB） | arm64-v8a | `77864ce47b912bbd363d8a3bca53ffe30d385ac64dcfb30f183d29cfd9ab1534` |
| `videodown-universal-release.apk` | 224,216,520 B（213.8 MiB） | arm64-v8a / armeabi-v7a / x86 / x86_64 | `d760cd9092b8784d01848ca99894e468e194a8b0c7b403a3e428b77448c666bb` |

体积主要来自随包固定发布的解析引擎资产（yt-dlp / FFmpeg / QuickJS），与上一版同量级。

## 构建与签名

```bash
./gradlew testDebugUnitTest assembleRelease            # arm64
./gradlew assembleRelease -Pabi=universal              # universal
```

- 签名：`keystore.properties` 存在，两个包都用发布证书签好；`apksigner verify` 通过（APK Signature Scheme **v2**，v1/v3 未启用；minSdk 26 下 v2 足够）。
  证书 `CN=VideoDown Release, O=VideoDown, C=CN`，SHA-256 `925e4c248ba7f70e9eb5e3b8587ee7bde384476799cbd097dd65746a6d29c50d`。
- `aapt2 dump badging` 复核：`versionCode='5' versionName='1.1.0'`、`application-label:'拾影'`、`application-label-en:'Keepsake'`、ABI 列表符合预期（原始输出见 `release-1.1.0-2026-10-10/apk-metadata.txt`）。
- 顺序注意：arm64 与 universal 不能在同一份输出目录里共存，构建后一份会覆盖目录；本次每构建一种就立刻复制到 `.tooling/release-1.1.0/`。

## 本轮验证

- **JVM：391 项通过，0 失败、0 错误、0 跳过**（`./gradlew testDebugUnitTest --rerun-tasks` 强制重跑，非缓存结果）。
- 设备用例（本轮改动相关）：`LanguageSwitchDeviceTest` 在 API 37 模拟器与 API 30 真机各 1/1 通过；`AppUpdateEntryDeviceTest` 真机 4/4；`TaskSearchDeviceTest` 1/1、`LibraryActionsDeviceTest` 4/4。
- 今日其余改动的设备证据见各自记录：[弹窗统一](app-dialog-unification-2026-10-10.md)、[存储弹窗改版](storage-dialog-redesign-2026-10-10.md)、[语言切换](language-switch-2026-10-10.md)、[英文名](english-name-keepsake-2026-10-10.md)、[检查更新动作行](update-dialog-actions-2026-10-10.md)、[搜索框圆角](task-search-corner-2026-10-10.md)。
- 发行说明与更新清单模板：`release-1.1.0-2026-10-10/release-notes.md`（中英）、`release-1.1.0-2026-10-10/version.json`（`versionCode` 5 / `versionName` 1.1.0，下载与发布页地址待填）。

## 发版边界与提醒

- **包内没有更新源地址**：本次没有传 `-PupdateManifestUrl=…`，`BuildConfig.DEFAULT_UPDATE_MANIFEST_URL` 仍为空，所以装机后「检查更新」默认提示「尚未配置更新源地址」。要让用户开箱可检查更新，需要用托管地址重新构建（`./gradlew assembleRelease -Pabi=… -PupdateManifestUrl=https://…/version.json`），或在应用内手动填。托管地址未提供，故未代填。
- **更新清单需另发**：把 `version.json` 的 `downloadUrl` / `releasePageUrl` 换成真实地址后部署；`versionCode` 必须为 5，老版本（4）才会看到新版本提示。
- **本次是从未提交的工作区出的包**（按你的选择）：工作区含 75 项未提交改动，除本轮记录过的各项外还有同一天其他人未提交的弹窗统一、保险箱、下载预算等改动。这些改动各自的验收记录都在 `docs/validation/`，但**没有对应的提交**——出包产物无法用 commit 复现，建议发版后尽快把这批改动提交并打 tag。
- **lint 仍是 53 个 error**（`VaultAuthenticator` 权限声明、`LocalContextGetResourceValueCall` 48 处等），全部位于既有代码；`lintVitalRelease` 未拦下发布，但这批问题未修。
- **一次偶发测试失败**：第一次跑 `testDebugUnitTest assembleRelease` 时 `AutoResumeDownloadTest#network recovery continues the transfer without a tap` 超时失败（8s 上限），紧接着该用例 4 次连续通过（1 次普通 + 3 次 `--rerun-tasks`），随后整套 391 项强制重跑也全过；判断为构建高峰期的偶发超时，非本轮改动引入（该用例与本次改动的模块无关）。
- **真机冒烟已做**：在 SHARK PRS-A0（Android 11 / API 30）上 `adb install -r` 安装 arm64 release 包，安装成功、启动无崩溃（logcat 无本应用 FATAL/ANR），解析页正常渲染，设置页「关于拾影」显示 **拾影 v1.1.0**（`device-release-launch.png`、`device-release-about.png`）。冒烟后已卸载该包，设备回到只剩 debug 包的状态；正式分发前建议再跑一遍升级路径（同签名旧版 → 新版覆盖安装）与主流程。
- **未做**：没有提交、没有打 tag、没有上传到任何托管。
