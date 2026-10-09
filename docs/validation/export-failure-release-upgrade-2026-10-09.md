# 导出失败反馈与正式版本升级验收（2026-10-09）

## 范围与当前状态

| 要求 | 当前状态 | 验收依据 |
| --- | --- | --- |
| 导出目录授权失效反馈与恢复 | 真机完整通过 | 真实系统授权后目录所有者撤销本轮权限，Android 拒绝访问；生产界面提示重新选择、清除失效目标并隐藏失效重试；真实新系统目录授权后完整副本及源文件保留通过 |
| 空间不足反馈与恢复 | 真机故障注入通过 | 实际写入 4096 字节后 ENOSPC；生产界面明确空间不足、半成品清理、源保留、移除配额后同目录重试成功 |
| 提升 versionCode 并验证真正升级 | 真机升级、数据保留及主要流程通过 | code 1→2 同签名 install -r Success；UID、首次安装时间、旧媒体/偏好保留，新媒体下载播放和可读导出通过 |

证据目录：`docs/validation/export-failure-release-upgrade-2026-10-09/`。

## 实施说明

此前导出失败统一提示检查权限、文件和空间，无法辨认原因。现在分别统计目标授权/可写性失效、目标空间不足和其他失败，提供可操作的中文反馈。失效目标仅在匹配当前保存目录时清除，并隐藏对失效目录的重复导出入口；空间不足保留同目录重试入口。已导出项不重复导出，失败项及本地源保留。中断副本检查本身失败时也结束忙碌状态并提示恢复授权后检查清理。

空间不足验证不填满用户设备。配额 Provider 只在测试 APK 中存在，通过真实 Android 文件描述符写入返回 ENOSPC，验证生产复制、错误分类、界面反馈和恢复；这是设备故障注入，不宣称验证了整台设备物理存储耗尽。

旧正式 APK 从当前已安装包拉取到工作区外临时目录，大小 78300071 字节、SHA-256 `c83b9281b0b699917b19b9e5ebe899ab4d467e9129e771a94f27ce8cb500e53f`，签名校验通过，证书 SHA-256 `925e4c248ba7f70e9eb5e3b8587ee7bde384476799cbd097dd65746a6d29c50d`。不卸载、不清数据、不改锁屏设置，不读取签名凭据；只清理本轮隔离记录、副本与新增权限，恢复原偏好。

新签名正式包为 versionCode 2 / versionName 1.0.1，大小 78301719 字节，SHA-256 `793b62f0e41a58977964f42a7a0d6bbbef021507180c211e45bdeb9faa8a0b91`，同证书校验通过。正式 Manifest 不包含配额及控制测试 Provider；包元数据见 `package-upgrade-metadata.json`，签名证据见 `code2-certificate.txt`。仅构建不代表真机升级已完成。

实际 code 1 通过公开微博链接下载 540P 媒体，播放进度推进至 00:12 / 01:07，真实相册转存 8754435 字节，SHA-256 `f5c38868e3dea6358cf1b733c76e2b94590ddb254df4474de5b74133d938dd07`，默认画质设置为节省空间、完成通知开启。随后实际 `install -r` 成功，系统确认 code 2 / 1.0.1、UID 10272、firstInstallTime 16:18:18 保留，lastUpdateTime 17:26:50。真实设置和已存页确认两项偏好与旧 540P 记录保留，见 `code2-installed-metadata.json`、`code2-install-r.txt` 与 `release-evidence`。媒体完整性及新包主要流程验收结果如下。

升级后旧视频真实播放推进至 01:01 / 01:07，重新转存文件仍为 8754435 字节且与升级前 SHA-256 完全一致（`code2-old-media-hashes.json`）。新包真实解析、下载 720P 视频并播放至 01:03 / 01:07；转存文件 13434699 字节，SHA-256 `a6baec536d88eaf1c8ce5b8f88391534bfaa79fa8b3e744269150a0bf6f786ab`。独立媒体读取 `code2-exported-media-validation.txt` 为 OK (1 test)，2 轨道、66849 毫秒、视频样本可读。真实文件最终校验见 `release-final-export-hashes.json`。一次自己的 uiautomator dump 未退出导致后续桥接 UiAutomation 注册冲突，保留原失败日志，收尾该占用后串行复验成功，不计正式包崩溃或首轮通过。

