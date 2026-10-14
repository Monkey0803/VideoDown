# 拾影（Keepsake）— 视频下载器

使用 Kotlin + Jetpack Compose 实现的 Android 应用，支持解析抖音、微博、快手、X（Twitter）分享链接及通用 HTTP/HTTPS 视频网址，选择分辨率下载视频、
记录下载历史、打开原视频与作者主页并播放已下载的视频。界面使用 Compose Material 3，在「我的 → 外观模式」中可选择跟随系统、浅色或深色，确认后立即生效并在重启后保留；Android 12 及以上还可在「我的 → 动态取色（Material You）」改用系统壁纸配色。默认采用奶白、暖橙与深褐品牌主题，深色模式使用暖褐背景与杏橙强调色。颜色、文字样式与圆角统一由 `MaterialTheme` 管理。

主导航分为「解析」「任务」「已存」「我的」：解析页支持粘贴分享文案和选择画质；任务页显示实时进度及暂停、续传、删除操作；已存页展示文件仍存在的已完成视频；「我的」可设置默认画质、仅在 Wi-Fi 下下载、完成时通知、每条任务独立通知、识别剪贴板链接与动态取色，偏好在重启后保留。

图标采用「拾影框」品牌标志及统一的圆角线性图标：桌面使用暖橙渐变，自适应图标提供独立单色主题版；应用内导航、解析、任务、影像库、播放和设置图标随浅深色品牌主题着色。播放、继续下载、收藏、保存到相册及导出文件夹使用不同语义的图标。设计源文件与展示页见 [图标设计](docs/design/shiying-icons/README.md)，接入及模拟器验证见 [图标接入记录](docs/validation/icon-integration-2026-09-30.md)。修改 SVG 后可运行 `python3 scripts/generate_shiying_icons.py` 重新生成 Android VectorDrawable。

开启「仅在 Wi-Fi 下下载」后，非 Wi-Fi 网络下的新建、继续和重试任务会暂停；下载期间离开 Wi-Fi 或在移动网络上开启该限制，也会停止传输并保留断点。连接 Wi-Fi 后需点击「继续」。开启「完成时通知」会按需申请通知权限；拒绝授权或关闭系统通知渠道时，页面会提示前往系统设置。每次成功完成下载后发送独立通知，点击打开对应视频或音频的详情；关闭该偏好不影响下载期间的前台服务进度通知。

下载在前台服务确认启动后才开始传输。服务启动被系统拒绝、启动超时或运行中意外停止时，任务页显示「后台下载已停止」和具体原因，保留断点供返回应用后重试；正常完成或全部暂停后退出服务。系统到达后台下载时限时也停止服务并保留任务。前台服务不保证应用被强制停止或系统杀进程后继续运行。

## 重复提醒、批量操作与搜索

- 下载前按平台、媒体 ID 和所选画质/格式检查历史。已有任务或已保存文件时显示提醒，可查看已有视频/任务，或明确选择「仍然下载」。文件已丢失的完成记录不会阻止重新下载，不同画质可分别保存。
- 多视频解析结果顶部显示当前视频和已选数量；点击「查看全部」打开自适应网格。点击封面切换预览，勾选框独立控制批量选择，支持全选/取消全选。默认不勾选，底部按钮保存当前视频；勾选后按各视频保留的画质保存所选项目。存在重复项时，可跳过重复项并下载其余视频，或仍然下载全部所选视频。封面高度随窗口调整，长标题默认两行，可展开查看全文。验证截图见 [结果页布局验证](docs/validation/result-selection-2026-09-30.md)。
- 任务页支持多选暂停、继续、删除；全部暂停与全部继续可分别使用。批量控制只处理符合当前状态的项目，批量删除先确认。
- 已存页可按标题/作者搜索、按平台筛选，并按最新保存、最早保存、文件大小排序。两种布局均支持多选删除和保存到相册。「全选当前结果」仅选择当前可见的筛选结果，改变筛选条件会清除选择。
- 批量操作逐项执行并显示进度和成功、跳过、失败数量。单项失败不会中断其余项目；可查看具体失败视频和原因，保存/删除完成后仅保留失败项选中，便于重试；删除只影响本地下载及记录，不删除相册副本。Android 8/9 保存到相册仍需存储权限。

## 存储、队列与失败恢复

- 「我的 → 存储空间」显示下载视频、临时文件、可用空间和可清理残留。清理需要确认，并在执行前重新检查文件指纹和任务引用；保留有效视频、相册副本，以及暂停、失败或进行中任务引用的断点和合并片段。清理对象仅限未引用的下载临时文件、引擎任务目录和文件已丢失的完成记录；已存视频请在「已存」中选择删除。
- 存储空间同时可视化运行任务的空间预算：显示尚需写入的总量（含每任务 32 MiB 工作余量和引擎合并的第二份空间）、占用比例条，以及按剩余字节排序的任务明细；预留超过当前可用空间时直接给出缺口和「清理残留或删除已存视频」的处理建议。已写入字节由可用空间反映，不重复计入预算。
- 运行中的下载共享空间预留账本：任务取得队列名额后，原子预留未来仍需写入的空间，每个任务保留 32 MiB 工作余量；已知大小的引擎任务按下载与合并两份空间估算。已经写入的文件占用由实际可用空间反映，不重复预留；暂停、失败、取消和完成后释放预留，等待中的任务不占空间预算。
- 直链收到响应后按实际大小调整预算，每次写入前重新检查；未知大小按已写入量滚动检查。引擎下载、合并、音频提取和校验期间根据任务目录实际占用持续检查；空间不足保留断点并提示清理后继续。此机制协调本应用的并发任务，无法保证其他应用突然大量占用磁盘或引擎未知大小输出不会触发实际磁盘不足。
- 「我的 → 同时下载数量」可设置 1–5 个任务，默认 3，重启后保留。提高数量立即开放名额；降低数量时正在下载、合并或校验的任务继续，新任务等待名额。
- 等待任务显示排队位置，并支持前移、后移和优先下载。仅调整等待任务，顺序保存到历史；重新开始或单独继续的任务加入队尾。进程退出后任务恢复为暂停，点击「全部继续」按保存顺序加入队列。
- 「我的 → 自动继续下载」默认开启，用于网络和空间原因造成的中断：离开 Wi-Fi、连接提前中断或磁盘暂满而暂停、失败的任务，在网络恢复或空间充足后自动继续，进程退出遗留的任务在下次启动自动继续。用户主动暂停的任务永不自动继续；关闭开关后全部中断都需手动点击继续。自动重试有次数上限；条件尚未消除时只等待、不计次。
- 失败提示区分网络、登录/验证、访问受限、链接过期、存储空间、文件写入、画质变化、网站限流和引擎故障。按原因提供重试、重新解析原网址、打开网页登录、管理空间或检查下载设置。HTTP 403 保留“过期或访问受限”的含义，避免直接当作需要登录。
- 网页辅助与重新解析使用原始页面网址；旧记录缺少原网址时明确提示重新粘贴，并禁用对应操作。DRM 和直播显示不支持，不提供反复重试入口。

