# 拾影 1.1.1 发版记录（2026-10-10）

## 版本与产物

| 项 | 值 |
| :--- | :--- |
| 版本 | `versionName = "1.1.1"`，`versionCode = 6`（上一版 1.1.0 / 5） |
| Release | https://github.com/Monkey0803/VideoDown/releases/tag/v1.1.1 |
| 应用内更新源 | `https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json` |

| 附件 | 大小 | SHA-256（与 GitHub 附件 digest 逐一核对一致） |
| :--- | ---: | :--- |
| `videodown-arm64-v8a-release.apk` | 78,834,383 B | `c86ea17ed02c983f33fdc8c0d81f76d7ee36a075295f11b40f3e5f0afd32a3a1` |
| `videodown-universal-release.apk` | 224,219,096 B | `edc1a4637f20c2b9258f3a80628a83cfd1deed27564302351c00dc1ba8279864` |
| `version.json` | 1,040 B | `a2db6d8098aaf6be372ab13efcabefce34bf9d3132ceaa80a2bd39b9dbd44b63` |

本地副本在 `.tooling/release-v1.1.1/`（git-ignored）；构建期嵌入清单地址，两个 ABI 分开构建。
签名沿用发布证书（`CN=VideoDown Release`，APK Signature Scheme v2），`aapt2 badging` 复核 `versionCode='6' versionName='1.1.1'`。

## 本版包含的改动

- 保险箱 `USE_BIOMETRIC` 修复与 Android 9 崩溃修复 → [lint 收敛记录](lint-cleanup-2026-10-10.md)
- 更新说明中英双语（`notesEn`）→ [更新说明按语言选择](update-notes-language-2026-10-10.md)
- 语言切换后 47 处提示/文件名不刷新 → 同上（lint 收敛记录的重构一节）
- `lintDebug` 错误 53 → 0 → 同上

## 验证

- 构建前的门：`scripts/publish-release.sh` 先跑 `testDebugUnitTest --rerun-tasks` → **394 项通过、0 失败**。
- 线上清单：`versionCode 6 / versionName 1.1.1`，`notes`（中文）与 `notesEn`（英文）都在；发布后约 15 秒即可从 `releases/latest/download/version.json` 读到。
- **真实升级路径**（模拟器 Pixel_10_Pro / API 37，装的是上一版正式包）：
  - ① 已装的 **1.1.0（code 5）** 点「检查更新」→「发现新版本：1.1.1」+ 中文说明 +「下载新版」（`device-1.1.0-sees-1.1.1.png`）。1.1.0 客户端不认识 `notesEn`，显示中文 `notes` —— 正是预期的优雅降级。
  - ② `adb install -r` **同签名覆盖安装 1.1.1** → 成功；再查显示「当前版本：1.1.1（内部版本 6）／已是最新版本。」（`device-1.1.1-latest.png`）。
  - ③ **1.1.1 客户端的中英双语说明**（临时把线上清单换成 `versionCode 7 / 9.9.9-test` 后逐语言验证，随后恢复）：英文界面显示 `English verification note…`（`device-1.1.1-notes-en.png`），中文界面显示 `中文验证说明…`（`device-1.1.1-notes-zh.png`）。
- 设备回归：受影响套件 53 项 0 真实失败（清单见 [lint 收敛记录](lint-cleanup-2026-10-10.md)）。

## 发版过程与边界

- **撞到一次已知的 AGP 打包 flake**：第一次跑发版脚本时 `:app:packageRelease` 在
  `PackageAndroidArtifact$IncrementalSplitterRunnable` 里失败（README 与 AGENTS.md 记录过同族问题）。
  删掉 `app/build/intermediates/apk` 与 `packaged_manifests` 后重跑即成功，**脚本本身没有改**。
- **实验期间线上清单短暂被替换**（`9.9.9-test`，约 1 分钟，`downloadUrl` 始终指向真实 APK），恢复后已 `curl` 复核回到 1.1.1。
- 模拟器上现在装的是 **release 1.1.1**（调试包也在）；真机 SHARK PRS-A0 也已升级到 1.1.1（见下节）。
- 远端 `1.0.2` 分支仍停在 1.1.0 之前的提交，尚未清理（等你决定）。
- 老版本要看到 1.1.1 的前提是它自己的 `versionCode < 6`；本次实测 1.1.0（5）满足。

## 真机（SHARK PRS-A0 / Android 11）复核（2026-10-10 晚，解锁后）

- **升级路径**：真机上原本装的是 release **1.1.0（code 5）**。打开「检查更新」→「当前版本：1.1.0（内部版本 5）／**发现新版本：1.1.1**」+ 中文更新说明 +「下载新版」（`device-shark-1.1.0-sees-1.1.1.png`）。
- **同签名覆盖安装**：`adb install -r .tooling/release-v1.1.1/videodown-arm64-v8a-release.apk` → `Success`；`dumpsys package` 复核 `versionCode=6  versionName=1.1.1`。再查显示「当前版本：1.1.1（内部版本 6）／**已是最新版本。**」（`device-shark-1.1.1-latest.png`）。
- **保险箱门禁（本版修复的正主）**：真机「已存 → 保险箱锁」现在会弹出系统验证窗——
  `mCurrentFocus=Window{… BiometricPrompt}`，界面为「解锁保险箱 / 验证身份后才能查看其中的内容」+ 设备凭据输入（`device-shark-vault-prompt.png`）。
  这正是修复前在这台机器上**什么也不弹**的那条路径；操作只用点击与返回键，没有输入任何凭据，随后按返回关闭了弹窗。
