# 更新对话框动作行验证证据（2026-10-10）

命令：`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL；
JVM 测试 387 项通过，0 失败、0 错误、0 跳过（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）。

命令：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.AppUpdateEntryDeviceTest`

- SHARK PRS-A0（Android 11，1080×2400 @440dpi，font_scale 0.86）：`com.videodown.douyin.AppUpdateEntryDeviceTest` tests=4 failures=0 errors=0 skipped=0 time=5.607s（原始报告 instrumentation-shark.xml）
- Pixel_10_Pro(AVD)（Android 17，1280×2856 @480dpi，font_scale 1.0）：`com.videodown.douyin.AppUpdateEntryDeviceTest` tests=4 failures=0 errors=0 skipped=0 time=6.703s（原始报告 instrumentation-emulator.xml）

## 真机界面坐标（中文，SHARK PRS-A0，截图 actions-one-row-zh.png）

`uiautomator dump` 里三个按钮的 bounds（同一 top=1395，left 递增）：

```
更新源   [337,1395][438,1443]
重新检查 [526,1395][660,1443]
完成     [761,1395][829,1443]
```

## 反证（临时改回 Column 写法后，同一用例在 SHARK PRS-A0 上失败）

```
java.lang.AssertionError: 三个操作按钮应同处一行，实际顶部坐标：[174.54546, 222.54546, 174.54546]
    at com.videodown.douyin.AppUpdateEntryDeviceTest.actionsShareOneRowWhenWidthAllows(AppUpdateEntryDeviceTest.kt:116)
```