详细验证和运行边界见 [P1 验证记录](docs/validation/p1-features-2026-09-30.md)。

## 批量链接、音频与文件整理（P2）

- 「解析 → 批量导入多个链接」接受分享文案或多条网址，去重后逐条解析，最多 50 条。每个页面中的媒体可以独立选择画质或音轨；支持全选、停止、重试未完成项，部分失败不会丢失成功结果。加入队列前再次检查重复，可跳过已有项或明确选择仍然下载。
- 通用引擎报告独立音轨时，解析结果显示「单独保存音轨」，可单独下载为 M4A。未提供独立音轨时，可以先保存视频，再在影像库长按选择「提取音频」。本地提取不需要联网；AAC 优先保留原编码采样，其他编码转 AAC，保留原视频。音频任务沿用队列及暂停、继续、重试、取消；无音轨的视频显示明确原因。
- 音频可在应用内播放、通过系统分享、保存到系统音乐目录或自定义文件夹。音频不会进入视频相册；后台播放能力见下方 P3。
- 影像库长按可收藏、实际重命名本地文件并设置分类；支持收藏及分类筛选。分类为库内标签，不移动文件。重命名保留扩展名，写入失败时回滚，并在启动时恢复中断的重命名事务。
- 单个文件和多选文件均可「导出到文件夹」，使用 Android 系统目录选择器取得并保留授权，记住上次目录。同名副本增加序号，保留原文件；逐项显示结果，失败项可以单独重试，停止时清理当前未完成副本。
- 文件夹不可写或授权失效时，清除失效目标并提示重新选择；空间不足时提示释放空间或更换目录，可在清理空间后只重试失败项。本地原文件不修改。
- 导出界面旋转保留本次任务；进程重建后提示上次中断并尝试清理未完成副本。无法访问的旧目录保留待清理记录，不阻止导出到新目录；整批导出不承诺在进程退出后自动继续。

验证记录见 [P2 验证记录](docs/validation/p2-features-2026-09-30.md)。

## 续播、播放设置与后台播放（P3）

- 视频和音频记住播放位置，再次打开继续播放；播放结束后从头开始。进度按文件大小和修改时间校验，更换文件后不沿用旧进度。
- 播放页可选择 0.5×、0.75×、1×、1.25×、1.5×、1.75×、2×、2.5×、3×、4×，并开启单曲循环；音频与视频分别记住设置。横屏倍速列表可滚动。
- 音频由媒体前台服务播放，返回其它页面、Home 或锁屏时仍继续。系统媒体通知显示标题和播放控制，点击返回当前音频；通过通知暂停后返回页面保持暂停和进度。支持系统媒体键、耳机播放键和音频焦点处理，拔下耳机时暂停。打开视频会暂停后台音频。
- 视频播放页提供「画中画」；支持的设备在播放中按 Home 自动进入小窗，暂停时不自动进入。小窗隐藏应用内操作，系统小窗可暂停、继续、展开及关闭。返回保持播放器和进度，关闭释放视频播放器。普通后台视频暂停。
- 后台服务不保证强制停止应用后继续播放；进度和音频/视频偏好保存在本地，重新启动后可继续。

实现、验证证据和真机边界见 [P3 验证记录](docs/validation/p3-features-2026-09-30.md)。

## 入口效率与任务管理（P4）

- 复制受支持平台的分享链接后回到应用，解析页出现「检测到 X 链接」横幅，点击「解析」才会解析；点「忽略」后同一链接不再提示。读取剪贴板要求窗口已获得焦点，且「我的 → 识别剪贴板链接」关闭后完全不读取。只识别抖音、微博、快手、X，普通网页链接不会触发提示。
- 长按桌面图标可用「粘贴解析」「批量导入」「下载任务」三个快捷方式；「粘贴解析」直接读取剪贴板并解析，不再显示确认横幅。
- 「我的 → 每条任务独立通知」默认关闭。开启后每个任务单独显示进度通知，可逐条暂停或继续，汇总通知同时保留。通知内不提供删除：删除不可逆且需要二次确认，只能在应用内完成。全部任务暂停后后台服务退出，独立通知随之清除。
- 任务页可搜索标题或作者（多个词按「且」匹配），并可按按状态、最新任务、最早任务、文件最大排序；传输中显示预计剩余时间，无法估算时不显示。
- 「我的 → 动态取色（Material You）」在 Android 12 及以上可见，默认关闭以保留暖橙品牌色。

验证记录见 [P4 验证记录](docs/validation/p4-features-2026-10-09.md)。

## 播放手势、字幕与下载节奏（P5）

- 视频播放页在**系统播放控件隐藏时**接管触摸：左右半屏双击快退 / 快进 10 秒，竖向滑动左半屏调亮度、右半屏调音量，长按临时 2× 加速（松手恢复）。控件显示时手势层完全不生效，倍速、循环、进度条与全屏按钮保持原有行为；离开页面时把屏幕亮度交还系统。
- 与视频同名的 `.srt` / `.vtt` / `.ass` / `.ssa` 字幕文件会作为独立字幕轨道加载，视频内嵌字幕同样渲染。存在字幕轨道时播放页出现「字幕」开关，选择会保留。只有真的存在字幕轨道时才显示该开关。
- 「我的 → 下载限速」限制**所有直链下载的总速度**（不限速 / 512 KB/s / 1 MB/s / 2 MB/s / 5 MB/s）。任务卡可为单个任务设置上限，或选择「跟随全局」；卡片上显示当前上限。限速在**每次写入前**生效且位于空间检查之后，因此磁盘写满仍然立即失败。**分片引擎下载（yt-dlp / FFmpeg）由独立进程完成，不受该限制。**
- 「我的 → 下载后自动存入相册」开启后，完成的视频复制到 `Movies/拾影`、音频复制到 `Music/拾影`；完成通知会说明是否已存入。Android 9 及以下需要存储权限，未授权时不复制并在通知中说明原因；保存失败不影响下载本身。
- 「已存」卡片与首页「最近保存」显示播放进度（进度条 + 「已看 N%」）。只有进度与当前文件的大小和修改时间都一致时才显示，重新下载或转码后不会沿用旧进度。
- 音频播放页可设「15 / 30 / 60 分钟后暂停」，按钮显示剩余时间。计时按绝对时间保存在本地，关闭页面后仍在后台生效；到点暂停播放并清除定时。

验证记录见 [P5 验证记录](docs/validation/p5-features-2026-10-09.md)。

## 合集解析（C3）

