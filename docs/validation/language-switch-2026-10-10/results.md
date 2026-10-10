# 应用内语言切换验证证据（2026-10-10）

命令：`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL。
JVM 391 项通过，0 失败、0 错误、0 跳过（含新增 `AppLanguageTest` 4 项与 `LocalizationGuardTest` 的 key 对照；汇总自 `app/build/test-results/testDebugUnitTest/*.xml`）。

命令：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.LanguageSwitchDeviceTest`

- Pixel_10_Pro(AVD)（Android 17 / API 37，走应用内切换分支）：`com.videodown.douyin.LanguageSwitchDeviceTest` tests=1 failures=0 errors=0 skipped=0 time=2.404s（原始报告 instrumentation-emulator.xml）
- SHARK PRS-A0（Android 11 / API 30，走只解释分支）：`com.videodown.douyin.LanguageSwitchDeviceTest` tests=1 failures=0 errors=0 skipped=0 time=2.769s（原始报告 instrumentation-shark.xml）

## 设备上的语言状态（实测）
```
$ adb -s emulator-5554 shell cmd locale get-app-locales com.videodown.douyin.debug   # 切换前
Locales for com.videodown.douyin.debug for user 0 are []
…在应用内选 English 并确定…
Locales for com.videodown.douyin.debug for user 0 are [en]
```

## 截图
- `emulator-language-dialog.png`：外观区的「语言」行与三项单选弹窗（中文界面）。
- `emulator-after-switch-en.png`：应用内切到 English 后的设置页整屏英文，语言行值 English。
- `shark-api30-language-unsupported.png`：Android 11 真机上只解释、只给系统设置入口。

## Lint
`./gradlew lintDebug` 仍失败于既有代码的 53 个 error（`VaultAuthenticator` 权限、`LocalContextGetResourceValueCall` 48 处等）；本轮新增的 `core/AppLanguage.kt`、`presentation/Labels.kt`、`LanguageSwitchDeviceTest.kt`、`AppLanguageTest.kt` 在 lint 报告中 0 条。
