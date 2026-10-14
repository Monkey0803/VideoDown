# 拾影 1.2.0 发版记录（2026-10-13）

## 版本与产物

| 项 | 值 |
| :--- | :--- |
| 版本 | `versionName = "1.2.0"`，`versionCode = 7`（上一版 1.1.1 / 6） |
| tag | `v1.2.0` → `9a8c0d6`，与 `origin/main` 的 HEAD 一致（发版落在 main 上） |
| Release | https://github.com/Monkey0803/VideoDown/releases/tag/v1.2.0（`draft=false`，标题「拾影 1.2.0（Keepsake）」，`target=main`） |
| 应用内更新源 | `https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json` |
| 发布日期 | 2026-10-14 10:55:28Z |

| 附件 | 大小 | SHA-256（本地文件与 GitHub 附件 digest 逐一核对一致） |
| :--- | ---: | :--- |
| `videodown-arm64-v8a-release.apk` | 79,058,372 B | `a85fdded1e0db63b010eb8bf43cbb4ce630ae3d674324d1fe5ac00b66b371c5a` |
| `videodown-universal-release.apk` | 224,441,961 B | `fb04b3c806e054b2642072041c935a50b4615b50fc0dbfb6fd116ca2855e3b77` |
| `version.json` | 1,026 B | `80b21c7bef49a2b0b31f8522989f92d451503b79f9d97d6331a9a740e372067f` |

- 签名：两个 ABI 都是 `CN=VideoDown Release, O=VideoDown, C=CN`，证书 SHA-256
  `925e4c248ba7f70e9eb5e3b8587ee7bde384476799cbd097dd65746a6d29c50d`——与 1.1.1 同一个发布证书，
  所以能就地覆盖升级。
- `aapt2 badging` 复核两个包都是 `versionCode='7' versionName='1.2.0'`、`minSdkVersion:'26'`。
- 从发布包里抽出 `classes*.dex` 搜字符串，确认嵌入的清单地址就是
  `releases/latest/download/version.json`（构建期由 `-PupdateManifestUrl` 注入）。
- 本地副本在 `.tooling/release-v1.2.0/`（git-ignored）。

## 本版包含的改动

完整说明见 [release-notes.md](release-1.2.0-2026-10-13/release-notes.md)、清单说明见
[manifest-notes.txt](release-1.2.0-2026-10-13/manifest-notes.txt) 与
[manifest-notes-en.txt](release-1.2.0-2026-10-13/manifest-notes-en.txt)。一句话：

- 统一动效 token、卡片式弹窗骨架、共享元素（已存封面 → 播放器）、骨架屏扫光、已存空状态插图、Coil 3 迁移。
- 已存列表拖拽排序并持久化顺序；设置备份带走自定义顺序。
- 修复弹窗页脚被键盘挡住（横屏 + 输入法）；修复全部暂停后「从通知继续」失去入口（暂停卡保留、点继续拉起服务）；
  修复网页解析失败后状态栏一片空白，并把视频号链接的失败原因说准。

三个启动器快捷方式**按既定决定继续不发布**（`LauncherShortcuts.publish` 清空动态快捷方式），
本版说明里没有提及。

## 发版前的门

| 门 | 结果 |
| :--- | :--- |
| `./gradlew testDebugUnitTest --rerun-tasks` | **444 项通过、0 失败** |
| `./gradlew lintDebug` | **0 error** / 140 warning / 6 hint |
| `scripts/publish-release.sh … --dry-run` | 仓库、分支、版本、清单地址、产物目录逐项打印无误后才真发 |
| 工作区 | 干净且当前在 `main`（脚本前置检查） |

## 线上核对（发布后）

- `curl -sL https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json` → **HTTP 200、1026 字节**，
  与本地 `version.json` 逐字节一致（`json.tool` 对比无差异）：`versionCode 7 / versionName 1.2.0`，
  中文 `notes` 与英文 `notesEn` 都在。
- `gh release view v1.2.0 --json assets` 里三个附件的 `digest` 与本地 `shasum -a 256` 完全一致（见上表）。

## 设备验证（模拟器 API 37，arm64）

### 升级路径