- 「我的 → 解析合集」打开后，粘贴**合集 / 频道 / 播放列表**链接会把整页条目都解析出来（默认关闭，最多 20 条），随后走既有的多选界面：逐条挑画质、批量入队，重复链接沿用既有重复检查。关闭时只解析链接指向的那一个视频——一个单视频链接不该悄悄变成二十条队列。
- 每个条目保留自己的 id 与来源网址，因此「下载第 2 条」下的就是第 2 条：已用本地两视频页面验证两条各自下到 10542 与 56341 字节、来源网址各自对应 `/one.mp4` / `/two.mp4`。
- 引擎侧只改了一处：`noplaylist` 现在由「X 帖子」或「应用显式要求合集」决定，`playlistend` 取自应用的 `playlist_limit`，上限与设置页文案共用 `EngineRequest.PLAYLIST_LIMIT`。

## 字幕下载与内嵌（C2）

- 解析结果里带字幕时，结果页会出现「同时保存字幕（中文）」的选项（只在该源真的有字幕、且选的是视频档位时出现）。打开后下载会把字幕**内嵌进视频文件**，离线播放也能用播放页的字幕开关打开它。
- 语言是应用挑的，不是逐条勾选：优先一个中文轨 + 一个英文轨（`zh-Hans` 优先于 `zh`），都没有时取源提供的第一个；**只有**在没有任何真实字幕轨时才使用自动字幕（机器生成的）。来源可能提供几十上百种语言，全量抓取会把一次视频下载变成字幕仓库。
- 字幕是引擎能力，所以请求字幕的任务会**改走引擎下载**，即使该档位本身是直链；否则会出现「下载成功但文件里没有字幕」的静默结果。
- 内嵌后字幕文件会被删除（一个任务只产出一个文件）；mp4 中的字幕轨以 `text/3gpp-tt`（mov_text）形式存在。

## 私有保险箱与应用锁（C4）

- 「已存」最左侧的「保险箱锁」是入口。点击后由**系统弹窗**验证身份：Android 11 及以上可要求生物识别或锁屏凭据，Android 10 以生物识别为主、可用锁屏凭据兜底，Android 9 及以下没有可用的系统弹窗（保险箱报告不可用，不会假装已锁）。验证通过才进入，按「锁定」立刻回到门外（不需要再次验证）。
- 保险箱是**独立视图**：普通列表永远不混入私密条目，解锁后也只显示私密条目——数量、分类下拉与批量操作全部只针对可见条目计算。
- 私密条目在下列位置一律不出现，或只显示占位文案「保险箱内容」：「已存」列表、首页「最近保存」、任务列表的标题、下载完成通知与单任务通知的标题。完成通知也不再深链到播放页（只打开应用），否则点一下通知就绕过了门禁。
- 「移入保险箱 / 移出保险箱」在条目的操作面板里；移入时**不移动也不加密**文件，只改变它出现在哪里。
- 自动存入相册会跳过私密条目（在保险箱内手动保存仍然可以）；导出链接清单也不会写出私密条目的链接。

## 语言与文案（C1，进行中）

