# 拾影图标接入与验证

2026-09-30：推荐方案「拾影框」已接入 Android App。本次保留现有布局、浅深色设置和 Android 12+ 壁纸动态配色；桌面图标保持暖橙品牌色，功能图标使用主题颜色。

## 接入内容

- 桌面图标使用独立渐变背景、奶白前景和透明底单色主题版，普通及 round launcher 均指向同一品牌素材。
- `AppIcon` 与类型化的 `AppIcons` 统一管理图标；SVG 转为 Android VectorDrawable，运行时不依赖 SVG 解析库、网页 CSS、联网或位图字体。
- 四个主导航提供普通及选中形态，选中胶囊使用主题 `primaryContainer`。「已存」选中图标的三角为透明镂空，避免固定白色与动态主题冲突。
- 首页及主视觉使用品牌小标；解析结果的展开、成功和外链符号改为矢量图标，保留中文操作标签。
- 任务页区分播放、暂停、继续下载、网页辅助、重新解析、管理空间及设置；状态保留文字，并配套对应形状。
- 影像库、网页、播放和设置统一替换原 Material 图标；收藏、重命名、导出、音频提取、保存相册使用不同语义。搜索及分类编辑使用配套图标。
- 播放器的原生 PlayerView 播放、暂停及全屏按钮也使用新资源；画中画的播放/暂停动作复用同一套资源。
- 下载与完成通知使用独立单色轮廓；音频通过 [Media3 的 DefaultMediaNotificationProvider](https://developer.android.com/reference/androidx/media3/session/DefaultMediaNotificationProvider#setSmallIcon(int)) 设置小图标。音频通知使用独立 ID 8701，避免与下载前台通知的 1001 冲突。

## 素材维护

```sh
python3 scripts/generate_shiying_icons.py
```

脚本读取 `docs/design/shiying-icons` 中的 SVG，生成 64 个 drawable：58 枚功能/导航/通知图标、品牌小标、两个补充全屏图标及三个 launcher 分层资源。脚本仅依赖 Python 3.9+ 标准库；重复生成的内容一致。返回和前进图标开启自动镜像，其余媒体和状态图标保持方向。

## 验证结果

环境：arm64 模拟器 `emulator-5554`，API 37，1280×2856；Android Studio JBR；Debug ARM64 APK。

| 验证 | 结果 |
| --- | --- |
| Debug APK、设备测试 APK 构建 | 通过 |
| JVM 单元测试 | 189 项通过，0 失败、0 错误 |
| 首轮设备测试 | 18 项通过 |
| 补充通知校验及截图稳定性复查 | 7 项通过；与首轮有重复，合计 19 个不同用例 |
| Android 原生矢量渲染 | 61 个图标 × 4 个尺寸 × 2 种着色，共 488 次，均可见且着色正确 |
| 已存选中图标的透明镂空 | 三角内部透明，周围影像区域保持实心 |
| 自适应图标 | 前景、背景及 monochrome 资源加载成功，Launcher 应用抽屉中实测显示新品牌图标 |
| 完成、音频通知 | 实际发布的通知使用新 smallIcon 资源 |
| 资源重新生成及差异检查 | 内容稳定，`git diff --check` 通过 |

设备用例覆盖 `IconIntegrationDeviceTest`、`Material3NavigationDeviceTest`、`AppearanceModeDeviceTest`、`ResultSelectionDeviceTest`、`LibraryActionsDeviceTest` 和 `P3BackgroundAudioDeviceTest`。包含真实点击四个导航、浅深色切换、结果选择、长按菜单、后台音频、系统媒体控制和通知返回。通知校验临时授权模拟器 Debug App，完成后恢复原先未授权状态；测试通知被清除，偏好恢复。

截图和运行日志位于未跟踪的 `app/build/reports/icon-integration/`：八张真实浅深色页面截图、`launcher.png`、`app-icons-preview.png` 及测试日志。截图中的蓝色来自模拟器壁纸动态配色，不代表更改了暖橙品牌图标。

## 运行边界

本次未验证实体手机、Android 8–12 的实际安装或各厂商 Launcher 的主题图标开关。下载中的前台通知已替换资源并通过渲染检查，本轮未启动联网下载检查通知抽屉；完成及音频通知通过实际发布通知的资源校验。没有改动解析、下载、文件整理的数据逻辑，不据此宣称平台服务兼容性有变化。

保留了接入前的首页、批量导入、影像库及设备验收相关未提交改动；本轮没有执行 Git 提交或推送。


## 暖色主题更新（2026-09-30）

依据用户确认的展示页截图，默认主题改为固定暖色品牌配色，不再自动读取壁纸颜色。浅色使用奶白 `#FFF8F5`、深褐 `#33251F`、浅橙容器 `#FFE6D8`，小字与操作使用深橙 `#A64021` 保持对比度；暖橙 `#F2784B` 与杏橙 `#FFAD75` 渐变用于品牌标志。深色采用暖褐底与杏橙强调色，仍支持跟随系统、浅色、深色切换。导航使用实心选中形态及浅橙背景。影像库瀑布流与列表的多选标记移入封面左上角，改为白色圆环、暖橙圆形勾选；取消深色底板，保留 48dp 点击区域。

- Debug 和 AndroidTest APK 构建通过；190 项 JVM 测试通过，包括浅深色文字对比度和品牌色断言。
- 模拟器 4 项图标设备测试通过：固定品牌色、浅深色四页导航、488 次图标尺寸与着色渲染、完成通知；通知权限临时授予后已恢复。新增默认主题颜色断言，避免 Android 12+ 壁纸动态配色覆盖品牌色。
- 本次截图与日志保存在 `app/build/reports/warm-brand-theme/`，旧章节中的蓝色页面截图属于本次更新之前的记录。
- 圆形选择标记调整后，Debug / AndroidTest 构建及筛选删除、列表批量导出两项模拟器测试通过。
- 保留此前未提交的首页、批量导入、页面标题和影像库筛选布局修改。