用 1.1.1 的**线上那一版**（本机副本摘要 `edc1a4637f20c2b9…`，与 1.1.1 发版记录一致）就地覆盖：

| | 版本 | firstInstallTime | lastUpdateTime |
| :--- | :--- | :--- | :--- |
| 升级前 | `versionCode=6` / `1.1.1` | 2026-10-10 18:38:26 | 2026-10-14 19:02:14 |
| 升级后 | `versionCode=7` / `1.2.0` | **2026-10-10 18:38:26（没变）** | 2026-10-14 19:02:48 |

`firstInstallTime` 不变 + `versionCode` 递增 = 就地升级而不是重装；装完直接启动无崩溃。

### 老版本确实能看到这次升级（走真实线上清单）

在装有 **1.1.1** 的发布包上点「我的 → 检查更新」，弹窗读的是线上清单：

- 英文界面：`Installed: 1.1.1 (build 6)` / `New version available: 1.2.0` + 英文说明 →
  [device-1.1.1-sees-1.2.0-en.png](release-1.2.0-2026-10-13/emulator-api37/device-1.1.1-sees-1.2.0-en.png)
- 切换语言到简体中文后同一条路径：`当前版本：1.1.1（内部版本 6）` / **`发现新版本：1.2.0`** + 中文说明 →
  [device-1.1.1-sees-1.2.0-zh.png](release-1.2.0-2026-10-13/emulator-api37/device-1.1.1-sees-1.2.0-zh.png)

两份弹窗都显示更新源就是 `releases/latest/download/version.json`，说明中英各一版都按界面语言正确取用。
1.2.0 装好后「检查更新」行显示 `v1.2.0`、解析引擎显示
`yt-dlp 2026.08.19 · component 0.18.1 · QuickJS / FFmpeg` →
[device-1.2.0-profile-bottom.png](release-1.2.0-2026-10-13/emulator-api37/device-1.2.0-profile-bottom.png)。

### release 包 UI（`P2ReleaseUiBridgeDeviceTest`）

装上 1.2.0 release 包后重跑该类：**3 条里 1 条通过**（`actualReleaseUiClickAndCapture`，
此前 3 条全红是因为模拟器上根本没有 release 包，见 `docs/TODO.md` 的既有条目）。剩下两条不是回归：

- `preciselyEnterPublicSourceInVisibleReleaseUi` 需要 `-e public_source <url>` 这个 instrumentation 参数
  （缺参数时第 13 行 `getString(...)!!` 直接 NPE）；
- `independentlyReadActuallyExportedReleaseMedia` 需要先有一次真实导出的
  `cache/p2-release-export.mp4`（是链式用例，单独跑没有产物）。

两条都属"需要额外参数/前置产物"，本轮没有为它们补驱动。

## 未覆盖 / 边界（如实记录）

- **真机没有参与本轮发版验证**：SHARK PRS-A0 有锁屏且本会话无法人工解锁，所以升级路径与 UI 都在模拟器上做；
  1.1.1 那轮真机复核的结论（升级、保险箱、PiP 人工解锁）不适用于本版新增的动效/拖拽/通知改动。
- **数据在升级后是否逐项完好没有断言**：`adb root` 在 production 镜像上不可用，读不到 `/data/data`；
  能证明的是"就地覆盖（`firstInstallTime` 未变）+ 启动不崩 + 设置页读到了旧偏好与引擎版本"。
- **`P2ReleaseUiBridgeDeviceTest` 的两条链式用例**如上未跑通。
- **动效手感、键盘弹起时页脚观感**仍待人工复核（`docs/TODO.md` 里两条 `[ ]`），本版照发是因为它们是观感取舍、
  不是功能缺失。
- 平板 / 折叠屏未在发版前验证（既有边界）。

## 复现

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --rerun-tasks && ./gradlew lintDebug
scripts/publish-release.sh v1.2.0 \
  --notes-file docs/validation/release-1.2.0-2026-10-13/release-notes.md \
  --manifest-notes-file docs/validation/release-1.2.0-2026-10-13/manifest-notes.txt \
  --manifest-notes-en-file docs/validation/release-1.2.0-2026-10-13/manifest-notes-en.txt
```