- 应用**跟随系统语言**：`values/` 是简体中文，`values-en/` 是英文，`res/xml/locales_config.xml` 声明支持的语言，清单里用 `android:localeConfig` 暴露给系统。
- **英文名是 Keepsake**（中文名仍是「拾影」）：`values-en/` 的 `app_name` 即桌面与解析页标题；英文文案里的品牌名、备份导出文件名（`keepsake-links.txt` / `keepsake-settings-backup.json`）也一并用它。`shiying_preferences`（偏好文件名）、`shiying://`（通知深链 scheme）、`Movies/拾影`（MediaStore 目录）与 `ic_shiying_*`（图标资源）是**数据与资源标识**，不随品牌名改动。
- 「我的 → 外观 → 语言」可在应用内切换：Android 13 及以上写进系统的「按应用语言」（`LocaleManager`），应用自己不存语言偏好，所以这一行与系统设置里的「应用语言」永远是同一个值，切换后系统重建 Activity、界面语言当场跟着变；Android 12 及以下没有这个能力，弹窗只解释并给出前往系统设置的入口。
- 此前 `MainActivity` 会强制覆盖成简体中文，现已移除。
- **文案一律进 `res/values/strings.xml`，代码里不留中文字面量。** 格式串用 `%1$s` 占位；注意 `ifBlank { }` 这类非 `@Composable` 的 lambda 里不能直接调用 `stringResource`，要先取值再传入。
- `LocalizationGuardTest` 守着这次迁移：两个语言文件必须 key 一一对应，且 `src/main/java` 下的中文**字面量只许减少**（842 → **0**：未标注理由的中文串已清零）。
- **ViewModel 不出文案。** `HistoryViewModel` 曾经把「操作名」既当显示文案又当数据键（`operation == "删除"`），现已改为只传 `BatchOperation` / `HistoryNotice` 这类键与数字，句子由界面层用 `stringResource` 拼——这样 ViewModel 里就不需要再有任何用户可见文案。
- **domain 不持有文案。** `domain/model/DownloadFailure.kt` 只保留失败分类与「可采取哪些恢复动作」，标题/说明/按钮文字全部由 `presentation/FailureText.kt` 映射到资源；测试改为断言**每种 kind 都有标题与说明**（新分类不可能漏翻译）。
- **枚举只带值，文案在表现层。** `ThemeMode`/`LibraryLayout`/`PreferredQuality`/`TaskSort`/`LibrarySort` 的 `label` 属性已删除，改由 `presentation/Labels.kt` 映射；`AppPreferences.apply()` 也改为返回 `SettingsChange` 键列表，由设置页拼出「已恢复：…」的句子。
- **错误按状态判断，不按文案判断。** `AppError.Http(code)` 取代了「服务器返回 HTTP 403」这种字符串，下载引擎的重取/重试逻辑改为看状态码——以前是 `message.contains("HTTP 403")`，改一句提示就会悄悄改变重试行为。解析页的失败也改成 `HomeError` 键（`FromApp`/`EmptyInput`/`NoVariant`/`QueueFailed`），由 `presentation/ErrorText.kt` 统一出文案。
- **钥匙串（keystore）提示、分享面板标题、通用兜底错误**都已进资源；`toAppError()` 也改为返回带 `reasonKey` 的错误（`generic:network` / `generic:unknown`），所以「不要把系统英文消息当提示」这条约束现在由**类型**保证而不是靠拼字符串。
- **域层用 `null` 表示「不显示」，界面补文案。** `VaultPolicy.title(...)` 对锁定记录返回 `null`（而不是「保险箱内容」），通知与列表各自补上本地化文案；`SleepTimer.remainingRounded` 返回 `Minutes`/`Seconds`，`PlayerGestures.seekLabelSeconds` 只返回 `-10`/`+10`（符号与数字是数据，单位是文案）。
- **连诊断与兜底文案也进资源。** `core/AppText.kt` 是应用级文本解析器（`VideoDownApp` 启动时安装），给没有 Context 的数据层类提供 `require` 消息、诊断异常、日志与兜底标题的文案；JVM 套件通过 `TestText` 装上「id → 资源名」的测试缝，于是断言的是**用了哪条文案**而不是译文。只剩 **2 处**不可本地化：MediaStore 目录名（是路径）与清单格式标识行（要被再次解析）。守卫测试只统计**未标注**的中文串，当前为 0。
- **key 只有一个解析入口。** `core/reasonKeyRes(key)` 覆盖全部 key 家族（`engine:` / `download:` / `service:` / `vault:` / `gallery:` / `x:` / `platform:` / `input:` / `update:` / `generic:`），`storedErrorMessage` 就是一次查表——**任何存进记录的 key 都不可能被原样显示**（这条规则在第二十批修掉一个「engine:LIVE 可能泄漏到提示」的隐患后固化下来）。
- **版本号也是「结构 + 文案」。** `AppVersion` / `AppUpdateManifest` 不再自带 `label`；更新对话框用 `versionText`（清单里的版本名）与 `appVersionText`（本机版本，含内部版本号）分别成句——**两者的措辞本来就不一样**，合并成一个会改掉界面文字（设备测试 `AppUpdateEntryDeviceTest` 抓到过这一点）。
- **句子的参数也是结构。** `AppError.Parse` 除了 `reasonKey`，还能带 `platform`（平台名）与 `detail`（数据值，例如抖音作品 ID）；`reasonKeyNeedsPlatform` / `reasonKeyNeedsDetail` 显式声明哪些文案需要参数，不靠猜 `%s` 的个数。批量导入的状态同样只报结构（`ImportNotice`、`ImportRowError`），由对话框成句。
- **通知与导航栏也走资源。** 底部四个标签（解析/任务/已存/我的）、画中画的两个动作、完播通知卡片（「已存入相册 · 点击查看」）都由资源提供；`SubtitleSelection` 只说选中的语言与是否自动字幕，名字由 `subtitleSelectionText` 给；`GalleryAutoSave.Failed` 与 `VaultPolicy.autoGalleryReasonKey` 都返回 key，通知负责成句。
- **时间与进度也是「结构 + 文案」。** `core/Formatters.kt` 的 `remainingTime()` / `relativeTime()` 返回 `RemainingTime`/`RelativeTime`（小时分、分秒、秒、刚刚、N 分钟前…），`WatchBadge` 只带 `fraction`；句子在 `presentation/TimeText.kt`。domain 里的 `WatchProgress.label()`（「已看 25%」）已删除。
- **平台名是参数，不是拼进句子的字面量。** `VideoPlatform` 去掉了 `label`（只留 `referer`），名字由 `videoPlatformLabelRes` 提供；「抖音访问失败…」这类句子改成 key + 平台字段，资源写成 `%1$s访问失败…`，并显式声明哪些 key 需要平台参数（`reasonKeyNeedsPlatform`），不靠猜参数个数。
- **导出的结果报数字和原因，不报句子。** `FileExportViewModel` 原本在后台线程里拼「已导出 3 项，1 项失败；…」整句话；现在返回 `FileExportResult(succeeded, failed, reasons, cleanup, cancelled)`，由 `ExportText.kt` 出文案——新增一种失败原因必须同时给出文案，否则 `exportReasonRes` 编译不过。
- **失败要有类型，不能靠比对文案。** 相册保存失败原本是把消息文本和 6 条中文字符串比对来区分，改一句提示就会归类错误；现在是 `GalleryExportException(GalleryExportFailure)`，`galleryFailureKindOf(error)` 负责把外部异常（如 `SecurityException`）归到一个类别。批次结果里的失败也改成存 key（`BatchFailure.galleryFailureKey`），由界面出文案。X 的解析失败同理：`XParseResult.Failure(XParseFailure)` 取代 `Failure("中文句子")`，请求本身失败则复用 `AppError.Http(status)`（**HTTP 状态就是 HTTP 状态**，不再有「X 访问失败（HTTP 403）」这类专有文案）。`AppError.Parse.reasonKey` 统一承载 `engine:` / `x:` 两类 key。
- **持久化的错误存 key，不存句子。** 任务记录会活过一次语言切换，所以引擎失败存成 `engine:<kind>`、后台服务失败存成 `service:<kind>`（`core/ErrorResources.kt`、`core/ServiceFailure.kt`），界面用 `storedErrorMessage(...)` 解析；**旧版本写进历史的中文消息**没有前缀，仍按原文显示并继续被 `legacyFailureKind` 识别。`VideoEngineException` 现在只带 `kind`，`DownloadServiceException` 只带 `ServiceFailure`。
- **数据不是文案。** `legacyFailureKind` 里的中文是用来**匹配用户设备上已存的历史消息**的，改写就会失效。守卫测试支持按函数名豁免（`DATA_FUNCTIONS`），豁免必须写明理由，不允许用它来放过界面文案。
- **迁移已完成**：所有用户可见文案来自 `values/` 与 `values-en/`（各 626 条 key）。`domain`/`core`/`data` 不持有界面文案，只返回值、枚举或 key，由 `core/reasonKeyRes(key)` 单点解析；其余 116 处中文字面量是内部不变量、开发者日志、开发者诊断异常与持久化内容，逐条用 `// l10n-ignore: <理由>` 登记（守卫计数为 0）。

## 通用网址与网页辅助

粘贴 HTTP/HTTPS 网址或分享文案即可尝试解析。已知平台优先使用原有解析器，失败后进入 yt-dlp；X 推文即使原生解析成功，也会补充通用引擎找到的媒体和画质。自动解析失败时点击「打开网页查找视频」，在内置网页中登录或播放，再选择检测到的媒体资源，也可使用当前网页登录态重新解析。

MP4/WebM 直链继续断点下载；HLS、DASH 和分离音视频通过本地 Python、yt-dlp 与 FFmpeg 下载、合并并校验。任务页区分下载、合并和文件校验；只有校验完成才进入「已存」。不同格式按实际扩展名分享，MKV 等不适合相册的容器会提示使用文件分享。

「我的」显示实际引擎版本，并可清除网页登录态。登录态只保存在本机；默认匿名解析，不自动读取其他浏览器账户。引擎随 APK 固定更新，不依赖解析服务器。

此功能面向点播视频，不保证所有网站可用；不支持 DRM、直播录制或整站采集。部分网站会限制 WebView 登录。详细架构、依赖校验和、16 KB 原生库修复与许可来源见 [通用视频引擎说明](docs/research/universal-video-engine.md)，当前通过及未验证项见 [实施验证记录](docs/validation/universal-video-2026-09-29.md)。

