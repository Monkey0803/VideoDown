# 重复提醒、批量操作与搜索筛选验证

日期：2026-09-30。实现与验证均基于当前工作目录。

## 实现范围

| 功能 | 最终行为 | 证据 |
|---|---|---|
| 重复提醒 | 按平台、视频 ID、画质及引擎格式匹配；可查看已有记录或明确再次下载 | `DownloadSelection.kt`、`DownloadEngine.findDuplicate`、HomeBatchDownloadTest、LibraryQueryTest、设备重复弹窗用例 |
| 文件丢失与历史恢复 | 已完成记录的文件缺失时允许下载；重新加载历史后仍能识别可播放副本，优先返回已保存文件 | DownloadPreferencesIntegrationTest 新增 2 个实际历史/文件用例 |
| 多视频下载 | 勾选/全选，每个视频独立保留画质；可跳过重复项或强制下载全部选择 | HomeBatchDownloadTest、设备批量下载用例 |
| 部分加入队列失败 | 保留已加入数量和错误，留在结果页，避免跳转后看不到失败 | HomeBatchDownloadTest 部分失败用例 |
| 任务多选 | 对所选且状态适用的任务暂停/继续/删除；全部暂停和全部继续可分别使用 | HistoryBatchTest、设备任务多选用例 |
| 搜索与筛选 | 标题/作者搜索，可组合平台筛选；最新/最早保存及文件大小排序 | LibraryQueryTest、设备搜索/平台筛选用例 |
| 当前结果选择 | 两种布局均可多选；全选限当前结果；改变筛选条件清空选择 | 设备筛选结果删除及布局用例 |
| 批量删除 | 确认时捕获具体 ID 集合，取消保留文件，未选视频不受影响，相册副本不删除 | HistoryBatchTest、设备确认取消/筛选删除用例 |
| 批量相册保存 | 逐项导出，进度与成功/跳过/失败数量；单项失败继续其余项目 | HistoryBatchTest、设备真实 MediaStore 导出用例 |
| 失败明细与重试 | 记录失败项 ID、标题和原因，弹窗展示完整明细；导出/删除后只保留失败项选中 | 设备 MP4 成功 + MKV 失败混合用例；重试后成功视频的相册副本仍恰好 1 个 |
| 小屏幕与键盘 | 高度不足及多选时采用紧凑头部；搜索完成或操作筛选按钮会收起键盘 | 320dp 宽设备运行上述全部 6 个新增用例 |

## 执行结果

- JVM 单元测试：143 项，0 失败、0 错误、0 跳过。
- Android 17 arm64 模拟器：6 个新增 Compose 交互用例，全部通过；最终运行宽度 320dp，结束后恢复原始密度 480。
- 回归：LibraryActionsDeviceTest 的 4 项长按菜单、播放回调、删除确认及丢失文件提示用例通过；QualityCardsDeviceTest 的 1 项尺寸与格式选择用例通过。
- `assembleDebug` 通过，产物：`app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk`。
- `lintDebug` 通过（无错误，仍有项目级警告）；变更文件空白检查通过。
- 独立只读审查已完成；审查提出的失败项识别与重试范围问题已修复，复审无阻塞项。

最终验收命令（使用 Android Studio 自带 JBR）：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.BatchFeaturesDeviceTest \
  --console=plain
```

最终设备 XML 留存在 `app/build/reports/feature-acceptance/batch-narrow.xml`。

## 验证边界

设备交互测试使用隔离的视频、历史记录和下载调用替身，不触碰用户下载；文件删除及 MediaStore 相册复制是真实操作。单元集成用例使用真实本地历史存储验证重复识别。此轮未重新进行远程平台完整下载，也未在 Android 8/9 真机重复验证旧版存储权限弹窗；下载传输及权限处理继续复用现有实现。
