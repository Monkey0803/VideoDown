# 全局下载结果中心与设置页拆分验收（2026-11-16）

## 范围

- 普通解析和批量选择入队后不再强制跳到任务页，结果页保留，入队结果仍通过现有提示显示。
- 「解析 / 已存 / 我的」在存在可处理任务时显示全局下载条；任务页不重复显示。
- 下载条按「进行中 → 失败 → 暂停 → 本次完成」显示单一推荐状态，进行中任务展示可计算的平均进度；点击进入任务页。
- 历史完成记录不会在每次启动时重新提示；只有本次会话从等待/运行转为完成的任务计入完成结果，进入任务页后视为已查看。
- 「我的」拆为「外观与语言 / 下载与网络 / 网站与账号 / 备份与存储」四个子页，系统返回和页内返回均回到设置首页。
- 存储失败恢复入口继续直接打开「备份与存储」中的存储空间弹窗。

## 自动化结果

### JVM 与编译

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
```

结果：通过。`DownloadCenterSummaryTest` 覆盖状态优先级、平均进度、纯完成历史不重复提示，以及本次完成结果。

### 设备测试

环境：Pixel 10 Pro AVD，Android 17 / API 37。

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew connectedDebugAndroidTest -Pabi=universal \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.DownloadCenterSettingsDeviceTest

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew connectedDebugAndroidTest -Pabi=universal \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.ResultSelectionDeviceTest
```

结果：通过。

- `DownloadCenterSettingsDeviceTest`：暂停任务在非任务页出现下载条，点击后落到对应任务；四个设置入口均可进入并返回。
- `ResultSelectionDeviceTest`：批量入队后保持结果页，不再自动触发任务页导航；既有选择、画质和横屏用例一起回归。
- 受设置层级变化影响的 `P1FeaturesDeviceTest`、`AppUpdateEntryDeviceTest`、`XLoginSessionDeviceTest`、`LanguageSwitchDeviceTest` 与 `LocalizationDeviceTest` 已改为先进入对应子页，并通过 Android 测试源码编译。

## 尚未覆盖

- 尚未在 API 26、API 30 真机、折叠屏和系统 200% 字体下人工确认下载条与四入口设置首页的视觉密度。
- 本次完成提示只覆盖当前进程内观察到的状态迁移；应用被系统杀死后完成的任务仍以系统完成通知和任务页记录为准，不在下次启动重复弹结果条。