## 应用更新入口

- 「我的 → 检查更新」显示当前版本，并读取一个可配置的版本清单 JSON（`versionCode` / `versionName` / `downloadUrl` / `releasePageUrl` / `notes` / `notesEn`）判断是否有新版本。`notesEn` 是英文说明：弹窗按界面语言在两者之间选，缺一版就回落到另一版（`AppUpdateManifest.notesFor`）。
- 发现新版本时显示版本号与更新说明，并提供「下载新版」或「查看发布页」，在系统浏览器或下载器中打开；应用不申请安装权限，也不静默安装。
- 更新源地址默认取构建期参数 `-PupdateManifestUrl=…`（写入 `BuildConfig.DEFAULT_UPDATE_MANIFEST_URL`），也可在「检查更新 → 更新源」中按设备修改并保留。未配置、离线或清单格式不正确时给出明确提示并保留当前版本信息。
- **正式包默认指向本仓库的 Releases**：`https://github.com/Monkey0803/VideoDown/releases/latest/download/version.json`。`releases/latest/download/…` 是稳定地址，换版本不用改应用，所以 APK 与清单同属一次 Release。
- 发版用 `scripts/publish-release.sh v1.1.1 --notes-file <发行说明.md> --manifest-notes-file <中文清单说明.md> --manifest-notes-en-file <英文清单说明.md>`：先跑 JVM 测试，再分别构建 arm64 与 universal 两个 APK（构建期嵌入清单地址），按 `app/build.gradle.kts` 的 `versionCode` / `versionName` 生成 `version.json`，最后 `gh release create` 把两个 APK 与清单作为附件上传。tag 与 `versionName` 不一致时脚本直接拒绝。
- **发版一律落在 `main` 上**：工作区不干净直接拒绝；当前不在 `main` 时，脚本先把当前分支合并进 `main`（`--no-ff`）并推送，构建与打 tag 都在 `main` 的 HEAD 上进行（`gh release create --target main`），保证「`main` 上的代码 = 发布的包」。`--merge-only` 只做合并与推送、`--skip-merge` 只供排查、`--dry-run` 只校验并打印计划。
- 老版本要能看到升级，清单里的 `versionCode` 必须大于它自己的；附件名固定为 `videodown-arm64-v8a-release.apk` / `videodown-universal-release.apk`，清单的 `downloadUrl` 指向 arm64 包（通用包从发布页取）。
- 解析引擎仍随 APK 固定更新，不引入独立的引擎热更新。验证记录见 [应用更新入口验证](docs/validation/app-update-entry-2026-10-09.md) 与 [1.1.0 发版记录](docs/validation/release-1.1.0-2026-10-10.md)。

## 功能对照

| # | 需求 | 实现位置 |
|---|------|----------|
| 1 | 解析抖音、微博、快手、X 链接、下载视频、**支持选择分辨率** | `data/repository/MultiPlatformVideoRepository.kt`（平台路由）+ `data/remote/`（链接解析）+ `data/download/`（下载）+ `presentation/home/HomeScreen.kt`（分辨率选择 UI） |
| 2 | 记录下载历史 | `data/local/DownloadHistoryStore.kt` + `presentation/history/` |
| 3 | 打开原视频与对应作者主页 | `core/DouyinIntents.kt`（抖音优先唤起客户端，其余平台打开对应网页） |
| 4 | 断点续传 | `data/download/ResumableDownloader.kt`（HTTP Range + `If-Range`）+ `DownloadEngine`（偏移量落盘） |
| 5 | 播放视频、横屏全屏 | `presentation/player/PlayerScreen.kt`（标题栏与播放器内均可切换横屏；按视频比例显示，切换保留进度和暂停状态，返回键先退出全屏） |
| 6 | 画质显示文件大小 | `data/remote/VideoSizeProbe.kt`（后台读取 CDN 响应头；不可获取时明确显示大小未知） |
| 7 | 转存到相册 | `data/gallery/GalleryExporter.kt`（通过 MediaStore 复制已完成视频） |
| 8 | 已存视频支持瀑布流与列表切换，并记住布局选择 | `presentation/library/LibraryScreen.kt` + `core/AppPreferences.kt`（点击「我的影像库」右侧按钮切换；瀑布流按封面比例排列，列表显示封面、标题、作者和保存时间） |
| 9 | 已存视频长按常用操作 | `presentation/library/LibraryActions.kt`（两种布局均可长按播放、分享、保存到相册、删除；删除需再次确认，保存期间防止重复操作） |
| 10 | 输入页键盘避让与自动收起 | `presentation/MainActivity.kt` + `presentation/home/HomeScreen.kt`（输入框和解析按钮避让键盘；解析、完成键、粘贴、点击空白和离开页面时收起，保留输入内容） |
| 11 | X 推文多视频选择 | `domain/model/ParsedVideos.kt` + `presentation/home/`（每个视频独立选择画质；保留 `/video/N` 指定的媒体索引） |
| 12 | 微博动图保存为 MP4 | `data/remote/WeiboJsonParser.kt`（读取微博提供的动图 MP4；多张动图独立选择，支持转发微博，保存后可播放和分享） |
| 13 | 通用网址、网页辅助、HLS / DASH / 分轨合并 | `data/generic/` + `presentation/browser/` + APK 内置 yt-dlp / FFmpeg |
| 14 | 存储占用、残留清理与空间检查 | `data/local/DownloadStorageRepository.kt` + `data/download/StorageSpaceGuard.kt` + `presentation/settings/StorageDialog.kt` |
| 15 | 并发设置与等待任务排序 | `data/download/DownloadQueueScheduler.kt` + `core/AppPreferences.kt` + `presentation/history/` |
| 16 | 按失败原因恢复与页面跳转 | `domain/model/DownloadFailure.kt` + `presentation/home/` + `presentation/history/` + `presentation/MainActivity.kt` |
| 17 | 应用更新入口与可配置版本清单 | `data/update/AppUpdateChecker.kt` + `presentation/settings/UpdateDialog.kt` + `presentation/settings/UpdateViewModel.kt` + `core/AppPreferences.kt` + `app/build.gradle.kts`（`-PupdateManifestUrl`） |
| 18 | 剪贴板链接识别（可关闭，忽略后不再提示） | `data/remote/ClipboardLinkPolicy.kt` + `presentation/home/HomeViewModel.kt` + `presentation/home/HomeScreen.kt`（焦点后读取、确认横幅）+ `core/AppPreferences.kt` |
| 19 | 通知内单任务暂停 / 继续 | `data/download/DownloadService.kt`（`ACTION_PAUSE_ONE` / `ACTION_RESUME_ONE` + 每任务独立通知）+ `presentation/settings/SettingsScreen.kt` |
| 20 | 启动器快捷方式（粘贴解析 / 批量导入 / 下载任务） | `LauncherShortcuts.kt` + `presentation/MainActivity.kt` |
| 21 | 任务页搜索、排序与预计剩余时间 | `presentation/history/TaskQuery.kt` + `presentation/history/HistoryScreen.kt` + `presentation/history/HistoryViewModel.kt` + `core/Formatters.kt`（`formatEta`） |
| 22 | Material You 动态取色 | `core/AppPreferences.kt` + `presentation/settings/SettingsScreen.kt` + `presentation/MainActivity.kt` + `presentation/theme/Theme.kt` |
| 23 | 播放手势（双击快进退、竖滑亮度音量、长按 2×） | `presentation/player/PlayerScreen.kt` + `domain/model/PlayerGestures.kt` |
| 24 | 字幕渲染与开关（含同名 sidecar 字幕） | `presentation/player/PlayerScreen.kt` + `data/playback/SubtitleSidecar.kt` + `core/AppPreferences.kt` |
| 25 | 全局与单任务下载限速 | `data/download/BandwidthLimiter.kt` + `data/download/DownloadEngine.kt` + `data/download/ResumableDownloader.kt` + `presentation/settings/SettingsScreen.kt` + `presentation/history/HistoryScreen.kt` |
| 26 | 下载后自动存入相册 | `data/gallery/AutoGalleryExport.kt` + `core/ServiceLocator.kt` + `data/download/DownloadCompletionNotifier.kt` + `presentation/settings/SettingsScreen.kt` |
| 27 | 继续观看进度回填 | `domain/model/PlaybackState.kt` + `data/playback/PlaybackPreferences.kt` + `core/WatchBadge.kt` + `presentation/library/LibraryScreen.kt` + `presentation/home/HomeScreen.kt` |
| 28 | 音频睡眠定时器 | `domain/model/SleepTimer.kt` + `data/playback/AudioPlaybackService.kt` + `presentation/player/PlayerScreen.kt` |
| 32 | 私有保险箱与应用锁 | `domain/model/VaultPolicy.kt` + `core/VaultAuthenticator.kt` + `data/local/DownloadLibraryMetadataRepository.kt` + `presentation/library/*` + `presentation/history/HistoryScreen.kt` + `data/download/*Notifier/Service` |
| 31 | 字幕下载与内嵌 | `data/generic/EngineRequest.kt` + `assets/video_engine.py` + `domain/model/SubtitleLanguageSelection.kt` + `domain/model/DouyinVideo.kt` + `presentation/home/HomeScreen.kt` |
| 29 | 合集 / 播放列表批量解析 | `data/generic/EngineRequest.kt` + `data/generic/GenericVideoRepository.kt` + `assets/video_engine.py` + `presentation/settings/SettingsScreen.kt` |
| 30 | 链接导入导出与设置备份（含已存自定义顺序） | `data/backup/SettingsSnapshot.kt` + `data/backup/SavedOrder.kt` + `data/backup/LinkList.kt` + `data/backup/BackupDocuments.kt` + `core/AppPreferences.kt` + `presentation/settings/SettingsBackupFlow.kt` + `presentation/settings/SettingsScreen.kt` |

