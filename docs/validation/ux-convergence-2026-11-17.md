# 用户体验收敛验收记录（2026-11-17）

## 范围

- 全局下载结果中心改为按字节汇总进度，同时显示本次新发生的失败/暂停数量；历史异常不在启动后重复占位。
- 完成结果进入「已存」，其余结果进入「任务」；非进行中结果可关闭，未查看结果在任务入口显示徽标。
- 清除网页登录态增加影响说明和二次确认；设置恢复增加变更预览，确认后才写入。
- 任务卡只保留状态主操作，删除、分享、限速、重新下载与队列位置调整进入具名的「更多任务操作」。
- 设置首页增加「关于与更新」，检查更新和关于应用不再混在「备份与存储」。
- 新增统一间距 Token 和常用文字行高；840dp 及以上窗口使用 Navigation Rail。

## 自动化结果

### JVM 与 Android 测试编译

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
```

结果：通过。

- `DownloadCenterSummaryTest` 覆盖按字节加权进度、未知总量、本次异常和完成结果。
- `SettingsBackupPreviewTest` 覆盖有效变化、非法字段过滤、无变化备份和已存顺序预览。
- 既有本地化键一致性、主题对比度和关闭系统动画测试随 JVM 套件通过。

### 设备测试

环境：Pixel 10 Pro AVD，Android 17 / API 37。

以下类逐类通过：

```text
DownloadCenterSettingsDeviceTest
AppUpdateEntryDeviceTest
LocalizationDeviceTest
SpeedLimitDeviceTest
```

`DownloadCenterSettingsDeviceTest` 共覆盖六条主路径：

1. 等待任务转暂停后出现本次结果，点击进入对应任务，并从具名的更多菜单看到限速和删除。
2. 等待任务转完成后显示完成结果，点击进入「已存」。
3. 设置首页五个分类均可进入并返回。
4. 注入 200% 字体后五个分类仍能滚动到达。
5. 清除网页登录态必须先出现确认框。
6. 注入 1200dp 窗口配置后显示 Navigation Rail，并隐藏底部导航栏。

回归类确认「关于与更新」中的检查更新、中英文切换，以及任务菜单中的单任务限速仍可用。

## 未覆盖边界

- 200% 字体和 1200dp 使用 Compose 配置宿主验证，不等同于真实平板、折叠屏或 OEM 字体布局人工走查。
- 无障碍覆盖了关键操作的 Compose 语义名称与 48dp 触控区域，但尚未用真实 TalkBack 完整朗读并操作主链。
- 本轮没有 API 26、API 30 真机，也没有折叠态、分屏和外接键盘人工验收。
- 下载结果的未查看状态只在当前进程维护；应用被系统杀死后的完成结果继续依赖系统通知和持久任务记录，不在下次启动重复弹条。
