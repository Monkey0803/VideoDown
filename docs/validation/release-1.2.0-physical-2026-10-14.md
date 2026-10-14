# 拾影 1.2.0 真机补验（2026-10-14）

## 环境与范围

- 真机：SHARK PRS-A0，Android 11 / API 30，ADB 序列号 `4f0ef8ac`。
- 正式包：`.tooling/release-v1.1.1/videodown-arm64-v8a-release.apk` 与 `.tooling/release-v1.2.0/videodown-arm64-v8a-release.apk`。后者 SHA-256 为 `a85fdded1e0db63b010eb8bf43cbb4ce630ae3d674324d1fe5ac00b66b371c5a`，与 [1.2.0 发版记录](release-1.2.0-2026-10-13.md)一致。
- 真机原先只有 `com.videodown.douyin.debug` 1.1.1；正式包应用 ID 是 `com.videodown.douyin`，因此先并列安装 1.1.1 正式包，再做正式包覆盖升级。随后为了验证旧媒体保留，仅重建了**本次验收安装的正式包**测试数据，再做一轮带媒体的 1.1.1 → 1.2.0 升级。Debug 包未卸载或清数据；其未提交输入框受到影响，见文末。

## 覆盖升级结果

| 核对项 | 1.1.1 正式包 | 覆盖至 1.2.0 后 | 结论 |
| :--- | :--- | :--- | :--- |
| `versionCode` / `versionName` | 6 / 1.1.1 | 7 / 1.2.0 | 通过 |
| `firstInstallTime` | 2026-10-14 19:59:05 | 2026-10-14 19:59:05 | 没有重装 |
| `lastUpdateTime` | 2026-10-14 19:59:05 | 2026-10-14 20:00:53 | 已更新 |
| `ceDataInode` | 364204 | 364204 | 同一应用数据目录 |

1.1.1 正式包在真机上通过应用内更新入口识别线上 1.2.0 清单；`adb install -r` 覆盖安装 1.2.0 成功，升级后可正常启动。升级前设置页显示“深色”“同时下载 2 个任务”，升级后这两项仍显示；新版本设置页显示 `v1.2.0`，已存页空态插图可见。

证据：[升级前包信息](release-1.2.0-2026-10-14/physical-api30/01-before-package.txt)、[升级前设置](release-1.2.0-2026-10-14/physical-api30/01-before-profile.png)、[旧版识别更新](release-1.2.0-2026-10-14/physical-api30/02-before-update-detected.png)、[升级后包信息](release-1.2.0-2026-10-14/physical-api30/03-after-package.txt)、[升级后设置](release-1.2.0-2026-10-14/physical-api30/03-after-profile.png)、[已存空态](release-1.2.0-2026-10-14/physical-api30/04-saved-empty.png)、[版本信息](release-1.2.0-2026-10-14/physical-api30/05-after-version.png)。同目录保留对应 UI 层级 XML。

## 旧媒体随正式包升级

第二轮在 1.1.1 正式包里用本地 MP4 保存两条媒体，两条记录均在“已存”可见并可进入播放器；其中 landscape 素材播放界面显示约 `00:12 / 01:00`。升级前文件大小分别为 56,341 字节、10,542 字节，并记录文件路径与 SHA-256。随后 `adb install -r` 覆盖 1.2.0，**两条旧记录仍在、两条旧文件均可再次打开播放，路径、大小和 SHA-256 与升级前逐项一致**。旧版设置的“同时下载 2 个任务”也保留；版本 6 → 7，`firstInstallTime=2026-10-14 20:25:27` 与 `ceDataInode=360111` 均不变。该结果直接覆盖了此前只检查旧设置、没有旧媒体的验收缺口。

证据：[1.1.1 两条已存](release-1.2.0-2026-10-14/physical-api30/32-v111-two-saved.png)、[1.1.1 播放](release-1.2.0-2026-10-14/physical-api30/34-v111-landscape-playback.png)、[升级前媒体摘要](release-1.2.0-2026-10-14/physical-api30/37-v111-media-size-sha256.txt)、[升级后媒体摘要](release-1.2.0-2026-10-14/physical-api30/38-v120-media-size-sha256.txt)、[升级后包信息](release-1.2.0-2026-10-14/physical-api30/38-v120-postupgrade-package.txt)、[1.2.0 两条旧媒体](release-1.2.0-2026-10-14/physical-api30/40-v120-library-old-media.png)、[1.2.0 旧媒体播放](release-1.2.0-2026-10-14/physical-api30/42-v120-old-landscape-playback.png)、[旧设置保留](release-1.2.0-2026-10-14/physical-api30/43-v120-preserved-setting.png)。本次未对跨版本播放进度做独立断言。

