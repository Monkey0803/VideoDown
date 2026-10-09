# 功能排查与后续顺序（2026-10-09）

> 本文是历史快照，后续排期维护在 [功能待办（TODO）](TODO.md)；此处不再更新。

依据当前源码、已有验收记录和本轮真机结果整理。初始阶段只排查，后续按用户要求执行 P1；未重新执行所有历史设备测试。P1 逐项结果见 `docs/validation/p1-remaining-2026-10-09.md`；P2 与正式升级最新结果见下表及证据入口。

## 已具备的功能

平台及通用网址解析、网页辅助、批量导入、画质选择、重复提醒、下载队列与排序、暂停续传、分片合并、失败分类恢复、共享空间预留、残留清理、收藏分类和重命名、相册与文件夹导出、独立音轨及离线提取、播放续播与最高 4× 倍速、后台音频及画中画均已有实现。不要重新列为缺失功能。

并发空间管理本轮完成 199 项 JVM 检查及 4 项真机测试。真机四项限于本地媒体下载、合并取消、离线提取恢复和模拟空间不足监控，不能代替真实站点、后台下载服务、通知或完整播放界面的验收。

## 优先处理

| 优先级 | 项目 | 当前性质 | 下一步与完成标准 |
| --- | --- | --- | --- |
| P1 | 画中画锁屏后返回 | 无锁屏及安全锁屏人工解锁已通过 | 连续 3 轮 Home/返回、播放暂停、真实系统关闭和释放已通过；追加两轮安全锁屏正常人工解锁，原播放器/视频/断点保留，实际 UI 恢复播放、暂停及结束释放通过。锁屏后按既有行为暂停，不自动播放。 |
| P1 | 后台下载服务的失败反馈 | 主要流程及熄屏人工解锁已通过 | 等待前台启动确认、断点、后台下载、通知暂停、中断反馈及真实按钮重试均已验证；追加安全熄屏继续传输、人工解锁后完整 8 MiB 文件与生产媒体校验、服务及通知退出专项通过。Android 15 系统时限仍未覆盖。见 background-download-2026-10-09.md。 |
| P1 | 实际网络变化与进程退出恢复 | 真实 Wi-Fi 与直接/引擎恢复通过；移动网络按用户要求暂缓 | 实际 Wi-Fi 断开/恢复、Range 断点、队列、哈希和每项一次完成已通过；生产直链及公开 YouTube 引擎下载被真实 force-stop 后恢复已通过。双 SIM 为空，移动网络仍未验收，不以 Wi-Fi 结果替代。 |
| P1 | 真实站点及登录恢复 | 公开站点及 404 恢复通过；账号及网站挑战按用户要求暂缓 | 已修复容器、注释、时间戳、媒体校验和旧网页弹层缺陷。MDN 旧源真实 404→原文档恢复→自然发现新视频→完整下载通过。抖音匿名重解析未取得视频，W3C 提取遭挑战；真实账号及挑战完成后的恢复下载保留未验收。 |
| P2 | 通知、后台音频与耳机交互 | 完成通知与后台音频真机通过 | 完成通知开关、两条系统卡实际精准点击和重建不重复跳转通过，修复已存页泛跳转与 PendingIntent 共享目标。音频 Home、系统通知实际暂停/播放、安全熄屏续播及正常人工解锁后同媒体和进度保留通过；另补真实后台返回播放器界面。物理耳机拔插仍未覆盖。详见 p2-device-2026-10-09.md。 |
| P2 | 真机文件导出与 Release 验收 | 指定四项及导出失败、正式升级补验已通过 | 相册/音乐/系统目录导出可读；新增权限失效明确提示并清除失效目标、重新授权新目录恢复，中途 ENOSPC 配额故障明确反馈并同目录重试，完整真机 2 项通过。正式包 1.0.0/code 1→1.0.1/code 2 升级后旧媒体/偏好/UID保留，新下载播放导出通过。空间测试未填满物理设备，详见 export-failure-release-upgrade-2026-10-09.md。 |
| P2 | 验证记录和待提交项收尾 | 文档已同步，等待提交确认 | README 已同步最高 4×；P3 与通用引擎历史记录追加日期明确的真机结果，保留原验收边界。本次提交范围包含通知定位修复、导出失败反馈、1.0.1/code 2、LibraryScreen 去重、并发空间及 P2 真机测试与证据。P1 已在 4ea09f9 提交并推送。 |

## 可选新增功能

这些不是现有需求遗漏，需先确认产品方向：

- 空间预算可视化：**已实现**（`domain/model/StorageUsage.kt` 的 `StorageBudget`、`data/download/StorageReservations.snapshot()`、`DownloadEngine.storageBudget()`、`presentation/settings/StorageDialog.kt`）。存储界面在物理可用量和文件占用之外，显示运行任务尚需写入的预算总量、占用比例条和按剩余字节排序的任务明细；预留超过可用空间时给出缺口字节数和清理建议。见 `docs/validation/space-budget-2026-10-09.md`。
- 下载自动继续：**已实现**（`AppPreferences.autoResume`、`DownloadPauseCause`、`DownloadEngine.autoResume`、`AutoResumeBudget`）。默认开启，网络恢复、空间释放或进程重启后自动继续被网络/空间中断的任务；用户主动暂停永不自动继续，可在「我的」关闭。见 `docs/validation/auto-resume-2026-10-09.md`；设备侧真实 Wi-Fi 断开/恢复与真实磁盘写满的端到端验收仍待补。
- 更新入口：**已实现**（`data/update/AppUpdateChecker.kt`、`presentation/settings/UpdateDialog.kt`、`core/AppPreferences.updateManifestUrl`）。「我的 → 检查更新」显示当前版本，读取可配置的版本清单 JSON 后提示新版本、更新说明和下载/发布页链接，更新源可用 `-PupdateManifestUrl` 或对话框内设置。引擎仍随 APK 固定更新，不含独立引擎热更新；发布方需自行提供清单托管地址。

## 不计入本轮待办

DRM 解密、直播录制、整站采集不在现有范围；ijkplayer 尚未接入，但当前已有 Media3 播放器，不能将未替换播放器直接视作缺失功能。

## 证据入口

- `docs/UI_ACCEPTANCE_2026-09-30.md`
- `docs/validation/p1-features-2026-09-30.md`
- `docs/validation/p2-features-2026-09-30.md`
- `docs/validation/p3-features-2026-09-30.md`
- `docs/validation/universal-video-2026-09-29.md`
- `docs/validation/concurrent-storage-2026-10-09.md`
- `docs/validation/space-budget-2026-10-09.md`
- `docs/validation/auto-resume-2026-10-09.md`
- `docs/validation/p2-device-2026-10-09.md`
- `docs/validation/export-failure-release-upgrade-2026-10-09.md`
