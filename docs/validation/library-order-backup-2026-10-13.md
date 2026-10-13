# 已存自定义顺序随设置备份走（2026-10-13）

`docs/TODO.md` 里「已存自定义顺序不随备份走」一项的修复与验证记录。

## 一、问题

「已存」列表的手动拖拽顺序存在记录上（`DownloadRecord.sortIndex`：> 0 表示被手动排过，值就是位置；
0 表示从没排过、按规则接在后面），而设置备份文件只装 `SettingsSnapshot`——**设置项**。
于是导出一份备份再恢复，排序**方式**（`librarySort`，属于设置）会回来，**排好的那一段顺序**丢了。

## 二、改动

`data/backup/SavedOrder.kt`（新，纯 Kotlin）定死三条语义：

- `of(records)`：只导出 `sortIndex > 0` 的记录、按位置升序；从没排过时返回 `null`——
  "没排过"不该被备份成一份顺序，否则恢复一次就把规则顺序固化成手动顺序了。
- `sanitize(raw)`：丢空串、按首次出现去重、截到 `MAX_ENTRIES`（5000）；清洗后为空返回 `null`。
  手改或外来的文件不能把一次恢复变成无界操作。
- `restorableOrder(records, backupOrder)`：备份顺序在前，**本机多出来的（仍带着手动位置）按原有相对
  顺序接在后面**，然后整段重新落位——既不会和残留的旧位置撞号，也不会把"备份之后才排过"的记录挤到
  别人前面。返回 `null` 表示"什么都不用做"：备份没带顺序、备份里的 id 一个都不认识（换机恢复）、
  或恢复后与当前顺序完全一致。

接线：

- `SettingsSnapshot` 加可空字段 `savedOrder: List<String>?`（旧文件/新文件都能读：字段可空 +
  `ignoreUnknownKeys`），`sanitized()` 里过一遍 `SavedOrder.sanitize`。
- `AppPreferences.snapshot(savedOrder = …)`：顺序不是偏好设置，由调用方读库后传进来。
- `presentation/settings/SettingsBackupFlow.kt`（新）：`encodeSettingsBackup` 与 `applySettingsBackup`
  把"设置 + 顺序"两个方向各封成一处，恢复时补一条 `SettingsChange.LIBRARY_ORDER`——
  否则一次「只改了顺序」的恢复会被报成"备份里的设置与当前一致"。
- `SettingsScreen` 的导出/恢复改调这两个函数；`Labels.kt` 与 `values/`、`values-en/` 各补一条文案
  （`settings_change_library_order`：已存顺序 / Saved order）。

**已知语义（写进了 README 的「已知限制」）**：id 是本机的，换机恢复时顺序保持原样；记录本身不随这份
备份走，这里也不假装能跨设备还原顺序。

## 三、验证

### JVM 单测

`./gradlew testDebugUnitTest` → **427 用例通过**（本轮 +10：`SavedOrderTest` 8 例 +
`SettingsBackupCodecTest` 2 例）。XML 见 [jvm/](library-order-backup-2026-10-13/jvm/)。

`SavedOrderTest` 覆盖：只导出排过的那段（位置有空洞也按位置升序）、没排过导出为空、清洗空串/重复/超长、
恢复后"备份之后才排过"的记录接在后面、不存在的 id 被丢掉、无变化返回 `null`、换机恢复返回 `null`，
以及一次**跨 store 的落盘往返**（编码 → 改顺序 → 解码 → 恢复 → 全新实例读盘仍是备份时的顺序）。

### 设备用例（模拟器 API 37）

`BackupDeviceTest` **4/4 通过**，新增
`aSettingsBackupBringsTheSavedOrderBackThroughARealDocument`：
三条真实记录 → 手动排成 third, first, second → **调设置界面用的同一个 `encodeSettingsBackup`** →
写进真实文档（SAF fixture provider）→ 备份之后再拖成 second, third, first →
读回文件 → **调设置界面用的同一个 `applySettingsBackup`** → 断言报出了 `LIBRARY_ORDER`
且这三条的相对顺序回到备份时的 third, first, second。
XML 见 [emulator-api37/BackupDeviceTest.xml](library-order-backup-2026-10-13/emulator-api37/BackupDeviceTest.xml)。

