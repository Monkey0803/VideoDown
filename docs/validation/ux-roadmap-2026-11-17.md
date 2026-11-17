# 用户体验路线验收记录（2026-11-17）

## G1 首次使用与高质量空状态

- 复用已有三步说明与「粘贴并解析」入口，没有新增阻塞式启动弹窗。
- 新增「知道了」关闭操作；关闭状态写入既有 `shiying_preferences`，页面重建和应用升级后不重复出现。
- 输入框已有内容、存在剪贴板候选或已有保存内容时，继续由纯逻辑策略隐藏引导，避免与主任务竞争。

验证：

```text
./gradlew testDebugUnitTest --tests com.videodown.douyin.presentation.UiDensityPolicyTest \
  compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pabi=universal \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.FirstUseGuideDeviceTest
```

结果：Pixel 10 Pro AVD，Android 17 / API 37，通过。

未覆盖边界：尚未在全新安装的真实设备上人工评估引导文案与首次剪贴板系统提示的组合观感。

## G2 下载结果跨进程恢复

- 只持久化失败、暂停、完成任务的 id 集合，不复制标题、网址或文件路径。
- 首次磁盘装载只恢复已经落盘且仍匹配当前终态的 id；普通历史记录不会变成新结果。
- 任务恢复运行、被删除或状态变化时自动清理旧结果；进入任务/已存目标页或关闭结果条后同步清空持久状态。
- 增加 `HistoryUiState.hydrated`，空历史也能明确完成基线装载，不会吞掉第一条新任务。

验证：`DownloadResultInboxTest` 与 `DownloadResultRestoreDeviceTest` 通过；设备环境同 G1。

## G3 异常恢复主操作统一

- 失败任务卡显示原因说明和一个带文字的推荐主操作。
- 次要恢复动作移入具名的「更多任务操作」，不再与主操作并排竞争。
- 无来源网页时继续禁用重新解析/网页恢复，并保留明确说明。

验证：`DownloadFailureGuidanceTest` 与 `P1RecoveryNavigationDeviceTest`（6 条）通过；设备环境同 G1。

## G4 权限与运行环境就地说明

- 通知与 Android 8/9 存储权限继续只在用户启用通知、保存到相册或截图时申请，不新增启动权限请求。
- 后台服务失败保留「重试」作为唯一主操作，并在更多操作中提供「打开系统设置」。
- 画中画进入失败后显示带「前往设置」操作的 Snackbar；优先进入应用画中画设置，系统不支持时回退到应用详情。

验证：`DownloadFailureGuidanceTest` 与 `P1RecoveryNavigationDeviceTest`（6 条）通过；Pixel 10 Pro AVD，Android 17 / API 37。设备测试验证设置入口只在对应失败场景出现，没有实际修改系统设置。

## G5 无障碍与大屏模拟器验收

已完成的自动回归：

- `DownloadCenterSettingsDeviceTest`：注入 200% 字体后设置主入口仍可滚动到达；1200dp 宿主切换到 Navigation Rail。
- `AdaptiveContentDeviceTest`：宽窗口内容仍受最大宽度约束。
- `HomeKeyboardDeviceTest`：真实软键盘弹出时输入与提交入口可达，完成键可正确收起键盘。

结果：以上三类在 Pixel 10 Pro AVD、Android 17 / API 37 通过。

人工模拟器验收：

- **TalkBack**：实际绑定 Android Accessibility Suite 的 `TalkBackService`，使用触摸探索选中和双击激活，依次进入解析、任务、已存、设置；四页均能正确聚焦和跳转，无崩溃或系统弹窗阻断。
- **系统 200% 字体**：通过系统 `font_scale=2.0` 后冷启动，不使用 Compose 注入；四个主页面均可进入，设置首页可滚动到底部并打开「关于与更新」，未发现文字覆盖、越界或主要操作不可达。
- **平板窗口**：把 AVD 切换为 2560×1600 / 320 dpi（1280×800dp），实际显示 Navigation Rail 并走通四页；内容宽度、卡片和设置入口排布正常。
- **折叠屏尺寸变化**：分别使用 2208×1840 / 420 dpi 的展开尺寸和 1080×2092 / 420 dpi 的外屏尺寸冷启动，验证 Navigation Rail 与底部导航切换，并走通四页；未发现裁切、重叠或入口不可达。
- **真实分屏**：通过系统最近任务的「分屏」把当前 Debug 构建与 Chrome 配对；`dumpsys activity` 确认 `com.videodown.douyin.debug` 处于 `mode=multi-window` 且 `visible=true`，分屏内四页均可切换。

证据：本轮截图和 UI hierarchy 保存在 `/tmp/videodown-talkback-*`、`/tmp/videodown-font200-*`、`/tmp/videodown-tablet-*`、`/tmp/videodown-fold-*` 与 `/tmp/videodown-debug-split-*`。模拟器结束后已恢复 1280×2856 / 480 dpi、字体倍率 1.0、TalkBack 关闭和单窗口状态。

未覆盖边界：当前 AVD 只提供 `DEFAULT` device state，因此折叠屏验证覆盖展开/外屏窗口尺寸变化，不覆盖铰链角度与半折叠姿态；物理设备、OEM 差异仍未覆盖，但不阻塞 G5。按用户要求，不验证外接键盘按键和键盘焦点顺序。

## G6 登录态表达与重新登录

- 「网站与账号」页把 X 登录态明确分成检查中、已登录和未登录，并说明它只适用于 X 解析。
- 登录页和设置页均说明 Cookie 只保存在拾影内，不读取 Chrome 等外部浏览器账号。
- 清除入口明确说明会清除拾影内所有网站的网页登录态，但不影响外部浏览器；原有二次确认继续展示受影响任务数。
- X 明确返回需要登录且本机已有 Cookie 时，解析页优先显示「重新登录 X 后重试」；没有 Cookie 时显示「登录 X 后重试」。
- X 返回访问受限时，入口使用「检查登录与访问权限」，不把地址失效、Cookie 过期和账号无权访问误判成其中任一种。

验证：

```text
./gradlew testDebugUnitTest --tests \
  com.videodown.douyin.presentation.login.XLoginRecoveryPolicyTest \
  compileDebugAndroidTestKotlin

./gradlew connectedDebugAndroidTest -Pabi=universal \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XLoginSessionDeviceTest
```

结果：JVM 策略测试通过；Pixel 10 Pro AVD、Android 17 / API 37 上 7 条离线设备用例通过。设备用例通过系统 `CookieManager` 注入测试 Cookie，并使用 `about:blank` 代替真实登录页，没有访问真实 X 账号或下载受保护内容。

未覆盖边界：没有使用真实 X 账号验证登录页、Cookie 过期和受保护推文；App 只能检测关键 Cookie 是否存在，最终有效期和内容访问权限仍由 X 服务端决定，因此界面不会声称已确认账号有效。
