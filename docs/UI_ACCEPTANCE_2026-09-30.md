# UI 验收记录：2026-09-30

本轮对当前 Debug 构建进行模拟器 UI 验收，包含实际点击、输入、旋转、播放、截图和 UI 层级检查。结果不替代真机或 Release 安装验证，也不代表各平台的线上解析服务均已验证。

## 环境与范围

- 设备：`emulator-5554`，arm64，API 37。
- 常规屏幕：1280 × 2856；补充窄屏：1080 × 2400、480 dpi，约 360 × 800 dp。
- 构建：`:app:assembleDebug :app:assembleDebugAndroidTest` 成功。
- 保留现有 app 数据；列表编辑、删除、导出等自动化操作使用独立测试数据。
- 本轮未新增应用源码改动；验收包含此前倍速、播放图标、输入下划线和对齐修复。

## 自动化结果

15 个测试类共 58 项：首次运行 56 项通过、1 项失败、1 项跳过。不能将首次结果表述为全部通过。

| 测试类 | 首次结果 | 检查内容 |
| --- | --- | --- |
| HomeKeyboardDeviceTest | 5 通过 | 多行输入、键盘避让、完成、返回、再次聚焦 |
| HomeResultBackDeviceTest | 3 通过 | 解析结果返回与页面状态 |
| QualityCardsDeviceTest | 1 通过 | 混合画质卡片尺寸与选择 |
| ResultSelectionDeviceTest | 4 通过 | 长标题、12 视频选择、预览、横屏滚动 |
| Material3NavigationDeviceTest | 3 通过 | 四个页面导航、中文资源、画质确认与取消 |
| AppearanceModeDeviceTest | 1 通过 | 明暗模式、持久化、跟随系统 |
| DownloadSettingsDeviceTest | 4 通过、1 跳过 | 下载偏好、通知关闭、Wi-Fi 限制 |
| BatchFeaturesDeviceTest | 6 通过 | 批量任务、影像库多选与导出 |
| P2BatchImportDeviceTest | 4 通过 | 批量导入、画质选择、旋转、重复确认 |
| P2LibraryOrganizationDeviceTest | 3 通过 | 收藏筛选、重命名分类、错误反馈与重试 |
| LibraryActionsDeviceTest | 4 通过 | 长按菜单、播放、删除确认、缺失文件提示 |
| P2AudioUiDeviceTest | 4 通过 | 音频提取入口、失败提示、分享与播放暂停 |
| P3PlaybackDeviceTest | 7 通过 | 断点、倍速、循环、图标切换、2× 与 4× 实际生效 |
| PlayerLandscapeDeviceTest | 4 通过 | 视频比例、横屏全屏、控件与系统栏 |
| P3PictureInPictureDeviceTest | 3 通过、1 失败 | 画中画进入、控制、关闭、锁屏与返回 |

完整首次日志：[instrumentation.txt](../build/ui-verification/overall/instrumentation.txt)。

## 保留观察

1. `pipScreenOffThenReturnKeepsActivityAndPlayer` 在首次混合运行中等待退出画中画超时。单独重跑该项通过（3.504 秒）；再单独运行整组 4 项画中画测试全部通过（11.55 秒）。尚未确定根因，不能宣称已经修复。原始失败和复验均保留：[单项复验](../build/ui-verification/overall/pip-rerun.txt)、[整组复验](../build/ui-verification/overall/pip-class-rerun.txt)。
2. 下载完成通知的开启测试因 Debug app 未获得通知权限跳过；不作为通知已验证的证据。这不阻塞本轮布局验收。
3. 部分自动化截图将页面单独挂载，缺少正式导航容器的系统栏间距。正式 app 的四页面与窄屏长按菜单截图显示正常；不将测试容器的背景遮挡误判为 app 缺陷。

## 页面与弹窗视觉证据

已检查常规屏幕下的四页面、有内容的任务列表与影像库、结果选择、默认画质、明暗设置，以及窄屏下的四页面与主要弹窗。当前已检查状态未发现可稳定复现的文字重叠、非预期截断或按钮错位。列表筛选使用横向滚动，长结果标题有展开入口，属于预期行为。

| 范围 | 截图 |
| --- | --- |
| 首页、任务、影像库、设置 | `build/ui-verification/overall/material3/16-{home,tasks,library,settings}.png` |
| 明暗模式 | `build/ui-verification/overall/material3/appearance-{light,dark}.png` |
| 输入与键盘 | `build/ui-verification/overall/cache/keyboard-ui-test-artifacts/keyboard-{visible,dismissed}.png` |
| 结果长标题与视频选择 | `build/ui-verification/overall/cache/result-ui-evidence/result-{summary,selection}.png` |
| 横屏播放控件 | `build/ui-verification/overall/cache/player-ui-test-artifacts/landscape-player.png` |
| 窄屏四页面 | `build/ui-verification/overall/narrow-{home,tasks,library,settings}.png` |
| 窄屏默认画质与并发数量 | `build/ui-verification/overall/narrow-{quality,concurrency}-dialog.png` |
| 窄屏批量导入 | `build/ui-verification/overall/narrow-batch-dialog.png` |
| 窄屏正式影像库菜单与重命名 | `build/ui-verification/overall/narrow-library-menu.png`、`narrow-rename-dialog.png` |
| 窄屏播放与暂停图标 | `build/ui-verification/overall/narrow-player.png`、`narrow-player-paused.png` |
| 竖屏全部倍速选项 | `build/ui-verification/overall/narrow-speed-dialog-portrait.png` |
| 强制横向窗口倍速滚动到 4× | `build/ui-verification/overall/narrow-speed-dialog-landscape.png`、`narrow-speed-dialog-landscape-scrolled.png` |

窄屏手动截图旁的同名 `.xml` 文件保存了 UI 层级。验收产物统一位于 `build/ui-verification/overall/`。

倍速弹窗的横向截图通过临时强制横向窗口取得，用于验证较短高度下的滚动与控件可达性；不作为正常全屏播放器进入该弹窗的证据。实际全屏播放与返回路径由 `PlayerLandscapeDeviceTest` 和横屏播放器截图验证。竖屏下 2× 与 4× 均直接可见；横向短窗口可滚动到 4×，关闭按钮保持可见。

## 收尾与结论

模拟器已恢复到物理 1280 × 2856、480 dpi，无尺寸覆盖；恢复自动旋转、原始方向和夜间模式。app 外观跟随系统、影像库瀑布流与默认画质保持原值；手动检查没有更改原有 1.75× 与循环开启设置。状态证据：[restored-device-state.txt](../build/ui-verification/overall/restored-device-state.txt)。

本轮 UI 验收已完成，未发现新的可稳定复现的布局缺陷，没有额外修改应用源码。画中画锁屏返回的首次超时仍作为待观察项保留，通知权限导致的跳过单独记录。后续发布前应对 Release APK 和真实设备进行安装与画中画锁屏返回验证。
