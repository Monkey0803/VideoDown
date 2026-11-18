# 批量链接导入用户体验验收记录（2026-11-18）

## 范围

- 「解析 → 批量导入多个链接」重构为全屏三段式：固定标题栏 / 可滚动列表 / 固定页脚。
- 输入区在解析后折叠成「N 条链接 + 编辑链接」，支持展开重解析（有勾选时先确认）。
- 结果卡改为「平台 + 域名 + 状态 / 封面 + 标题 + 作者与时长 / 画质」，原始 URL 只在失败行出现。
- 三态全选、已解析与已选数量、解析进度条、停止与继续解析；失败行单条重试。
- 解析期间已就绪行仍可勾选与挑画质；提交结果的提示改为固定通知条。
- 图文作品使用「找到 N 张图片」；单画质/单音轨不再渲染为只有一个选项的 chip。

实现约束未变：解析仍顺序执行、上限仍为 50 条、重复检查仍发生在提交前，不新增依赖与持久化字段。

## 自动化结果

### JVM 与 Android 测试编译

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
```

结果：通过。

- 新增 `BatchImportLayoutPolicyTest`（4 例）：三档阈值边界、FULL/COMPACT/MINIMAL 下的图标/副标题/状态条开关。
- `BatchImportTest` 新增 5 例：三态计数、行级勾选、解析期间仍可编辑勾选而输入被锁、单条重试只重跑该 URL、已就绪行不被重试。
- 既有 `LocalizationGuardTest`：中英键仍一一对应，中文字面量预算保持 0。
- 既有 7 例批量导入 JVM 用例全部通过（去重、停止、超限、提交失败保持勾选等）。

### 设备测试

环境：Pixel 10 Pro AVD，Android 17 / API 37。

```text
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.P2BatchImportDeviceTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.LauncherShortcutsDeviceTest
```

结果：`P2BatchImportDeviceTest` 10/10 通过，`LauncherShortcutsDeviceTest` 9/9 通过。

`P2BatchImportDeviceTest` 覆盖：

1. 多条网址去重、选 1080P 后只入队所选，失败行保留在列表。
2. 重复确认的「取消 / 跳过重复项 / 仍然全部下载」三条路径与逐媒体画质。
3. 横屏 + 软键盘下页脚完整可见；旋转回竖屏后选择仍在并能提交。
4. 50 条全部提交失败时页脚可达、失败项保持勾选。
5. **新增**：解析后输入区收起且结果态不出现裸 URL；「编辑链接」可展开、可收起。
6. **新增**：失败行「重试」只重跑该 URL，成功行已选画质不变。
7. **新增**：空剪贴板点「粘贴链接」出现提示。
8. **新增**：再次粘贴为追加，不吞掉上一批链接。
9. **新增**：图文作品显示「找到 1 张图片」，不出现「找到 1 个视频」。
10. **新增**：200% 字体下标题栏 ✕ 与「下载所选」仍可达。

`LauncherShortcutsDeviceTest` 的两处批量导入关闭动作改用 `batch-import-close` 测试锚点，其余快捷方式落地行为不变。

安装方式为覆盖升级（`android.injected.androidTest.leaveApksInstalledAfterRun=true`），没有卸载应用，登录态与下载历史保留。

## 视觉证据

目录：[`docs/validation/batch-import-ux-2026-11-18/`](batch-import-ux-2026-11-18/)

| 文件 | 内容 |
| :--- | :--- |
| `input-light.png` / `input-dark.png` | 输入态：图标标题、50 条说明、实时识别计数位、粘贴（次）与「开始批量解析」（主）。 |
| `results-light.png` / `results-dark.png` | 结果态：4 条链接（2 个视频、1 条失败、1 个图文作品），封面为空占位；状态条、折叠输入区、失败行重试与页脚主操作同屏。 |
| `device-light.png` / `device-dark.png` | 应用内通过「批量导入」快捷方式实际打开，验证全屏卡片、标题栏 ✕ 与系统栏区域的实际观感。 |

对照要点：主操作唯一（输入态解析、结果态下载所选）；结果卡不以网址开头；错误不只靠颜色；页脚不随列表滚动；图文与视频计数文案分开。

> 结果态截图由设备测试宿主注入确定性数据（假仓库、`coverUrl = null`），只用于布局与文案取证，不代表真实 CDN 封面加载效果。

## 未覆盖边界

- 真实封面加载未单独联调：结果卡复用结果页同一 Coil/AsyncImage 路径，但本轮取证里封面为空占位。
- 横屏 + 键盘只在 AVD 上自动验证；未覆盖真机 OEM 输入法、折叠屏与分屏。
- 200% 字体用 Compose 配置宿主验证，不等同 OEM 字体布局的人工走查。
- TalkBack 完整朗读未做；只覆盖关键控件的语义名称与 48dp 触控区域。
- 全屏弹窗在系统栏区域仍显示弹窗遮罩（浅色主题下是一条灰色带）。曾尝试把卡片底色铺到系统栏下方，但弹窗窗口无法稳定控制状态栏图标明暗，浅色下会出现浅色图标压浅色底，故回退为现有实现；如需页面级铺满，应把批量导入改成 `NavHost` 页面而不是继续加窗口特例。
- 失败行会根据解析错误的类型复用 `failureDescription`；只有 `UNKNOWN` 时不显示（避免出现「断点」这类下载语境），由「解析未完成，请重试」承担说明。解析专属的失败说明不在本轮范围。
- 未在 API 26/30 真机上验收。

> 后续：本页所用组件已进一步收敛，见 [组件与重复代码收敛验收记录](component-reuse-2026-11-18.md)。