## 1.2.0 正式包的真实交互

使用仓库 `app/src/androidTest/assets/` 内的两个 MP4，经本机 HTTP 服务与 `adb reverse` 供真机访问；通过系统 `ACTION_SEND` 进入正式包。两条链接在解析页分别识别为约 10 KB、55 KB 的视频，保存后“已存”出现两项；点击其中的 AAC 视频进入播放器，显示约 3 秒时长。这验证了正式包的解析、下载、已存展示和播放入口，但不能替代真实网站的联网下载。[解析结果](release-1.2.0-2026-10-14/physical-api30/06-local-result.png)、[两条已存](release-1.2.0-2026-10-14/physical-api30/07-two-saved-before-reorder.png)、[播放器](release-1.2.0-2026-10-14/physical-api30/08-player.png)。

在“已存”切到“自定义排序”，从第二条的拖动把手慢速拖到第一条上方，列表由 `aac → landscape` 变成 `landscape → aac`。强制停止并重新启动正式包后，顺序仍为 `landscape → aac`，筛选项仍显示“自定义排序”。这验证了本版拖拽排序在真机上落盘并在进程重启后恢复。[拖拽前](release-1.2.0-2026-10-14/physical-api30/09-custom-before-drag.png)、[拖拽后](release-1.2.0-2026-10-14/physical-api30/10-custom-after-drag.png)、[重启后](release-1.2.0-2026-10-14/physical-api30/11-custom-after-restart.png)。

横屏打开“重命名与分类”弹窗时，“取消”“保存修改”可见。但横屏点击文件名、分类输入框都未唤起可见输入法；竖屏下同一输入框可以获焦并唤起输入法。竖屏保持焦点后旋转到横屏，系统状态报 `mInputShown=true`，截图里仍没有键盘。因此本次**没有复现“横屏 + 可见键盘”前提**，不能判定键盘弹起时页脚是否可点，也不能直接认定是应用缺陷。[横屏弹窗](release-1.2.0-2026-10-14/physical-api30/12-landscape-dialog-before-ime.png)、[尝试唤起输入法后](release-1.2.0-2026-10-14/physical-api30/13-landscape-dialog-ime-state.png)。

继续验证批量导入弹窗：在竖屏让输入框获焦、确认输入法可见，再保持输入法旋转至横屏。真机截图里键盘占据下半屏，“查看下载任务”“关闭”仍在键盘上方；实际点“关闭”后弹窗退出。这证明**批量导入弹窗**在该条件下的页脚可触达。可用高度很小时头部被滚出当前视口，观感仍需人工确认，不能用此结果替代重命名弹窗。[竖屏输入法](release-1.2.0-2026-10-14/physical-api30/22-portrait-batch-ime.png)、[横屏输入法及页脚](release-1.2.0-2026-10-14/physical-api30/23-landscape-batch-ime.png)、[点击关闭后](release-1.2.0-2026-10-14/physical-api30/24-landscape-batch-closed.png)。

重命名弹窗再用相同顺序复核：竖屏输入法可见；旋转到横屏后系统报告 `mInputShown=false`，再点输入框仍未唤起键盘。故重命名弹窗的“横屏 + 可见键盘”仍未覆盖。[竖屏重命名](release-1.2.0-2026-10-14/physical-api30/25-portrait-rename-ime.png)、[旋转后](release-1.2.0-2026-10-14/physical-api30/26-landscape-rename-ime-attempt.png)、[再次聚焦后](release-1.2.0-2026-10-14/physical-api30/27-landscape-rename-refocus-attempt.png)。

从已存列表进入播放器的首份真机录屏中取得过渡重叠帧，能看到列表界面与播放器界面交叠。由于本地直链素材没有封面图，这份录屏本身不足以判断**封面共享元素位移**；随后另用有封面的页面复核。[首份录屏](release-1.2.0-2026-10-14/physical-api30/28-shared-cover-to-player.mp4)、[中间帧](release-1.2.0-2026-10-14/physical-api30/29-transition-overlap-frame.png)。

