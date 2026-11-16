# 用户体验优化验收记录（2026-11-16）

## 本轮范围

- 主页面统一使用 `safeDrawing` Insets；播放器和看图器分别处理顶部、底部与横向刘海/挖孔安全区。
- 解析空页增加三步引导、剪贴板用途说明和「粘贴并解析」主操作。
- 任务、已存空状态增加可执行动作；搜索/筛选无结果可一键清除条件。
- 任务页压缩统计与工具栏，没有活跃任务时隐藏队列摘要；已存页改为「搜索 + 筛选」并将完整条件移入可滚动底部面板。
- 解析结果默认只展示推荐选项，音轨、字幕、逐项画质和打包选项通过「更多下载选项」展开。
- 手机保持全宽，大屏内容限制在 840dp；设置页同步使用相同限宽策略。
- 恢复三个动态快捷方式的发布。

## 自动化结果

### JVM 与构建

命令：

```text
./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

结果：通过。新增 `UiDensityPolicyTest`，并扩充 `LibraryQueryTest` 对活动筛选数量、清除筛选和空状态策略的覆盖；中英文资源键一致性检查随 JVM 套件通过。

完成安全区去重后再次复跑时，工作区在 21:52 新增了本轮之外的 `presentation/sites/` 文件；这些文件引用的 `sites_*` / `site_channel_*` 字符串尚不存在，并包含一处未 opt-in 的 Material 3 API，当前 `compileDebugKotlin` 因此失败。该失败发生在上述通过记录之后，且不属于本轮文件，未擅自代改；合并前需由站点功能的改动方补齐资源并重新跑本命令。

### 设备测试

环境：Pixel 10 Pro AVD，Android 17 / API 37。

逐类通过：

- `AdaptiveContentDeviceTest`：1200dp 宿主下内容宽度不超过 840dp。首次运行发现 modifier 顺序导致仍占满 1200dp，修正后通过。
- `TaskSearchDeviceTest`：紧凑排序入口与任务搜索通过。
- `LibraryKindFilterDeviceTest`：底部筛选面板可滚动，选择图片及恢复全部类型通过。
- `LauncherShortcutsDeviceTest`：三个快捷方式发布及入口行为通过。
- `ResultSelectionDeviceTest`：推荐选项、展开高级选项、批量选择与横屏面板通过。
- `ImageViewerDeviceTest`：看图器交互回归通过。
- `EmptyStateIllustrationDeviceTest#anEmptyLibraryShowsTheIllustrationAndItSettles`：已存空状态「开始下载」动作通过。

全量 `connectedDebugAndroidTest` 运行到 `LocalizationDeviceTest#everyMigratedScreenFollowsTheAppLanguage` 后超过 10 分钟没有用例进展；应用和测试进程仍存活，日志持续出现模拟器 NFC AIDL 服务超时，因此终止该次全量运行并改跑上述相关类。`EmptyStateIllustrationDeviceTest` 全类另有既有形状像素阈值在本 AVD 失败，不影响同类中的空状态动作断言。

## 尚未覆盖

- 尚未在 API 26、30 真机、打孔/刘海真机和折叠屏上人工走查；本轮只证明 API 37 模拟器与 1200dp 测试宿主行为。
- 尚未完成 TalkBack 主路径、系统 200% 字体、减少动态效果与深浅色人工验收。
- 启动器真实长按菜单的视觉呈现仍需人工验证。
- 设置页已统一限宽，但「下载与网络 / 网站与账号 / 备份与存储」独立子页面的结构性拆分未在本轮实施；现有入口和行为保持不变。
- 入队成功仍会直接进入持久可见的任务页；针对部分失败的跨页面结果摘要仍需后续单独设计。
