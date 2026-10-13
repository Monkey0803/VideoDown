# 通知内「继续」在服务退出后仍可用（2026-10-13）

`docs/TODO.md` 里「独立通知在后台服务退出后无法继续任务」一项的处置记录（Phase A 遗留边界）。

## 一、问题

A2 的每任务通知卡提供「暂停 / 继续」。但全部任务暂停后：

1. `DownloadService.observeProgress` 发现没有活跃传输 → `stopSelfResult` → 服务按既有设计退出；
2. `onDestroy` 里 `clearIndividualNotifications()` 把每任务卡一并撤掉；
3. 于是「从通知继续一个已暂停任务」只在**还有别的任务在传输**时可用。用户把队列全暂停、锁屏走开，
   回来时通知栏里已经没有任何入口，只能先打开应用。

## 二、方案选择

两条路：

- **(a) 为已暂停任务保活服务**：违背"没有传输就不前台"的既有取舍，还牵扯 Android 15 的前台服务时限与耗电，不做。
- **(b) 服务退出后保留暂停卡，点「继续」把服务重新拉起来**：改动集中在退出与点击两条路径，生命周期不变。**本轮采用 (b)。**

## 三、改动

`data/download/DownloadService.kt`：

- **退出时只保留「已暂停」的卡**（`retainPausedNotifications()` 取代 `clearIndividualNotifications()`）：
  - 状态取值与观察流一致（`progress[id]?.status ?: record?.status`）——**暂停的任务会从 progress 里被移走**，
    这一条是实测出来的坑：只看 progress 会把该留的卡当成"没有状态"撤掉；
  - 留下的卡换成**非 ongoing**（背后没有传输，必须能被划掉）且只带「继续」；
  - 标题仍走 `VaultPolicy.notificationTitle`，私密任务不会在锁屏上报出名字；
  - `onDestroy` 不做 I/O：记录来自观察流缓存的 `latestRecords`。
- **服务启动时先撤掉上一次留下的卡**（`clearStaleIndividualNotifications()`）：槽位是每次运行重新分配的
  （进程被杀后更从 0 开始），不清就会同一任务两张卡；撤掉后观察流按当前状态重贴一遍。
- **动作 PendingIntent 改用 `getForegroundService`**（每任务卡与汇总卡的「全部暂停」）：真实点击发生在
  应用处于后台时，`startService` 会被系统拒绝；前台服务启动则由"用户点了通知"获得豁免。

`data/download/IndividualTaskNotifications.kt`（新）：

- 卡片身份改为**固定通知 id（1100）+ 记录 id 当 tag**，于是任何一方都能只凭记录 id 撤卡，不必知道服务内部
  的槽位（槽位从此只用于给 PendingIntent 造互不相同的 request code）；
- `cancelAll(context)` 供服务启动时清遗留、以及**开关关掉时立即收回**；
- `cancel(context, recordId)` 供单任务撤卡。

`presentation/settings/SettingsScreen.kt`：把「独立任务通知」开关关掉时立刻 `cancelAll`——
服务可能早已退出，留下的暂停卡必须当场收掉，不能等下次服务启动。

## 四、验证

### 设备用例（模拟器 API 37，已为本应用开启通知权限）

新增 `TaskNotificationDeviceTest#aPausedCardOutlivesTheServiceExitAndResumesFromItsOwnAction`
（**连续 3 次运行 3/3 通过**）：

1. 一个真实慢传输 → 用卡片自己的「暂停」动作暂停它；
2. 等到**汇总通知（1001）消失**（服务确实退出了）；
3. 断言暂停卡还在、只带「继续」、`FLAG_ONGOING_EVENT` 为 0（可划掉）；
4. 用**卡片自己的「继续」动作**（真实 PendingIntent）恢复 → 断言任务回到运行/排队、汇总通知重新出现、
   卡片回到带「暂停」的运行态。

**红灯**：把 `onDestroy` 的保留换回原来的 `clearIndividualNotifications()`（只改这一处，用例不动），
该用例失败（等不到留下的卡）：
[TaskNotificationDeviceTest.before.xml](notification-resume-after-service-exit-2026-10-13/emulator-api37/TaskNotificationDeviceTest.before.xml)。
绿灯 XML：
[TaskNotificationDeviceTest.xml](notification-resume-after-service-exit-2026-10-13/emulator-api37/TaskNotificationDeviceTest.xml)。

证据文本是用例在断言那一刻从 `NotificationManager.activeNotifications` 抄下来的（截图拍不到通知栏；
API 37 的 `dumpsys notification --noredact` 也已不再逐条打印通知记录）：

```
# paused-card-after-service-exit.txt（服务退出后）
id=1100 tag=840dca36-071f-4d3e-a24f-0432a25187c8 ongoing=false title=暂停卡存活-763c02 actions=[继续]

# resumed-from-card.txt（点卡片自己的「继续」之后）
id=1001 tag=null ongoing=true title=正在下载视频 actions=[全部暂停]
id=1100 tag=840dca36-071f-4d3e-a24f-0432a25187c8 ongoing=true title=暂停卡存活-763c02 actions=[暂停]
```

