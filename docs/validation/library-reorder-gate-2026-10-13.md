# 把手隐藏条件补全覆盖（2026-10-13）

`docs/TODO.md` 里「把手隐藏条件只覆盖了『搜索』一种」一项的收口记录。

## 一、问题

已存列表的拖拽把手只在「自定义排序 + 没有任何搜索/过滤 + 非多选 + 非批量忙」时出现。
这条判定原本以 6 个内联条件写在 `LibraryScreen` 里：

```kotlin
val reorderAllowed = sort == CUSTOM && text.isBlank() && platform == null &&
    folder == null && !favoritesOnly && !vaultOnly
val canReorder = reorderAllowed && !selecting && !state.batchBusy
```

设备用例只有 `theHandleDisappearsWhileSearching` 一条，**搜索以外的 5 个条件没有用例**：
多选、批量忙、平台过滤、分类过滤、仅收藏、保险箱里任何一条将来被改漏，用户就会在「顺序其实由规则
决定」的列表上拖动，把手动顺序写坏。

## 二、改动

判定搬进 `presentation/library/LibraryQuery.kt`，成为可单测的纯逻辑：

- `LibraryQuery.ordersManually`（查询这一半）：只有一个档位由用户决定顺序——自定义排序 + 无关键词 +
  无平台 + 无分类 + 非仅收藏 + 非保险箱。注释里写清了"其余档位的先后是规则算出来的"。
- `LibraryQuery.canReorder(selecting, batchBusy)`：在 `ordersManually` 之上再排除多选与批量忙。
- `LibraryScreen`：把手/拖拽用 `canReorder`；「第一次切到自定义排序要不要按当前顺序 seed 一次」
  用 `ordersManually`（原来那句里多余的 `sort == CUSTOM` 也一并去掉，因为 `ordersManually` 已蕴含）。

## 三、验证

### JVM 单测（穷举六个条件）

`./gradlew testDebugUnitTest` → **433 用例通过**（本轮 +6：新增 `LibraryReorderGateTest`）。
XML 见 [jvm/](library-reorder-gate-2026-10-13/jvm/)。覆盖：

- 自定义排序 + 干净查询：开放；
- 三个规则档位（最新/最早/最大）各自关闭；
- 搜索关闭，但**只有空格的关键词不关闭**（与 `LibraryQuery.apply` 把空白当没搜的口径一致）；
- 平台 / 分类 / 仅收藏 / 保险箱四个过滤各自关闭；
- 多选、批量忙、两者叠加各自关闭；查询本身不允许时，多选与批量忙是 false 也没用；
- 一条**不变量**：`canReorder` 蕴含 `ordersManually`（否则会出现"能拖但从未 seed 过顺序"的列表），
  且多选/批量忙都为 false 时两个判定必须一致（seed 只看查询这一半）。

**这些用例真的有牙**：把 `!favoritesOnly` 从判定里删掉（模拟一次真实回归），
`each filter closes the gate` 立刻失败并指名「仅收藏」，见
[LibraryReorderGateTest.mutated.xml](library-reorder-gate-2026-10-13/jvm/LibraryReorderGateTest.mutated.xml)；
随后已还原。

### 设备用例（模拟器 API 37）

`LibraryReorderDeviceTest` **4/4 通过**，新增
`theHandleDisappearsWhileSelectingOrFiltering`：多选让两个把手消失、退出多选后回来；
再按「未分类」过滤（三条记录都属于未分类，列表仍是三条）把手再次消失，
两次操作都没有写坏落盘顺序。
XML 见 [emulator-api37/LibraryReorderDeviceTest.xml](library-reorder-gate-2026-10-13/emulator-api37/LibraryReorderDeviceTest.xml)。

### 回归

模拟器 API 37 全量 `connectedDebugAndroidTest`（`ANDROID_SERIAL=emulator-5556`，解锁 +
`screen_off_timeout=30min`）：**198 条，34 条非通过** = 23 条按约定跳过的 opt-in +
11 条环境/既有问题（通知权限 4、人工解锁 1、release UI 3、`DynamicColor`/`EmptyStateIllustration`/
`WeiboGif` 3，均与上一轮逐条相同）。本轮改动是等价重构 + 测试，用户可见行为不变；
`LibraryReorderDeviceTest` 在这份全量里同样 4/4。
XML 见 [emulator-api37/connectedAndroidTest.full.xml](library-reorder-gate-2026-10-13/emulator-api37/connectedAndroidTest.full.xml)。

## 四、边界（未覆盖，如实记录）

- **每个过滤分支只穷举在判定层**：设备上只演了「搜索」（原有）、「多选 + 分类过滤」（本轮新增）；
  平台/仅收藏/保险箱/批量忙四个分支没有端到端用例——它们与前者共用同一个判定，
  端到端再各演一遍只是重复同一段界面接线。若将来把手改成按分支各写一份判定，这里必须补设备用例。
- **批量忙的设备用例依赖真实批量任务**（`state.batchBusy`），造起来成本明显更高，本轮不加。
- **任务页（HistoryScreen）的把手判定没有动**：它是另一条判定（`TaskSort.STATUS` + 关键词 + 批量忙），
  已有 `TaskReorderDeviceTest` 覆盖，不在本轮范围内。
- 平板 / 折叠屏未验证（与前面几项同样的边界）。

## 五、证据清单

```
docs/validation/library-reorder-gate-2026-10-13/
├── jvm/
│   ├── LibraryReorderGateTest.xml          新增 6 例
│   ├── LibraryReorderGateTest.mutated.xml  注入回归后的红灯（删除 !favoritesOnly）
│   ├── LibraryQueryTest.xml                既有 9 例仍通过
│   └── summary.xml                         全量 433 通过 / 0 失败
└── emulator-api37/
    ├── LibraryReorderDeviceTest.xml        4/4（含新增的多选 + 分类过滤）
    └── connectedAndroidTest.full.xml       全量回归
```

复现：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests "com.videodown.douyin.presentation.library.*"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.LibraryReorderDeviceTest
```