## 环境要求

- JDK 17 或以上（Android Studio 自带的 JBR 可直接使用）
- Android SDK：`compileSdk 36`、`build-tools 36.1.0`、`platform android-36`
- 在 `local.properties` 中指向本机 SDK：

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

## 构建与测试

命令速查、架构硬规则与「不要顺手改」清单见 [AGENTS.md](AGENTS.md)；本节保留更完整的说明。

```bash
# 单元测试（覆盖四平台解析、匿名访问与备用接口、多视频切换、断点续传、历史兼容、Wi-Fi 限制及完成回调）
./gradlew testDebugUnitTest

# 设备接口测试（偏好保存、系统网络判断、通知开关；通知发送用例需允许应用通知）
./gradlew connectedDebugAndroidTest

# 可选联网测试：用两个公开示例执行实际解析、完整下载及视频元数据校验
# 需在线 Android 设备和网络，会下载约 81 MiB；默认设备测试跳过此组
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.SocialVideoLiveDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_platforms=true

# X 真实 UI / 联网测试：解析、1080P 完整下载、主接口故障时备用接口、多视频切换
# 下载约 17 MiB；省略 live_x 时仅执行离线 UI 用例
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XVideoDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_x=true

# X 通用引擎补全：真实推文的分轨画质、下载合并及音视频轨道
./gradlew connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.XVideoDeviceTest#liveUnifiedXAddsEngineFormats' \
  -Pandroid.testInstrumentationRunnerArguments.live_x_engine=true \
  -Pandroid.testInstrumentationRunnerArguments.live_x_engine_download=true

# 微博 GIF 真实 UI / 联网测试：解析示例、完整保存 MP4、应用内播放，以及多动图选择
# 下载约 865 KiB；省略 live_weibo_gif 时仅执行离线 UI 用例
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.WeiboGifDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_weibo_gif=true

# 生成 arm64 Debug APK（默认目标架构就是 arm64-v8a）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk

# 生成 arm64 签名 Release APK
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/videodown-arm64-v8a-release.apk

# 需要通用包（含 x86_64，给模拟器用）时显式指定
./gradlew assembleRelease -Pabi=universal
# 产物：app/build/outputs/apk/release/videodown-universal-release.apk

# 安装到已连接设备
./gradlew installDebug
```

### arm64 打包

目标架构通过 `-Pabi=<值>` 控制，**默认 `arm64`**，所以 `assembleDebug` / `assembleRelease`
产出的就是 arm64 APK。构建脚本用 `ndk.abiFilters` 限制打包进 APK 的 native 库。

| `-Pabi=` | 打包的 ABI | 适用 |
|---|---|---|
| `arm64`（默认，等价 `arm64-v8a`） | `arm64-v8a` | 2017 年后几乎所有真机 |
| `universal`（等价 `all`） | `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` | 兜底，含 x86_64 模拟器 |
| `armeabi-v7a` / `x86` / `x86_64` | 对应单一 ABI | 特殊设备 |

产物文件名会带上架构标记（`base.archivesName`），例如
`videodown-arm64-v8a-release.apk`、`videodown-universal-release.apk`。

接入通用引擎后，APK 包含 Python、QuickJS 和 FFmpeg，体积比原来增大。arm64 与 x86_64 构建分别验证，`universal` 会打包所有 ABI。组件版本固定，不需要用户另外安装运行环境。