第一份里**没有 id=1001**，正是"汇总通知随服务退出消失、暂停卡留下"；`ongoing=false` 说明它可划掉。
第二份里 1001 回来了（服务被拉起），卡片也回到带「暂停」的运行态。

### 顺带查清的一条既有失败

同类的 `independentCardsPauseOnlyTheirOwnTaskAndFollowThePreference` 在这台模拟器上**稳定失败**
（暂停两张卡中的一张后，等它的卡变成「继续」超时）。**把本轮两个产物文件回退到 HEAD 后同样 3/3 失败**
（同一处 15s 超时），所以与本轮无关；它此前在 API 30 真机上通过过（A2 那批记录）。本轮没有修它，
如实记在这里。为了让通知用例能跑起来，这台模拟器上给应用开了 `POST_NOTIFICATIONS` 与
`POST_NOTIFICATION` appop——之前全量里"通知必须真的开启"的几条失败属于环境。

### 回归

模拟器 API 37 全量 `connectedDebugAndroidTest`（冷启、解锁、`screen_off_timeout=30min`、已给应用开启通知）：
**199 条，27 条非通过 = 19 条按约定跳过的 opt-in + 8 条真实失败**：

| 失败 | 判定 |
| :--- | :--- |
| `TaskNotificationDeviceTest#independentCards...` | **既有问题**：回退本轮两个产物文件到 HEAD 后同样 3/3 失败（见上） |
| `P2CompletionNotificationDeviceTest` | 既有：API 37 模拟器上的老问题（`docs/TODO.md` 已记）；本轮模拟器开了通知后它仍超时 |
| `P2PhysicalBackgroundAudioDeviceTest` | 环境：需要 `manual_unlock` 人工解锁选项 |
| `P2ReleaseUiBridgeDeviceTest` ×3 | 环境：驱动 release UI，模拟器上没装 release 包 |
| `WeiboGifDeviceTest#selectingAnotherGif...` | 既有：已用 stash 基线证明与设备/环境相关（见 `dialog-ime` 那份记录） |
| `DownloadSettingsDeviceTest#enabledCompletionPreferencePostsIndependentNotification` | 冷启后的**首个应用启动还没拿到 `LocaleTestRunner` 固定的中文**（断言拿到 `Download complete`）；同一台机器紧接着单独重跑该类 6/6 通过 |

且这一轮**比上一轮（198 条 / 34 非通过）多过了 6 条**：`AutoGallery`、`DynamicColor`、
`EmptyStateIllustration` 这些此前因"模拟器没开通知/状态不同"而失败的条目在本轮全绿——本轮为跑通知用例
给应用开了通知权限。

> 另记一笔：第一次全量跑到 `AppearanceModeDeviceTest` 时模拟器的 NFC/UWB HAL 进入崩溃循环
> （系统日志一小时涨到 28MB），用例卡住不返回，被中止后冷启重跑才得到上面的结果。那是模拟器的事，
> 不是本轮改动。

## 五、边界（未覆盖，如实记录）

- **后台点击通知的豁免无法在用例里模拟**：真实点击由系统给"用户交互过通知"的临时豁免，才会允许启动前台
  服务；用例用 `PendingIntent.send()` 时应用仍在前台。用例证明的是"卡片自己的继续动作能把服务拉起来、
  任务真的继续"，后台豁免那一层由系统保证。
- **服务退出后若用户在应用里删除了那个已暂停任务，通知栏的卡会留到被划掉或被点一次**（点「继续」会拉起
  服务、发现记录已不在、随即撤掉卡）。没做"删除即撤卡"是因为卡片身份在服务侧，删除路径（引擎/ViewModel）
  没有服务上下文；这是本轮的有意取舍。
- **全部暂停就留几张卡**：开关本来就是按任务粒度开的，不做汇总卡。
- **只在手机形态验证**（模拟器 427×952dp）；平板/折叠屏的通知栏呈现未验证。
- 服务保活那条路 (a) 未实现，Android 15 前台服务时限与耗电问题原样保留。

## 六、证据清单

```
docs/validation/notification-resume-after-service-exit-2026-10-13/
└── emulator-api37/
    ├── TaskNotificationDeviceTest.xml        新用例 1/1 通过（既有那条见上）
    ├── TaskNotificationDeviceTest.before.xml 产物回退后的红灯
    ├── connectedAndroidTest.full.xml         全量回归（199 条 / 27 非通过）
    ├── paused-card-after-service-exit.txt    服务退出后：汇总通知没了，暂停卡还在（可划掉、只带继续）
    └── resumed-from-card.txt                 点卡片自己的「继续」之后：服务回来、卡片回到运行态
```

复现（通知需已授权，前台服务只能在应用处于前台时启动）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.TaskNotificationDeviceTest
```