**红灯**：把 `SettingsBackupFlow` 两个函数回退成"只动偏好设置"（产物侧改动，用例不动），同一条用例失败：
[BackupDeviceTest.before.xml](library-order-backup-2026-10-13/emulator-api37/BackupDeviceTest.before.xml)
（`AssertionError: 顺序变了就必须报出这条改动，got []`）。

### 回归

模拟器 API 37 全量 `connectedDebugAndroidTest`（`ANDROID_SERIAL=emulator-5556`，解锁 +
`screen_off_timeout=30min`）：**197 条，34 条非通过**，其中 23 条是按约定跳过的 opt-in
（`AssumptionViolatedException`）；11 条真实失败与上一轮（弹窗页脚那批）逐条相同，都是环境或既有问题：
通知权限 4 条、需要人工解锁 1 条、release UI 3 条（模拟器上没装 release 包）、
`DynamicColor` / `EmptyStateIllustration` / `WeiboGif` 3 条（已用 stash 基线证明与本轮无关。
另需注意这三条在 `docs/validation/dialog-ime-2026-10-13.md` 里已单独记录过）。本轮新增的
`BackupDeviceTest` 与两条弹窗类都在这份全量里通过（197 = 上一轮 196 + 本轮新增 1 条；上一轮里
两条弹窗类曾因用例自身的键盘时序问题各挂一次，已修，见下）。全量 XML：
[connectedAndroidTest.full.xml](library-order-backup-2026-10-13/emulator-api37/connectedAndroidTest.full.xml)。

**顺带修掉的用例时序问题**：跑回归时 `P2LibraryOrganizationDeviceTest` 在模拟器横屏上复现出
「键盘只弹了一半时判据短暂成立、随后布局再变」的时序竞态，给两条弹窗用例加了 `ImeSettler`
（等键盘高度连续两次读数一致）稳定门槛，之后连续 4 次运行 4/4，全量套件里也通过。
细节记在 [弹窗页脚那份记录](dialog-ime-2026-10-13.md)。

## 四、边界（未覆盖，如实记录）

- **系统文档选择器那一段没被自动化**：设备用例直接调 `BackupDocuments` 读写 + 与界面同一个
  `encodeSettingsBackup` / `applySettingsBackup`，只有"点选择器选文件"这一小步靠人工——与本类既有
  用例的处理方式一致。
- **SAF 写入依赖 API 31+ 的 `MANAGE_DOCUMENTS` 身份**：本类用例只能在模拟器上跑（API 30 真机句柄
  权限被拒，`P2FileExportDeviceTest` 也是同样的既有边界）。真机侧只跑了设置页相关类，见下。
- **顺序的粒度**是"手动排过的那一段"：从没排过的记录不进备份，恢复后依旧按规则（更新时间）接在后面。
  如果用户期望"把看到的整个列表顺序都固定下来"，这需要产品确认，本轮按最小语义做。
- **平板 / 折叠屏未验证**（与上一项同样的边界）。

## 五、证据清单

```
docs/validation/library-order-backup-2026-10-13/
├── jvm/
│   ├── SavedOrderTest.xml           8 例
│   ├── SettingsBackupCodecTest.xml  12 例
│   └── summary.xml                  全量 427 通过 / 0 失败
└── emulator-api37/
    ├── BackupDeviceTest.xml         4/4（含新增的顺序往返）
    ├── BackupDeviceTest.before.xml  产物回退后的红灯
    └── connectedAndroidTest.full.xml 全量 197 条（34 条非通过：23 条 opt-in + 11 条环境/既有）
```

复现：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
# 设备（SAF 需要 API 31+，用模拟器）
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.BackupDeviceTest
```