> ⚠️ 使用 `abiFilters` 意味着**一次构建只产出一个架构的 APK**：先构建 `universal` 再构建 `arm64`
> 时，AGP 会清掉上一次的产物。需要两种包就分两次构建。
>
> 这里刻意**没有**使用 ABI splits：开启 splits 后，把 `testDebugUnitTest` 与
> `assembleDebug`/`assembleRelease` 放在同一条 Gradle 命令里执行时，会在 AGP 的
> `PackageAndroidArtifact$IncrementalSplitterRunnable` 里随机失败（本机实测 3 次中失败 2 次）。
> 改用 `abiFilters` 后同样的命令连续 4 次全部成功。

### 签名

- `keystore/debug.keystore`：签入仓库的调试签名（口令均为 `android`），让 `assembleDebug` 在任何
  机器与 CI 上都能复现。
- Release 签名从仓库根目录**被 git 忽略**的 `keystore.properties` 读取，模板见
  `keystore.properties.example`。若该文件不存在，`assembleRelease` 会产出未签名包。

```properties
# keystore.properties（不要提交）
storeFile=keystore/release.keystore
storePassword=<your-password>
keyAlias=<your-alias>
keyPassword=<your-password>
```

> ⚠️ 本仓库**没有提交任何 release 密钥**：`keystore/release.keystore` 与 `keystore.properties`
> 都已加入 `.gitignore`。正式发布前请生成你自己的密钥：

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -storetype PKCS12 \
  -alias <your-alias> -keyalg RSA -keysize 2048 -validity 10000
```


## 架构

单模块 Clean Architecture 分层，依赖方向严格单向：

```
presentation  ──▶ domain ◀── data        core 被所有层使用
   (Compose)      (纯 Kotlin)   (网络/磁盘/下载)
```

```
app/src/main/java/com/videodown/douyin/
├── core/                     # 结果类型、格式化、Intent 跳转、ServiceLocator（手写 DI）
├── domain/
│   ├── model/                # DouyinVideo / VideoVariant / VideoPlatform / DownloadRecord（纯 Kotlin，可序列化）
│   └── repository/           # VideoRepository / DouyinRepository / Downloader / DownloadHistoryRepository 接口
├── data/
│   ├── remote/               # 平台链接识别、抖音/微博/快手解析器、匿名网页访问
│   ├── download/             # ResumableDownloader、DownloadEngine、DownloadPaths、DownloadService
│   ├── local/                # DownloadHistoryStore（JSON 原子写入）
│   └── repository/           # 接口实现
└── presentation/
    ├── home/                 # 解析 + 分辨率选择 + 下载进度
    ├── history/              # 历史列表、继续/暂停/重试/删除
    ├── player/               # ExoPlayer 播放
    └── theme/
```

`domain` 不依赖任何 Android/框架类，可被纯 JVM 单元测试直接覆盖。

## 抖音解析策略

抖音 Web API 依赖会失效的请求签名（`a_bogus` / `X-Bogus`），因此解析按**可靠性从高到低**逐级降级：

1. **匿名分享页（首选）**：`GET https://www.iesdouyin.com/share/video/{aweme_id}/`
   该页面为 SSR 渲染，正文内嵌 `window._ROUTER_DATA` JSON，无需签名即可拿到完整视频信息。
2. **签名 Web 接口**：`/aweme/v1/web/aweme/detail/`（在具备有效 Cookie/签名时可用）。
3. **旧版 v2 接口**：`/web/api/v2/aweme/iteminfo/`（仅作最后兜底）。

短链 `v.douyin.com/xxxx` 通过 HTTP 302 展开后再提取 19 位 `aweme_id`。

解析 JSON 时不写死字段路径，而是**广度优先搜寻找第一个「像 aweme」的对象**（同时含有 `aweme_id`
与 `video.play_addr`）。抖音每年会数次调整 `_ROUTER_DATA` 的键名（`video_(id)/page`、`note_(id)/page`、
`videoInfoRes`、`aweme_detail` 等），这种写法可以在键名变动后继续工作。

### 分辨率来源与优先级

1. `video.bit_rate[]` —— 官方逐档码率，作为权威分辨率列表；
2. `video.play_addr` —— 默认（通常最高画质、无水印）；
3. `video.play_addr_h264` —— H.264 转码，兼容性更好；
4. `video.download_addr` —— 带水印，排序最后；
5. `ratio=` 改写派生档位 —— 当接口只给出单一码率时补充 720P/540P/360P。

分辨率标签按**短边**命名（竖屏 1080×1920 → `1080P`），与抖音自身的 `ratio` 字段一致。
最终按标签去重，界面上每一档只出现一次。

## 微博与快手解析

- 微博支持正文链接（`weibo.com/{uid}/{id}`、`m.weibo.cn/status/{id}`、`m.weibo.cn/detail/{id}`）及可展开的 `t.cn` 短链。通过匿名访客流程获取临时 Cookie，再读取正文视频信息，不读取浏览器或账号 Cookie。画质来自 `playback_list`，过滤图片预览资源。
- 快手支持 `kuaishou.com/f/...` 分享短链、`/short-video/{id}` 和移动端 `/fw/photo/{id}`。跟随分享页跳转并解析内嵌 JSON，按目标视频 ID 匹配，避免下载推荐列表中的其他视频。
- 两个平台复用画质选择、文件大小、下载/续传、历史、播放和转存相册流程；下载请求及续传保留各平台的 Referer。已有抖音历史记录仍可读取。
- 这些专用解析器提供 MP4 快速路径；未返回视频时会进入通用引擎。需要登录或验证时可打开网页辅助。直链地址失效会尝试重新解析一次，能够确认所选媒体和格式时刷新链接；断点不兼容则重下，身份无法确认时提示重新选择。

2026-09-29 在 Android 模拟器中完成两个指定链接的实际网络下载和视频轨道校验：微博 `RklMe8iOM` 提供 1080P/720P/540P，实测下载 540P（8,754,435 字节、约 67 秒）；快手 `X3uQi2UzJmqN2fq` 提供 720P，实测下载 75,746,030 字节、约 261 秒。此项为设备端接口测试，不代表手动点击 UI 或所有平台视频均已验证。

## X（Twitter）解析

- 主路径为匿名 Guest + GraphQL，失败或缺少可用媒体时自动尝试嵌入页数据接口。匿名会话只保存在内存中，过期后重建，不读取或保存登录 Cookie。
- 支持 `x.com`、`twitter.com`、移动域名和指向推文的 `t.co` 短链；短链只允许跳转到已识别的 X 域名。`/video/N` 按原媒体位置选择，图文混排不会错选其他视频。
- 一条推文有多个视频时，结果页先选择视频，再选择该视频的画质；引用视频单独标识。下载记录保留各媒体的独立 ID、来源链接和请求头。
- 原生路径读取页面提供的 MP4（包括动画 GIF 对应的 MP4）；对同一推文继续用 yt-dlp 补充 HLS、分轨画质及视频卡片，按媒体 ID 合并候选并保留 `/video/N` 的选择。用户选择分片画质后由引擎下载与合并。受保护或要求登录的内容可在网页辅助中使用本机授权会话重新解析；实际能否访问取决于账号和网站限制。
- `XVideoHttpClient` 集中维护网页接口版本与参数；`XSyndicationToken` 的浮点转 36 进制行为有 JavaScript 对照测试。相关接口不是稳定的开发者 API，平台调整时仍需更新适配。

