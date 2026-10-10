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

- **仓库已公开、更新源已落地**：正式包构建期嵌入 `https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json`，v1.1.0 的 Release 已带 `version.json` 与两个 APK；详见下一节。
- **产物对应的提交是 `93f79f8`**（其后 `086b332`、`53b8aaf` 只改发版脚本）：原工作区 75 项未提交改动（含同一天其他人的弹窗统一、保险箱、下载预算等）已作为 1.1.0 提交入库，产物可用该提交复现。
- **历史已重写**（按你的选择）：清掉了本机路径、设备序列号、内网 IP 与测试用微博链接，全部提交换了 hash，远端是强推后的结果；镜像备份在仓库外 `../VideoDown.backup-2026-10-10.git`。**注意**：GitHub 侧旧的（未重写的）对象可能仍能按已知 SHA 直连访问一段时间，在意的话可联系 GitHub Support 清理。
- **lint 仍是 53 个 error**（`VaultAuthenticator` 权限声明、`LocalContextGetResourceValueCall` 48 处等），全部位于既有代码；`lintVitalRelease` 未拦下发布，但这批问题未修。
- **一次偶发测试失败**：第一次跑 `testDebugUnitTest assembleRelease` 时 `AutoResumeDownloadTest#network recovery continues the transfer without a tap` 超时失败（8s 上限），紧接着该用例 4 次连续通过（1 次普通 + 3 次 `--rerun-tasks`），随后整套 391 项强制重跑也全过；判断为构建高峰期的偶发超时，非本轮改动引入（该用例与本次改动的模块无关）。
- **真机冒烟已做**：在 SHARK PRS-A0（Android 11 / API 30）上 `adb install -r` 安装 arm64 release 包，安装成功、启动无崩溃（logcat 无本应用 FATAL/ANR），解析页正常渲染，设置页「关于拾影」显示 **拾影 v1.1.0**（`device-release-launch.png`、`device-release-about.png`）。冒烟后已卸载该包，设备回到只剩 debug 包的状态；正式分发前建议再跑一遍升级路径（同签名旧版 → 新版覆盖安装）与主流程。
- **未做**：没有在公开之前先让 GitHub 侧清空旧对象（重写是在私密状态下完成并强推的）；也没有跑「同签名旧版覆盖升级到 1.1.0」的升级路径用例（此前只是全新安装冒烟）。

## GitHub 公开与更新源（2026-10-10 晚）

- 仓库：https://github.com/Monkey0803/VideoDown ，可见性由 PRIVATE 改为 **PUBLIC**（默认分支 `main`；此刻 `main` 与 `1.0.2` 指向同一提交，`1.0.2` 已落后两个只改脚本的提交）。
- Release：https://github.com/Monkey0803/VideoDown/releases/tag/v1.1.0 ，附件为 `version.json`（832 B）、`videodown-arm64-v8a-release.apk`（78,831,927 B）、`videodown-universal-release.apk`（224,216,648 B）；tag 打在 `93f79f8`。
  **注意**：这里发布的 APK 是带 `-PupdateManifestUrl` 重新构建的，与上面「版本与产物」表里第一次（无更新源）构建的字节不同，因此 sha256 也不同；下面是最新发布件的 hash，与 GitHub 附件 digest 逐一核对一致。

  | 附件 | sha256 |
  | :--- | :--- |
  | `version.json` | `9026575e4d23bea289a73dfd3b6a4227ea20e3d05b34dc334e30efa399fecb79` |
  | `videodown-arm64-v8a-release.apk` | `8f9a2e13f541b3160efdb4d046cbd60c6a46c4851fdd28b3096a03965b717c8b` |
  | `videodown-universal-release.apk` | `d595702192243a28415d616282e448f2a1e4c36e580cca80e6adb27ac5dda605` |
- 应用内默认更新源：`https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json`。清单的 `downloadUrl` 指向 arm64 APK，`releasePageUrl` 指向 Releases；`releases/latest/download/…` 是稳定地址，换版本不用改应用。
- 发版流程脚本化：`scripts/publish-release.sh v1.1.0 --notes-file … --manifest-notes-file …`（跑测试 → 两个 ABI 分别构建并嵌入清单地址 → 生成 `version.json` → `gh release create`）。
- 端到端验证（全部对着线上真实地址，非替身清单）：
  - 公开可达性：未登录 `curl` 清单返回 200（经 302 跳到 release-assets），APK `HEAD` 为 302 → 302 → `application/vnd.android.package-archive`。
  - 真机（SHARK PRS-A0 / API 30）安装 release 包后打开「检查更新」：显示「当前版本：1.1.0（内部版本 5）／已是最新版本。／更新源：<GitHub 地址>」（`device-update-check-latest.png`）。
  - 把线上清单临时换成 `versionCode 6 / 9.9.9-test` 后，同一台真机显示「发现新版本：9.9.9-test」+ 说明 +「下载新版」（`device-update-available.png`）；随后恢复真实清单并复核线上回到 1.1.0。
  - 模拟器（Pixel_10_Pro / API 37）再次安装 release 包复核：「已是最新版本。」（`emulator-release-update-latest.png`）。

### 这一段新暴露的边界

- **`releases/latest/download/…` 有边缘缓存**：实测发新 Release 后约 30 秒内该地址仍可能解析到上一版；本轮实验里创建又删除临时 Release，缓存的重定向一度指向已删除的 tag、该地址短暂 404，约 30 秒后自行恢复。对「用户手动点检查更新」可接受，但别把它用于必须实时一致的场景。
- **清单的 `notes` 只有一个字段**：应用内弹窗固定显示中文说明（脚本取 `--manifest-notes-file` 的第一段），完整中英发行说明只在 Releases 页面。要让弹窗跟随应用语言，需要给清单加 `notesEn` 之类的字段并改 `AppUpdateManifest` 与测试。
- **本轮实验短暂污染过线上清单**（约一分钟的 `9.9.9-test`，`downloadUrl` 仍指向真实 APK），已恢复并复核；若在意这段记录，可删掉 Release 重新上传附件。
- **真机在收尾时处于锁屏**，`adb input` 被拒（`Injecting to another application requires INJECT_EVENTS permission`），所以最后一次真机确认停在「发现新版本 9.9.9-test」那一屏；最终的「已是最新」确认在模拟器上完成。真机上 release 包**仍处于安装状态**（未卸载），模拟器上也是 release 包。