随后用本地 HTML 页面提供带青色底、橙框、红圆的封面和视频。1.2.0 正式包解析结果、已存第一张卡均显示这张封面；从卡片进入播放器的录屏中，早期帧仍显示列表卡，随后帧显示同一封面扩展到播放器视频区域，旧列表卡尚在过渡层中。**封面进入播放器的视觉过渡通过**；这不是动画时长、曲线或逐像素轨迹的测量。[解析结果](release-1.2.0-2026-10-14/physical-api30/45-cover-html-parse-result.png)、[已存封面](release-1.2.0-2026-10-14/physical-api30/46-cover-saved-list.png)、[录屏](release-1.2.0-2026-10-14/physical-api30/48-cover-to-player-transition.mp4)、[早期帧](release-1.2.0-2026-10-14/physical-api30/49-cover-transition-frame-early.png)、[扩展帧](release-1.2.0-2026-10-14/physical-api30/50-cover-transition-frame-expanded.png)、[观察记录](release-1.2.0-2026-10-14/physical-api30/51-cover-transition-observation.txt)。

通知首轮用本地限速 Range HTTP 任务尝试。真机系统通知记录确认下载进行时同时存在单任务卡 `id=1100`，带“暂停”动作，以及汇总卡 `id=1001`，带“全部暂停”动作。采集 UI 层级和触控在该机上各耗约 11–12 秒，任务完成前没能进入任务页执行暂停；直接从 ADB 启动非导出的下载服务也被系统拒绝。因此首轮只证明**活动通知卡存在且动作已挂载**，随后改用更长的任务完成实际点击。[首轮通知尝试与清理记录](release-1.2.0-2026-10-14/physical-api30/14-notification-attempt.txt)。

后续改用 2 MiB 的本地 Range 素材，以约 8 KiB/s 传输，留出真机操作时间。在系统通知栏展开单任务卡并实际点“暂停”后，汇总卡 `id=1001` 消失、下载服务退出，单任务卡 `id=1100` 留下并显示“已暂停 / 继续”。从这张保留的系统通知卡实际点“继续”后，`id=1001` 重新出现，`id=1100` 动作变回“暂停”，下载由 428 KB 增至 432 KB；之后任务完成并在任务页显示“已完成 2.0 M”。这条 **1.2.0 正式包真机暂停 → 服务退出 → 通知继续** 路径通过。[暂停时通知状态](release-1.2.0-2026-10-14/physical-api30/18-paused-notification.txt)、[暂停时服务查询](release-1.2.0-2026-10-14/physical-api30/18-paused-service-query.txt)、[继续后通知状态](release-1.2.0-2026-10-14/physical-api30/19-resumed-notification.txt)、[继续后服务查询](release-1.2.0-2026-10-14/physical-api30/19-resumed-service-query.txt)、[完成任务](release-1.2.0-2026-10-14/physical-api30/20-resumed-completed-task.png)。通知栏原图含其他应用消息，保存在本机忽略目录，未纳入仓库。

## 未覆盖边界

- 旧媒体记录、文件与可播放性已在第二轮升级验证；**播放进度**未做升级前后的定量对照。自定义顺序是 1.2.0 新功能，1.1.1 没有对应的旧顺序可升级；1.2.0 内的排序重启持久化已验证。
- 封面进入播放器的视觉过渡已验证；动画时长、曲线与逐像素轨迹未测。横屏输入法下的批量导入页脚功能通过，重命名页脚与弹窗观感仍待人工复核。
- 本次不替代全量 `connectedDebugAndroidTest`，也不替代 Android 15+ 的前台服务时限验证。

收尾时恢复了“每条任务独立通知”开关和设备自动旋转设置；本地 HTTP 服务及 `adb reverse` 端口映射已关闭。1.2.0 正式包及本次产生的测试记录留在真机上。Debug 1.1.1 的包、安装时间与数据目录标识未变，但系统曾意外切回其已有任务；`adb input` 在它首页**未提交的链接输入框**混入了部分测试字符。没有触发 Debug 解析、下载或设置改动；原输入内容无法可靠重建，因此没有再次改写。此处影响不能表述为“Debug 界面完全未触碰”。