## 检查与提交状态

最终当前代码完整 `P2ExportFailureDeviceTest` runner 为 `OK (2 tests)`，13.61 秒，见 `export-failure-two-final.txt`。两种失败反馈、真实权限撤销、新目录恢复、中途 ENOSPC、同目录恢复和清理恢复在同一完整 runner 内通过。首次完整运行的选择器动画导致点击 false 记录另存，不计通过；修正只改测试选择器稳定等待及可见启用节点匹配，生产行为未改。

授权失效最终 `export-access-new-directory.txt` 为 `OK (1 test)`，7.766 秒。系统选择器真实授权并完成首次复制后，目录所有者 UID 10271 撤销目标 UID 10270 的本轮权限，tree/children 权限从 0 变为 -1，query 抛 SecurityException。生产界面明确提示权限失效，偏好清除且失效重试入口消失；通过真实 DocumentsUI 在外部存储创建唯一验收目录，使用并允许后成功复制，完整字节与源媒体未改变。仅清理本轮目录、副本、记录和新增权限，恢复原偏好。最终恢复采用新目录；中间同测试根恢复失败不计通过。

空间不足最终 `export-enospc-root-supported.txt` 为 `OK (1 test)`，5.481 秒。选择器授权前读取拒绝，initialUri 仅定位目录；通过真实 DocumentsUI 获得持久读写 grant 后生产导出，OS ProxyFD 记录实际写入 4096 字节、ENOSPC 1 次。生产界面显示明确空间不足反馈，目标副本已清理，源文件字节不变；移除测试配额后点击同目录重试，完整副本及媒体有效，清理恢复通过。截图见 `p2-export-failure-evidence/enospc-visible.png`、`enospc-recovered.png`。

JVM `FileExportTest` 共 7 项，失败、错误、跳过均为 0；覆盖撤销权限、空间不足、失败项重试、错误分类和中断清理查询失败。完整 JVM 共 211 项，失败、错误、跳过均为 0；Lint 为 0 错误、85 警告、2 提示，未宣称警告已修复。Debug/测试 APK 及签名 Release 构建通过（`build-focused-export.txt`、`build-unit-lint-release-final.txt`）。

真机首轮在准备夹具 call 时被系统 Provider 权限拦截，未进入生产导出，不计产品失败或验收通过。本机 shell 也无 MANAGE_DOCUMENTS；最终采用测试 APK 自身的隔离控制 Provider，只允许 Debug 应用 UID 配置测试配额/统计/撤销固定测试目录的授权，没有文档读写绕过接口。实际选择器和生产导出全程使用正常授权。仅 releasePersistableUriPermission 不足以去除临时授权，因此由目录所有者撤销真实测试 URI 权限，并确认持久授权消失且 query 抛 SecurityException 后验证失败反馈。

测试 Provider 首轮遗漏目录选择器所需的 FLAG_SUPPORTS_IS_CHILD，系统因此过滤该根；补齐后真实授权及空间测试通过。后续恢复选择的临时 UI 导航、标题歧义和同测试根缺少使用按钮失败全部保留原日志，不计完整通过。授权失效恢复现改为选择新建的隔离真实系统目录，空间测试已覆盖同目录重试。

最终代码审查为 APPROVE，各级问题均为 0，见 `code-review-final.md`。收尾状态见 `final-state.json` 与 `security-and-cleanup-final.txt`：本轮两条正式包记录、三份相册副本、测试目录和夹具均已清理，原偏好恢复，网络、主题及安全锁屏配置保留。真机保留 1.0.1/code 2 正式包，停在空输入首页，无下载服务，升级后未观察到正式包崩溃。两项要求均已完成。

当前未暂存、提交或推送，保留通知修复、LibraryScreen 去重和并发空间管理等既有未提交改动。
