# 解析结果页布局验证（2026-09-30）

顶部横向视频标签改为固定摘要「当前 X/N · 已选 M 个」和「查看全部」入口，避免视频数量增加后挤占页面及横向截断。点击入口打开自适应缩略图网格，普通窗口三列，320dp 窄窗口两列；超出面板高度时纵向滚动，标题、全选和完成按钮保持可访问。

## 操作行为

- 点击网格卡片切换当前预览并关闭面板，勾选框仅控制批量选择。
- 初始不勾选视频，唯一的底部主按钮保存当前视频；勾选后改为保存所选 N 个视频。
- 每个视频独立保留所选画质。切换预览会回到详情顶部，不隐式改变批量选择。
- 封面按实际窗口高度调整，长标题默认两行，可展开和收起。
- 继续使用原有下载、独立音轨、重复提醒、下载任务和已保存视频入口。

## 构建与检查

`assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 成功；189 项 JVM 测试通过，0 失败/错误/跳过。Lint 为 0 错误、74 警告、2 提示，没有新增警告。静态代码复审通过。

## 模拟器真实交互

设备为 Pixel 10 Pro / Android 17 arm64，分辨率 1280×2856，分别以 density 480（约 426dp 宽）和 density 640（320dp 宽）执行。

- 普通窗口 15 项通过：新增结果页 4 项、批量功能 6 项、画质卡片 1 项、结果页返回 3 项、X 多视频离线切换 1 项。
- 窄窗口新增结果页 4 项通过：长标题展开/收起；勾选与预览相互独立且按勾选项目保存；切换至第 12 个视频并保留各自画质；全选/取消全选及面板展开时旋转横屏后访问末项。
- 已直接检查真实截图。测试结束后恢复模拟器 density 480。

测试运行在 MainActivity 中渲染实际 HomeScreen，使用确定性的 12 视频数据和下载替身。截图中灰色封面来自测试数据未提供 coverUrl；这些测试验证布局及传入下载的媒体/画质，不代表本轮重新验证线上解析、实际传输、相册或真机效果。

## 截图与日志

- [普通窗口详情](result-ui-evidence/result-summary.png)
- [普通窗口网格](result-ui-evidence/result-selection.png)
- [320dp 详情](result-ui-evidence/result-summary-320dp.png)
- [320dp 网格](result-ui-evidence/result-selection-320dp.png)
- [构建日志](result-ui-evidence/build.txt)
- [普通窗口设备测试](result-ui-evidence/device-426dp.txt)
- [窄窗口设备测试](result-ui-evidence/device-320dp.txt)

## 安装包

`app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk`

SHA-256：`b1fd9ed4df5ffbe957be392ba9ca919f91edf8b491829fa6198c1bd999476ff7`

该安装包包含本次结果页调整；此前 P3 验证文档中的哈希对应调整前的包。