2026-09-29 验证：92 项单元测试通过；3 项 Android 设备测试通过，包括真实界面输入/解析/选择 1080P/保存、离线多视频切换及故障注入后的真实备用接口访问。指定推文 `2096916397947257248` 提供 1080P/720P/360P/270P，完整下载 17,618,131 字节，Android 读取到 1920×1080、约 38 秒、包含音频。截图和日志位于 `app/build/reports/x-device/`。本次未验证 X 视频的转存相册或全程播放。

新增的 X 补全单元测试覆盖原生 MP4 与引擎 HLS 合并、视频卡片追加和指定媒体保留；真实 X 引擎结果以设备联网用例 `XVideoDeviceTest#liveUnifiedXAddsEngineFormats` 单独验收。网页登录态按实际请求主机查询 Cookie，额外覆盖 `api.x.com`；真实账号登录仍需单独验证。直播录制、纯音频 Spaces 与 DRM 不在点播下载范围内。

## 断点续传实现

`ResumableDownloader` 的行为与 `curl -C -` 一致：

- 已有 `.part` 文件时发送 `Range: bytes=<n>-`，并带上首次响应记录的 `ETag` 作为 `If-Range`，
  远端文件变化时自动从头重下而不是拼出损坏文件；
- `206 Partial Content` → 追加写入；`200 OK`（服务端忽略 Range）→ 截断重写；
  `416 Range Not Satisfiable` → 判定本地已完整，直接改名为成品；
- 网络中断时保留已落盘的字节，记录 `PAUSED`（可继续），并对瞬时错误做 3 次指数退避重试；
- 传输过程中每 2 秒把字节偏移量写入历史文件，进程被杀后仍能从断点恢复；
- 应用启动时会把上次遗留的 `RUNNING`/`PENDING` 记录降级为 `PAUSED`，界面显示「继续」而不是卡住的进度条。

前台服务 `DownloadService` 在下载期间保活进程并在通知栏显示进度与「全部暂停」。

## 存储位置

视频保存在应用私有外部目录，无需任何存储权限，并通过 `FileProvider` 供播放/分享使用：

```
Android/data/com.videodown.douyin/files/Movies/Douyin/
```

历史记录保存在 `files/download_history.json`（写临时文件后 rename，保证原子性；文件损坏时会
保留为 `.corrupt` 而不是静默丢弃）。

## 已知限制

- 抖音接口变动频繁。若三级解析全部失效，界面会给出明确错误提示（已识别视频 ID 但取不到信息），
  此时需要更新 `DouyinJsonParser` 的字段适配。
- 图文（图集）作品没有视频流，会提示「没有可下载的视频流」。
- `ratio=` 派生档位属于兜底策略；若接口已提供完整 `bit_rate` 列表，则以官方档位为准。
- 合集解析最多 20 条，且**取决于各站 extractor 的能力**：需要登录、需要 JS 渲染或受地区限制的合集可能只返回部分条目，甚至与单视频一样只返回一条；实例化结果里只包含引擎能拿到直链的条目，直播、DRM 与私享内容会被过滤掉（不会以占位条目出现）。
- 合集解析的「整页枚举」只在引擎路径生效；被 `probeDirect` 直接识别的裸视频链接没有合集概念。
- 字幕同样只在引擎路径可用：被 `probeDirect` 直接识别的裸视频链接没有提取器上下文，结果页不会出现字幕选项。引擎侧的真实字幕可用性取决于站点（需要登录、只提供机器字幕、或字幕单独收费的源另说）。
- 字幕语言由应用挑选（最多一个中文 + 一个英文），不提供逐语言勾选；最多保留两条轨，不做双语以外的多语内嵌。
- 音频档位永远不取字幕；任务卡片也不显示「含字幕」，只能从播放页的字幕开关看出。
- **保险箱需要 Android 9 及以上**，且设备已设置锁屏或已录入指纹/人脸；否则入口会说明「请先在系统中设置锁屏密码或指纹」，而不是假装上锁。更低版本没有可供普通应用使用的验证弹窗，因此不支持。
- **系统验证弹窗本身没有被自动化测试覆盖**：测试用可替换的实现分别驱动「通过」与「拒绝」两条路径，验证的是门禁两侧的行为（隐藏、显示、通知与导出不外泄）。
- **设置备份里的「已存顺序」按记录 id 匹配**：同一台设备导出再恢复能把手动排好的顺序带回来；换机恢复时 id 一个都对不上，顺序保持原样（下载记录本身不随这份备份走）。
- **保险箱保护的是「看见」，不是「取出」**：文件仍在应用私有目录里，没有额外加密；有 root 或调试备份能力的人仍能拿到文件。
- 任务列表与首页「最近保存」本身没有门禁，所以私密条目在那里只显示「保险箱内容」；任务列表的搜索仍会匹配被隐藏的标题，只是匹配到的条目依旧显示为「保险箱内容」。
- 保险箱内手动「保存到相册」与「分享」仍然可用——门禁已经放行，这是用户的明确操作。
- **视频号分享链接不支持解析**（`weixin.qq.com/sph/…` 及其跳转后的 `channels.weixin.qq.com`）：那个页面只给标题、作者和播放数据，**播放地址在微信客户端内动态签发**，网页里没有可直接下载的地址；随包 yt-dlp 里也没有任何 weixin/wechat 提取器（实测逐个字符串搜过）。解析必然失败，所以失败时给的是专门的说明（「这是视频号链接：内容只能在微信里播放…」），不再提示「打开网页登录或播放后查找」——那条路对这个网站是死的；若已从别处拿到直链，粘贴直链可以正常下载。判定入口是 `VideoLinkExtractor.isWeChatChannelsLink`（`mp.weixin.qq.com` 的公众号文章不在此列）。
- **网页解析面板扫完一定会给一句结论**：找到候选就说已检查资源，一个都没有就说「本页没有发现可下载的视频」；视频号页面换成上面那句说明。候选判定见 `BrowserMediaDetector`，面板的状态文案由纯函数 `browserStatusRes` 决定。
- **不要用 `embedsubtitles` 参数**：它是 yt-dlp 的命令行专用开关，`YoutubeDL` 会直接忽略它（已实测：下载成功、无报错、文件里没有字幕轨）。引擎里必须显式添加 `FFmpegEmbedSubtitle` 后处理器。
