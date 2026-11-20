# 拾影（Keepsake）— 视频下载器

项目自有源码按 [GNU GPL 3.0 或更新版本](LICENSE)（`GPL-3.0-or-later`）发布。随包第三方代码、引擎和素材保留各自的许可证（看图器使用 [ZoomImage](https://github.com/panpf/zoomimage)，Apache-2.0）；引擎许可来源见[通用视频引擎说明](docs/research/universal-video-engine.md)。F-Droid 收录所需的第三方源码与素材权属核查仍在进行，见 [F-Droid 预检](docs/validation/fdroid-precheck-2026-10-15.md)。

使用 Kotlin + Jetpack Compose 实现的 Android 应用，支持解析抖音、微博、快手、X（Twitter）、哔哩哔哩分享链接及通用 HTTP/HTTPS 网址，选择分辨率下载视频、保存分享链接里的图片与图文作品（可打包成一个 ZIP）、
记录下载历史、打开原视频与作者主页并播放已下载的视频。界面使用 Compose Material 3，在「我的 → 外观模式」中可选择跟随系统、浅色或深色，确认后立即生效并在重启后保留；Android 12 及以上还可在「我的 → 动态取色（Material You）」改用系统壁纸配色。默认采用奶白、暖橙与深褐品牌主题，深色模式使用暖褐背景与杏橙强调色。颜色、文字样式与圆角统一由 `MaterialTheme` 管理。

主导航分为「解析」「任务」「已存」「我的」：解析页支持粘贴分享文案和选择画质；任务页显示实时进度及暂停、续传、删除操作；已存页展示文件仍存在的已完成视频；「我的」可设置默认画质、仅在 Wi-Fi 下下载、完成时通知、每条任务独立通知、识别剪贴板链接与动态取色，偏好在重启后保留。

解析结果加入队列后保留在当前页面，便于继续选择内容；「解析 / 已存 / 我的」显示全局下载结果条：进行中按实际字节汇总总进度，同时保留本次新失败/暂停数量，历史异常不会在每次启动后永久占位；完成结果点击进入「已存」，其余状态进入「任务」，未查看结果也会显示在任务入口徽标上。窗口宽度按 Material 3 尺寸类分档：Compact（<600dp）使用底部导航，Medium/Expanded（≥600dp）改用侧边导航；首页在 Expanded（≥840dp）拆成「解析主面板 + 站点/最近保存」双栏，已存 Feed 按 Compact / Medium / Expanded 给 2 / 3 / 4 列，设置页在 Expanded 改为「分类列表 + 详情」。折叠屏存在可分离折痕时内容收敛到折痕一侧，关键操作不跨折痕。「我的」首页分为「外观与语言 / 下载与网络 / 网站与账号 / 备份与存储 / 关于与更新」五个入口；恢复设置会先预览改动，清除网页登录态会先说明将暂停的任务并再次确认。

图标采用「拾影框」品牌标志及统一的圆角线性图标：桌面使用暖橙渐变，自适应图标提供独立单色主题版；应用内导航、解析、任务、影像库、播放和设置图标随浅深色品牌主题着色。播放、继续下载、收藏、保存到相册及导出文件夹使用不同语义的图标。设计源文件与展示页见 [图标设计](docs/design/shiying-icons/README.md)，接入及模拟器验证见 [图标接入记录](docs/validation/icon-integration-2026-09-30.md)。修改 SVG 后可运行 `python3 scripts/generate_shiying_icons.py` 重新生成 Android VectorDrawable。

开启「仅在 Wi-Fi 下下载」后，非 Wi-Fi 网络下的新建、继续和重试任务会暂停；下载期间离开 Wi-Fi 或在移动网络上开启该限制，也会停止传输并保留断点。连接 Wi-Fi 后需点击「继续」。开启「完成时通知」会按需申请通知权限；拒绝授权或关闭系统通知渠道时，页面会提示前往系统设置。每次成功完成下载后发送独立通知，点击打开对应视频或音频的详情；关闭该偏好不影响下载期间的前台服务进度通知。

下载在前台服务确认启动后才开始传输。服务启动被系统拒绝、启动超时或运行中意外停止时，任务页显示「后台下载已停止」和具体原因，保留断点供返回应用后重试；正常完成或全部暂停后退出服务。系统到达后台下载时限时也停止服务并保留任务。前台服务不保证应用被强制停止或系统杀进程后继续运行。

## 重复提醒、批量操作与搜索

- 下载前按平台、媒体 ID 和所选画质/格式检查历史。已有任务或已保存文件时显示提醒，可查看已有视频/任务，或明确选择「仍然下载」。文件已丢失的完成记录不会阻止重新下载，不同画质可分别保存。
- 多视频解析结果顶部显示当前视频和已选数量；点击「查看全部」打开自适应网格。点击封面切换预览，勾选框独立控制批量选择，支持三态全选。默认不勾选，底部按钮保存当前视频；勾选后按各视频保留的画质保存所选项目。存在重复项时，可跳过重复项并下载其余视频，或仍然下载全部所选视频。封面高度随窗口调整，长标题默认两行，可展开查看全文。验证截图见 [结果页布局验证](docs/validation/result-selection-2026-09-30.md)。
- 任务页支持多选暂停、继续、删除；全部暂停与全部继续可分别使用。任务卡只保留当前状态的主操作，删除、分享、限速、重新下载与队列位置调整收进「更多任务操作」，失败恢复动作仍直接显示。批量控制只处理符合当前状态的项目，批量删除先确认。
- 已存内容长按可「检查文件」：先按完成时记录的大小与修改时间做低成本比对，再对路径存在的文件做容器/轨道/尾部结构校验；结果显示完整、文件缺失、与完成时不一致或已损坏。缺失或损坏时可以直接「重新下载」（沿用原地址并保留收藏、分类、隐私与手动排序）或「重新解析」（回到解析页重新取地址）。检查不做全量哈希，也不逐帧解码。
- 已存页可按标题/作者搜索、按平台筛选，并按最新保存、最早保存、文件大小排序。两种布局均支持多选删除和保存到相册。「全选当前结果」仅选择当前可见的筛选结果，改变筛选条件会清除选择。
- 批量操作逐项执行并显示进度和成功、跳过、失败数量。单项失败不会中断其余项目；可查看具体失败视频和原因，保存/删除完成后仅保留失败项选中，便于重试；删除只影响本地下载及记录，不删除相册副本。Android 8/9 保存到相册仍需存储权限。
- 保存到相册（视频 / 音频 / 图片 / Live Photo）与打包 ZIP 写入公共下载目录都会留下导出回执：记录 id + 源文件指纹（名称、大小、修改时间）+ 目标 URI。再次保存时先确认目标仍在，存在就提示「已保存」而不是默默写第二份，只有用户明确选择「再保存一份」才写；从系统相册或下载目录删除目标后回执自动失效，下一次照常写入。Live Photo 的静帧与 MOV 按一对记录，任一半缺失或写入失败都不会留下半套回执。

## 存储、队列与失败恢复

- 「我的 → 存储空间」显示下载视频、临时文件、可用空间和可清理残留。清理需要确认，并在执行前重新检查文件指纹和任务引用；保留有效媒体、相册副本，以及暂停、失败或进行中任务引用的断点和合并片段。清理对象仅限未引用的下载临时文件、引擎任务目录和文件已丢失的完成记录；已存内容请在「已存」中选择删除。
- 存储空间同时可视化运行任务的空间预算：显示尚需写入的总量（含每任务 32 MiB 工作余量和引擎合并的第二份空间）、占用比例条，以及按剩余字节排序的任务明细；预留超过当前可用空间时直接给出缺口和「清理残留或删除已存内容」的处理建议。已写入字节由可用空间反映，不重复计入预算。
- 运行中的下载共享空间预留账本：任务取得队列名额后，原子预留未来仍需写入的空间，每个任务保留 32 MiB 工作余量；已知大小的引擎任务按下载与合并两份空间估算。已经写入的文件占用由实际可用空间反映，不重复预留；暂停、失败、取消和完成后释放预留，等待中的任务不占空间预算。
- 下载完成的媒体在「容器可打开、首样本存在、无 DRM」之外再校验轨道与尾部：分轨合并结果必须同时具备任务要求的视频轨和音频轨，声明了时长的文件在 90% 处之后仍要有有效样本；服务器的提前断流、未知 `Content-Length` 造成的截断 MP4 / WebM / 音频不会再被记成成功。校验只做有限次样本读取，不逐帧解码；失败时删除未发布的成品，保留 `.part` / `.ytdl` 分片，重试可续传。没有声明时长的容器仍只做首样本校验。
- 直链收到响应后按实际大小调整预算，每次写入前重新检查；未知大小按已写入量滚动检查。引擎下载、合并、音频提取和校验期间根据任务目录实际占用持续检查；空间不足保留断点并提示清理后继续。此机制协调本应用的并发任务，无法保证其他应用突然大量占用磁盘或引擎未知大小输出不会触发实际磁盘不足。
- 「我的 → 同时下载数量」可设置 1–5 个任务，默认 3，重启后保留。提高数量立即开放名额；降低数量时正在下载、合并或校验的任务继续，新任务等待名额。
- 等待任务显示排队位置，并支持前移、后移和优先下载。仅调整等待任务，顺序保存到历史；重新开始或单独继续的任务加入队尾。进程退出后任务恢复为暂停，点击「全部继续」按保存顺序加入队列。
- 「我的 → 自动继续下载」默认开启，用于网络和空间原因造成的中断：离开 Wi-Fi、连接提前中断或磁盘暂满而暂停、失败的任务，在网络恢复或空间充足后自动继续，进程退出遗留的任务在下次启动自动继续。用户主动暂停的任务永不自动继续；关闭开关后全部中断都需手动点击继续。自动重试有次数上限；条件尚未消除时只等待、不计次。
- 直链与 yt-dlp / HLS / DASH / 分轨任务共用一套有界重试：网络中断和 429/503 最多再试 3 次，`Retry-After` 有秒数或 RFC 1123 日期时按服务端时间等待，缺失时按 1.5s 起翻倍的指数退避（单次封顶 15 分钟）。下一次允许重试的时间与已用次数会写进任务记录，进程被杀或应用重启后的自动继续不会提前发请求；任务卡显示「等待重试 · N 秒」，倒计时期间「更多任务操作」里的「立即重试」可由用户明确跳过冷却。
- 失败提示区分网络、登录/验证、访问受限、链接过期、存储空间、文件写入、画质变化、网站限流和引擎故障。按原因提供重试、重新解析原网址、打开网页登录、管理空间或检查下载设置。HTTP 403 保留“过期或访问受限”的含义，避免直接当作需要登录。
- 网页辅助与重新解析使用原始页面网址；旧记录缺少原网址时明确提示重新粘贴，并禁用对应操作。DRM 和直播显示不支持，不提供反复重试入口。

详细验证和运行边界见 [P1 验证记录](docs/validation/p1-features-2026-09-30.md)。

## 批量链接、音频与文件整理（P2）

- 「解析 → 批量导入多个链接」接受分享文案或多条网址，去重后逐条解析，最多 50 条。结果以封面、标题、作者与画质展示，原始网址只在失败行出现；输入区解析后折叠成一行摘要，可随时展开重解析。每条链接可单独重试，支持三态全选、停止与继续解析未完成项；解析其它链接期间，已就绪条目仍可勾选和挑画质，部分失败不会丢失成功结果。图片来源显示为「找到 N 张图片」。加入队列前再次检查重复，可跳过已有项或明确选择仍然下载。
- 通用引擎报告独立音轨时，解析结果显示「单独保存音轨」，可单独下载为 M4A。未提供独立音轨时，可以先保存视频，再在影像库长按选择「提取音频」。本地提取不需要联网；AAC 优先保留原编码采样，其他编码转 AAC，保留原视频。音频任务沿用队列及暂停、继续、重试、取消；无音轨的视频显示明确原因。
- 音频可在应用内播放、通过系统分享、保存到系统音乐目录或自定义文件夹。音频不会进入视频相册；后台播放能力见下方 P3。
- 影像库长按可收藏、实际重命名本地文件并设置分类；支持收藏及分类筛选。分类为库内标签，不移动文件。重命名保留扩展名，写入失败时回滚，并在启动时恢复中断的重命名事务。标题沿用平台原文（可能含换行与 `?`、`/` 等标点），写入磁盘的文件名按下载时同一套规则清洗，路径分隔符不会逃出下载目录。
- 单个文件和多选文件均可「导出到文件夹」，使用 Android 系统目录选择器取得并保留授权，记住上次目录。同名副本增加序号，保留原文件；逐项显示结果，失败项可以单独重试，停止时清理当前未完成副本。
- 文件夹不可写或授权失效时，清除失效目标并提示重新选择；空间不足时提示释放空间或更换目录，可在清理空间后只重试失败项。本地原文件不修改。
- 导出界面旋转保留本次任务；进程重建后提示上次中断并尝试清理未完成副本。无法访问的旧目录保留待清理记录，不阻止导出到新目录；整批导出不承诺在进程退出后自动继续。

验证记录见 [P2 验证记录](docs/validation/p2-features-2026-09-30.md)。

## 续播、播放设置与后台播放（P3）

- 视频和音频记住播放位置，再次打开继续播放；播放结束后从头开始。进度按文件大小和修改时间校验，更换文件后不沿用旧进度。
- 播放页可选择 0.5×、0.75×、1×、1.25×、1.5×、1.75×、2×、2.5×、3×、4×，并开启单曲循环；音频与视频分别记住设置。横屏倍速列表可滚动。
- 音频由媒体前台服务播放，返回其它页面、Home 或锁屏时仍继续。系统媒体通知显示标题和播放控制，点击返回当前音频；通过通知暂停后返回页面保持暂停和进度。支持系统媒体键、耳机播放键和音频焦点处理，拔下耳机时暂停。打开视频会暂停后台音频。
- 视频播放页提供「画中画」；支持的设备在播放中按 Home 自动进入小窗，暂停时不自动进入。小窗隐藏应用内操作，系统小窗可暂停、继续、展开及关闭。返回保持播放器和进度，关闭释放视频播放器。普通后台视频暂停。
- 视频使用沉浸式控制层：单击显隐，播放时 3 秒后自动隐藏；中央可快退、播放/暂停、快进，底部集中进度、倍速、循环、轨道、画面比例、截图、画中画和全屏。全屏仍复用同一个播放器，暂停状态与当前位置不会因旋转重置。
- 画面比例可在「适应 / 裁剪填充 / 拉伸填充」间切换，本次播放结束后恢复默认适应；多音轨和字幕轨按文件实际内容列出。播放失败可原地重试或交给系统播放器，设备存在多个解码器时允许自动回退。
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

- 视频播放页支持左右半屏双击快退 / 快进 10 秒、横向拖动预览并跳转、左半屏竖滑亮度、右半屏竖滑音量，以及长按临时 2× 加速（松手恢复）。横竖方向越过阈值后锁定，控制按钮所在区域优先响应按钮；离开页面时把屏幕亮度交还系统。
- 拖动进度时按需抽取附近关键帧，失败时退化为仅显示时间而不影响 seek。截图按当前时间点保存原始视频帧到 `Pictures/拾影`，不包含控制按钮和字幕。
- 与视频同名的 `.srt` / `.vtt` / `.ass` / `.ssa` 字幕文件会作为独立字幕轨道加载，视频内嵌字幕同样渲染。存在字幕轨道时播放页出现「字幕」开关，选择会保留。只有真的存在字幕轨道时才显示该开关。
- 「我的 → 下载限速」限制**所有直链下载的总速度**（不限速 / 512 KB/s / 1 MB/s / 2 MB/s / 5 MB/s）。任务卡可为单个任务设置上限，或选择「跟随全局」；卡片会说明现在是谁在限制（「任务限速 …」或「全局限速 …」）。限速在**每次写入前**生效且位于空间检查之后，因此磁盘写满仍然立即失败。
- **引擎任务（yt-dlp / HLS / DASH / 分轨）共享同一个全局带宽预算**：多个引擎进程的总速率不再各自受限，而是由应用侧的共享令牌桶统一记账；超出预算时暂停整个引擎进程组一小段时间再继续，单任务上限同时生效。上限在任务运行中修改后，下一个进度事件即生效。引擎请求从不把拉流交给 FFmpeg（FFmpeg 只做本地合并/提取），所以不存在「FFmpeg 自行拉流」这一路；暂停式节流是近似值，短期速率仍可能略高于上限，站点不上报进度的那部分流量也无法计量。
- 「我的 → 下载后自动存入相册」开启后，完成的视频复制到 `Movies/拾影`、音频复制到 `Music/拾影`；完成通知会说明是否已存入。Android 9 及以下需要存储权限，未授权时不复制并在通知中说明原因；保存失败不影响下载本身。
- 「已存」卡片与首页「最近保存」显示播放进度（进度条 + 「已看 N%」）。只有进度与当前文件的大小和修改时间都一致时才显示，重新下载或转码后不会沿用旧进度。
- 音频播放页可设「15 / 30 / 60 分钟后暂停」，按钮显示剩余时间。计时按绝对时间保存在本地，关闭页面后仍在后台生效；到点暂停播放并清除定时。

验证记录见 [P5 验证记录](docs/validation/p5-features-2026-10-09.md)。

## 图片、看图器与打包下载（D）

- **图片是一等媒体类型**：`MediaKind`（视频 / 音频 / 图片 / 压缩包）随下载记录落盘，校验、相册落地、通知文案和「点开之后去哪」都按它分派，不靠扩展名猜。旧版本写下的历史没有这个字段，读进来仍按视频处理。
- **支持的图片来源**：粘贴图片直链（`jpg / jpeg / png / webp / gif / avif / heic / bmp`，按文件头识别而不是只看扩展名）；抖音图文（`/note/`，分享页 `/share/note/` 优先）；微博九宫格/多图（原图优先；动图同时提供站方 MP4 转码与 GIF 原图下载格式；Live Photo 同时保留静帧和原始 `.mov`）；X 多图（只接受 `pbs.twimg.com`，请求原图尺寸，`/photo/N` 选中第 N 张）；快手图集（内嵌 JSON 的 `photo.atlas`）；以及网页辅助面板里页面已加载的图片。
- **每张图是一个独立条目**，沿用多视频结果的勾选/批量流程：可以只保存一张，也可以勾选多张逐个入队。图片下载复用直链下载器（断点续传、限速、空间预留、失败分类），普通图片工作余量降到 2 MiB，避免九张图各占 32 MiB 被误判「空间不足」；Live Photo 因还包含 MOV，按视频任务预留空间。
- **「已存」按类型筛选，不单独开 tab**：顶部筛选条新增「类型」（全部 / 视频 / 音频 / 图片 / 压缩包），与平台、分类、排序、仅收藏、搜索可叠加；筛选生效时同样不允许拖拽自定义排序（顺序只对完整列表有意义）。列表标题改为中性的「已存内容 / N 项」，避免图片和压缩包混进来后还叫「已存视频」。
- **看图器**使用 [ZoomImage](https://github.com/panpf/zoomimage)（Apache-2.0，Maven Central）的 `CoilZoomAsyncImage`：双指缩放、拖动、双击放大、超大图子采样与 Exif 方向都由该库提供，应用内不再自己写缩放手势。已存/任务/首页「最近保存」点击图片记录进入全屏图片中心；同一普通库或保险箱内的全部已存图片按最新顺序排列，可左右翻页、点缩略图或通过按保存日期分组的三列网格快速定位。控制栏 3 秒后自动隐藏，轻点图片切换显示；提供保存、分享、详情、打开来源与确认删除。幻灯片可选 3/5/10 秒（默认并记住 5 秒），循环播放且在缩放、弹层或应用退到后台时暂停；播放期间保持屏幕常亮。详情通过 AndroidX ExifInterface 按需读取完整的可用 EXIF/XMP 标签，包括直接展示 GPS 信息；数据只在当前看图会话按文件路径与修改时间缓存，不上传、不持久化、不写日志。进入或翻到 Live Photo 时会在原静帧上自动循环播放配对的 MOV，也可手动停止/恢复；离开当前图片即释放播放器。分享会一次发送 JPG 与 MOV，另存文件夹、相册导出、重命名和删除也始终成对处理；删除后优先前进、末尾回退、最后一张退出。保险箱入口每次先走系统认证，且不与普通图片混排。
- **打包下载**：结果页在图片条目 ≥ 2 时出现「打包下载（N 张）」。它创建**一个**归档任务：逐张下载到任务目录（已完成的图在暂停/进程被杀后直接跳过），全部就绪后打成一个 ZIP（`images/001.jpg` 保序），校验条目数后落到应用私有目录，并复制到公共 `Download/拾影`（Android 10+ 走 `MediaStore.Downloads`，更低版本需要存储权限）。已存/任务里显示为「压缩包 · N 张」，点击交给系统「打开方式」，分享与「导出到文件夹」沿用既有能力。
- 私密记录（保险箱）不自动存入相册，图片同样如此；ZIP 固定进下载目录，不经过「下载后自动存入相册」开关。
- 看图器与打包下载的验收见 [图片下载验证](docs/validation/image-download-2026-11-16.md)：JVM 用例、模拟器设备用例与在线（opt-in）用例各自覆盖的范围与未覆盖边界都记在那份记录里。

## 合集解析（C3）

- 「我的 → 解析合集」打开后，粘贴**合集 / 频道 / 播放列表**链接会把整页条目都解析出来（默认关闭，最多 20 条），随后走既有的多选界面：逐条挑画质、批量入队，重复链接沿用既有重复检查。关闭时只解析链接指向的那一个视频——一个单视频链接不该悄悄变成二十条队列。
- 每个条目保留自己的 id 与来源网址，因此「下载第 2 条」下的就是第 2 条：已用本地两视频页面验证两条各自下到 10542 与 56341 字节、来源网址各自对应 `/one.mp4` / `/two.mp4`。
- 引擎侧只改了一处：`noplaylist` 现在由「X 帖子」或「应用显式要求合集」决定，`playlistend` 取自应用的 `playlist_limit`，上限与设置页文案共用 `EngineRequest.PLAYLIST_LIMIT`。

## 字幕下载与内嵌（C2）

- 解析结果里带字幕时，结果页会出现「同时保存字幕（中文）」的选项（只在该源真的有字幕、且选的是视频档位时出现）。打开后下载会把字幕**内嵌进视频文件**，离线播放也能用播放页的字幕开关打开它。
- 语言是应用挑的，不是逐条勾选：优先一个中文轨 + 一个英文轨（`zh-Hans` 优先于 `zh`），都没有时取源提供的第一个；**只有**在没有任何真实字幕轨时才使用自动字幕（机器生成的）。来源可能提供几十上百种语言，全量抓取会把一次视频下载变成字幕仓库。
- 字幕是引擎能力，所以请求字幕的任务会**改走引擎下载**，即使该档位本身是直链；否则会出现「下载成功但文件里没有字幕」的静默结果。
- 内嵌后字幕文件会被删除（一个任务只产出一个文件）；mp4 中的字幕轨以 `text/3gpp-tt`（mov_text）形式存在。

## 2026-11-19 体验收敛

- 「任务」页把失败与暂停收敛为「可继续」筛选，顶部「进行中 / 已完成 / 可继续」统计可直接点选；「清除已完成」只把完成项从任务列表归档，不删除记录、文件或「已存」内容，并可在 Snackbar 里撤销。私密完成项不再出现在任务页，进行中的私密任务只显示「保险箱内容」占位封面。
- 保险箱门禁统一到「打开记录」与播放器：从任务页、重复下载提示、通知等路径进入私密视频都会先走系统认证；首页重复提示不显示私密记录，任务卡不加载私密封面，完成通知仍不深链私密内容。
- 结果页不再把画质、音轨、字幕和打包藏在「更多下载选项」后面：画质与音轨直接展示，打包按钮在图片作品时直接可见，字幕只在来源真有字幕且目标是视频时出现。
- 「已存」卡片右上角新增「更多操作」入口，与长按菜单等价；页面新增可见的排序入口，滤镜按钮改为「筛选与排序」，排序不再只藏在弹层里。
- 首页「粘贴链接」成为真正的按钮，剪贴板为空时给出提示；「打开网页查找视频」只在输入框已有链接时出现，解析中可「取消解析」，不再出现看起来可点但实际禁用的灰色文字。
- 多选工具条的「完成选择」固定在右侧，批量动作在中间横向滚动；封面选择圈加深色外描边，浅色封面上也能看清。看图器底部动作提升到 48dp 触控区。
- 播放器在文件丢失时可直接「重新下载」或「重新解析原网址」，音频服务连接失败可「重试连接」；新增「手势说明」入口，双击、滑动、长按不再只能靠 README 发现。
- 站点专区改用与其他页面一致的自适应宽度；删除单条观看记录可撤销，清空记录先确认。设置页补充「下载文件在应用专属目录、卸载会删除」的说明；开启完成通知后，后台失败也会发一条通知，点击进入任务页查看原因。
- 首次完成下载后会提示一次文件去向，可在「我的」开启自动存入相册保留副本。

## 私有保险箱与应用锁（C4）

- 「已存」最左侧的「保险箱锁」是入口。点击后由**系统弹窗**验证身份：Android 11 及以上可要求生物识别或锁屏凭据，Android 10 以生物识别为主、可用锁屏凭据兜底，Android 9 及以下没有可用的系统弹窗（保险箱报告不可用，不会假装已锁）。验证通过才进入，按「锁定」立刻回到门外（不需要再次验证）。
- 保险箱是**独立视图**：普通列表永远不混入私密条目，解锁后也只显示私密条目——数量、分类下拉与批量操作全部只针对可见条目计算。
- 私密条目在下列位置一律不出现，或只显示占位文案「保险箱内容」：「已存」列表、首页「最近保存」、任务页的完成项、任务卡封面、下载完成通知与单任务通知的标题。进行中的私密任务仍出现在任务页以便暂停，但只有占位封面和脱敏标题。完成通知不再深链到播放页（只打开应用），否则点一下通知就绕过了门禁。
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
- **迁移已完成**：所有用户可见文案来自 `values/` 与 `values-en/`（各 995 条 key）。`domain`/`core`/`data` 不持有界面文案，只返回值、枚举或 key，由 `core/reasonKeyRes(key)` 单点解析；其余 116 处中文字面量是内部不变量、开发者日志、开发者诊断异常与持久化内容，逐条用 `// l10n-ignore: <理由>` 登记（守卫计数为 0）。

## 通用网址与网页辅助


粘贴 HTTP/HTTPS 网址或分享文案即可尝试解析。已知平台优先使用原有解析器，失败后进入 yt-dlp；X 推文即使原生解析成功，也会补充通用引擎找到的媒体和画质。自动解析失败时点击「打开网页查找视频」，在内置网页中登录或播放，再选择检测到的媒体资源。

内置网页的面板默认收起成一行结论（加载中 / 查找中 / 找到几项 / 没有找到），扫到候选时自动展开一次，也可以随时收起，浏览面积留给网页本身。候选按「同一份媒体」聚合：同一张图被站点以 PNG / WebP / JPG 或不同尺寸给出多个地址时只显示一行，带缩略图、尺寸与大小，格式在行内切换；视频按完整地址分别列出。页面级「重新解析」对已知平台直接走原生解析器（复用本机网页登录态），普通网页才用带 `Referer`/`User-Agent` 的网页会话引擎；原生解析失败且不是「需要登录」时会回退一次网页会话引擎，两者都失败时保留原生更具体的归因。图片候选按文件头识别（不只看扩展名）并直接构造图片下载，不经过 yt-dlp——网页图片没有对应提取器。

网页自己的「打开 App」推广弹窗（例如 X 的「See this post in the app」）会按已知文案隐藏，可在面板里关掉；被站点按 UA 拦住时还可以切「桌面版网页」或「在浏览器中打开」。这些只改页面呈现与 UA，不绕过登录、会员或 DRM。

MP4/WebM 直链继续断点下载；HLS、DASH 和分离音视频通过本地 Python、yt-dlp 与 FFmpeg 下载、合并并校验。任务页区分下载、合并和文件校验；只有校验完成才进入「已存」。不同格式按实际扩展名分享，MKV 等不适合相册的容器会提示使用文件分享。

「我的」显示实际引擎版本、X（Twitter）登录入口，并可清除网页登录态。登录态只保存在本机；默认匿名解析，不自动读取其他浏览器账户。X 的登录态可复用：登录一次后，X 解析在匿名路径都失败时会自动改用本机登录态重试；有效期由 X 的 `Set-Cookie` 决定，App 不设自己的过期时间，失效时解析会提示重新登录（详见下方「X（Twitter）解析」）。引擎随 APK 固定更新，不依赖解析服务器。

此功能面向点播视频，不保证所有网站可用；不支持 DRM、直播录制或整站采集。部分网站会限制 WebView 登录。详细架构、依赖校验和、16 KB 原生库修复与许可来源见 [通用视频引擎说明](docs/research/universal-video-engine.md)，当前通过及未验证项见 [实施验证记录](docs/validation/universal-video-2026-09-29.md)。

## 站点专区与 KUBB.TV 在线播放

解析页的「站点专区」是独立于通用粘贴解析的站点入口；「查看全部」进入站点中心，并显示最多 100 条独立的最近观看记录。站点由 `SiteSourceRegistry` 注册，页面只依赖稳定站点 ID、频道、域名白名单和能力声明，后续新增适配器无需修改主导航。

首个适配器为 KUBB.TV。页面顶部提供电影、电视剧、综艺、动漫、短剧、体育、纪录片、华人、游戏、新闻、娱乐、生活、音乐、时尚、科技和小视频共 16 个原生频道，频道内容仍由站点移动网页展示；电视直播不显示。站点浏览器只允许 HTTP/HTTPS 和 KUBB.TV 主域导航，拦截登录、账号、会员与支付路径，关闭文件/内容访问、混合内容、多窗口及第三方 Cookie。用户主动点击的外链交给系统浏览器，无手势跳转和弹窗不放行。

进入影片或带 `?id=` 的选集页面后，App 并行使用当前页面和 WebView 实际加载的 HLS/直链候选解析媒体；多个有效媒体会先选择画质。播放地址只保存在进程内，观看历史只保存可重新解析的规范页面 URL、标题、封面、时长和进度。进程重建导致会话失效时返回原站点页面重新解析，再从断点继续。

在线播放复用本地播放器的进度条、倍速、循环、轨道、比例、手势、全屏和画中画控制；依赖本地文件的截图、抽帧预览和文件分享不会出现。媒体请求逐次附加 Referer/User-Agent，并使用无 Cookie 的 OkHttp 数据源。播放器内「下载」把本次解析的原始 `DouyinVideo` / `VideoVariant` 交给既有下载器，因此仍保留 HLS、请求头、重复确认、暂停续传和下载历史语义。直播、DRM、登录/会员/付费内容继续明确拒绝，不尝试录制或绕过授权。

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
| 1 | 解析抖音、微博、快手、X、哔哩哔哩链接、下载视频、**支持选择分辨率** | `data/repository/MultiPlatformVideoRepository.kt`（平台路由）+ `data/remote/`（链接解析）+ `data/download/`（下载）+ `presentation/home/HomeScreen.kt`（分辨率选择 UI） |
| 2 | 记录下载历史 | `data/local/DownloadHistoryStore.kt` + `presentation/history/` |
| 3 | 打开原视频与对应作者主页 | `core/DouyinIntents.kt`（抖音优先唤起客户端，其余平台打开对应网页） |
| 4 | 断点续传 | `data/download/ResumableDownloader.kt`（HTTP Range + `If-Range`）+ `DownloadEngine`（偏移量落盘） |
| 5 | 播放视频、横屏全屏 | `presentation/player/PlayerScreen.kt`（标题栏与播放器内均可切换横屏；按视频比例显示，切换保留进度和暂停状态，返回键先退出全屏） |
| 6 | 画质显示文件大小 | `data/remote/VideoSizeProbe.kt`（后台读取 CDN 响应头；不可获取时明确显示大小未知） |
| 7 | 转存到相册 | `data/gallery/GalleryExporter.kt`（通过 MediaStore 复制已完成视频） |
| 8 | 已存内容支持瀑布流与列表切换，并记住布局选择 | `presentation/library/LibraryScreen.kt` + `core/AppPreferences.kt`（点击「我的影像库」右侧按钮切换；瀑布流按封面比例排列，列表显示封面、标题、作者和保存时间） |
| 9 | 已存内容长按常用操作 | `presentation/library/LibraryActions.kt`（两种布局均可长按播放、分享、保存到相册、删除；删除需再次确认，保存期间防止重复操作） |
| 10 | 输入页键盘避让与自动收起 | `presentation/MainActivity.kt` + `presentation/home/HomeScreen.kt`（输入框和解析按钮避让键盘；解析、完成键、粘贴、点击空白和离开页面时收起，保留输入内容） |
| 11 | X 推文多视频选择 | `domain/model/ParsedVideos.kt` + `presentation/home/`（每个视频独立选择画质；保留 `/video/N` 指定的媒体索引） |
| 12 | 微博动图下载格式 | `data/remote/WeiboJsonParser.kt`（同一动图可选微博提供的 MP4 转码或 GIF 原图；多张动图仍独立选择，MP4 进播放器、GIF 进看图器） |
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

# 指定 X 推文验收：解析 → 结果页 → 图片打包 → 看图器截图（产物在应用私有 files/x-evidence/）
adb shell "am instrument -w -e class com.videodown.douyin.XSpecifiedPostDeviceTest \
  -e x_post_url 'https://x.com/user/status/ID' \
  com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner"

# 图片/图文真实联网测试：直链图片 + 平台图文解析并打包（约数 MiB）
# 真实作品链接会过期，仓库不内置；平台图文另传 image_live_douyin / image_live_weibo / image_live_kuaishou / image_live_x
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.ImageLiveDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_images=true

# KUBB.TV 匿名公开内容在线验收：电影、电视剧、动漫、小视频各解析一条，并短时播放首条
# 默认跳过；站点结构、地区策略和媒体流量均可能变化。增加 live_kubb_download 会创建下载任务后立即取消。
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.KubbLiveDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_kubb=true

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

1. **匿名分享页（首选）**：视频链接先试 `GET https://www.iesdouyin.com/share/video/{aweme_id}/`，
   图文链接（含 `/note/`）先试 `/share/note/{aweme_id}/`，另一种再兜底。该页面为 SSR 渲染，
   正文内嵌 `window._ROUTER_DATA` JSON，无需签名即可拿到完整信息。
2. **签名 Web 接口**：`/aweme/v1/web/aweme/detail/`（在具备有效 Cookie/签名时可用）。
3. **旧版 v2 接口**：`/web/api/v2/aweme/iteminfo/`（仅作最后兜底）。

短链 `v.douyin.com/xxxx` 通过 HTTP 302 展开后再提取 19 位 `aweme_id`。

解析 JSON 时不写死字段路径，而是**广度优先搜寻找第一个「像 aweme」的对象**（同时含有 `aweme_id`
且具备 `video.play_addr` **或**非空 `images`）。图文作品的 aweme 没有可播放的视频，只看 `video`
会让 BFS 继续往下抓推荐位里的另一个视频——先认 `images` 就是为了不把错误的内容当成这条作品。抖音每年会数次调整 `_ROUTER_DATA` 的键名（`video_(id)/page`、`note_(id)/page`、
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

- 微博支持正文链接（`weibo.com/{uid}/{id}`、`m.weibo.cn/status/{id}`、`m.weibo.cn/detail/{id}`）及可展开的 `t.cn` 短链。通过匿名访客流程获取临时 Cookie，再读取正文媒体信息，不读取浏览器或账号 Cookie。画质来自 `playback_list`（过滤 JPEG 预览图与缩略图条）。正文的静态图片按 `pic_ids` 顺序成为独立图片条目，尺寸优先取 `original`，其次 `largest / large / bmiddle / thumbnail`。`type=gif` 且同时存在 `video` 时，同一媒体条目提供两个下载格式：微博 `video` 的 MP4 转码和 `original` 的 GIF 原图；前者兼容播放器，后者按图片媒体保存并保留动画，不在内容列表里重复成两条。**Live Photo**（`type=livephoto`）解析为一个成对媒体条目：静帧优先取 `largest`（它的 `original` 常被微博压到 1080），同时下载 `picture.video` 指向的原始 `.mov`。两份源文件均按原字节保存，不转码；只有 JPG 与 MOV 都完成并通过校验后任务才算完成。
- 快手支持 `kuaishou.com/f/...` 分享短链、`/short-video/{id}` 和移动端 `/fw/photo/{id}`。跟随分享页跳转并解析内嵌 JSON，按目标作品 ID 匹配，避免下载推荐列表中的其他作品。图集作品的 `photo.atlas` 给出相对路径、CDN 主机与每张尺寸，按列表顺序展开成图片条目。
- 两个平台复用画质选择、文件大小、下载/续传、历史、播放和转存相册流程；下载请求及续传保留各平台的 Referer。已有抖音历史记录仍可读取。
- 这些专用解析器提供 MP4 快速路径；未返回视频时会进入通用引擎。需要登录或验证时可打开网页辅助。直链地址失效会尝试重新解析一次，能够确认所选媒体和格式时刷新链接；断点不兼容则重下，身份无法确认时提示重新选择。

2026-09-29 在 Android 模拟器中完成两个指定链接的实际网络下载和视频轨道校验：微博 `RklMe8iOM` 提供 1080P/720P/540P，实测下载 540P（8,754,435 字节、约 67 秒）；快手 `X3uQi2UzJmqN2fq` 提供 720P，实测下载 75,746,030 字节、约 261 秒。此项为设备端接口测试，不代表手动点击 UI 或所有平台视频均已验证。

## X（Twitter）解析

- 主路径为匿名 Guest + GraphQL，失败或缺少可用媒体时自动尝试嵌入页数据接口。匿名会话只保存在内存中，过期后重建；**没有账号登录态时不会读取、也不会发送登录 Cookie**。
- **受限内容先试原生登录态**：X 对未登录请求会把敏感内容/年龄门禁的帖子回成 `TweetTombstone`（不返回任何媒体），此时浏览器之外只剩一条路——带上本机 WebView 的 `auth_token` + `ct0` 再请求同一条推文。解析顺序因此是「原生匿名 → 原生登录态 → 引擎登录态 → 引擎匿名」，登录态只在前一步明确要求登录时才使用；公开推文仍然完全匿名。
- 失败原因按类别给出，不再一律说成「DRM/直播」：`TweetTombstone` 归为需要登录并直接给「登录 X 后重试」入口；确认帖子里没有视频时给「这条内容没有视频」；随包引擎的 `No video could be found` 也归到同一类，而不是通用的「不支持」。
- 支持 `x.com`、`twitter.com`、移动域名和指向推文的 `t.co` 短链；短链只允许跳转到已识别的 X 域名。`/video/N` 与 `/photo/N` 都按媒体在推文里的位置选择（含图片），图文混排不会错选其他媒体。纯图片推文解析为图片条目：只接受 `pbs.twimg.com` 的 HTTPS 地址并请求 `?name=orig`，外站图片直接丢弃；引用的推文同样产出图片条目。
- 一条推文有多个视频时，结果页先选择视频，再选择该视频的画质；引用视频单独标识。下载记录保留各媒体的独立 ID、来源链接和请求头。
- 原生路径读取页面提供的 MP4（包括动画 GIF 对应的 MP4）；对同一推文继续用 yt-dlp 补充 HLS、分轨画质及视频卡片，按媒体 ID 合并候选并保留 `/video/N` 的选择。用户选择分片画质后由引擎下载与合并。受保护或要求登录的内容可在网页辅助中使用本机授权会话重新解析；实际能否访问取决于账号和网站限制。X 的动图 MP4 常带非 0 的 `ctts` flags（该字段应为 0），Android 平台解析器会因此整条轨道放弃、读不出任何轨道，下载完成校验改为退回容器结构判定（见「已知限制」）。
- **登录态复用**：在「我的 → X（Twitter）登录」里登录一次后，需要授权的推文（受保护、敏感内容等）会在匿名路径失败后自动改用本机登录态重试——先走原生 GraphQL（纯图片帖也是这条路径），再退回带 Cookie 的随包引擎；下载与失效重解析沿用同一份登录态，不需要再次登录。登录态由系统 WebView 的 `CookieManager` 持有，**有效期完全由 X 的 `Set-Cookie` 决定**：App 不设自己的过期时间、不在启动/升级/解析失败时清除它；Cookie 过期或被服务端拒绝时，解析给出 `x:session_auth_failed` 并提示重新登录。原生客户端只在这一次重试里附加 Cookie，不缓存、不落库；临时导出的 Cookie 文件只服务单次请求，用完即删，不进入下载记录、备份或日志。
- `XVideoHttpClient` 集中维护网页接口版本与参数；`XSyndicationToken` 的浮点转 36 进制行为有 JavaScript 对照测试。相关接口不是稳定的开发者 API，平台调整时仍需更新适配。

2026-09-29 验证：92 项单元测试通过；3 项 Android 设备测试通过，包括真实界面输入/解析/选择 1080P/保存、离线多视频切换及故障注入后的真实备用接口访问。指定推文 `2096916397947257248` 提供 1080P/720P/360P/270P，完整下载 17,618,131 字节，Android 读取到 1920×1080、约 38 秒、包含音频。截图和日志位于 `app/build/reports/x-device/`。本次未验证 X 视频的转存相册或全程播放。

新增的 X 补全单元测试覆盖原生 MP4 与引擎 HLS 合并、视频卡片追加和指定媒体保留；真实 X 引擎结果以设备联网用例 `XVideoDeviceTest#liveUnifiedXAddsEngineFormats` 单独验收。网页登录态按实际请求主机查询 Cookie，额外覆盖 `api.x.com`；真实账号登录仍需单独验证。直播录制、纯音频 Spaces 与 DRM 不在点播下载范围内。

## 哔哩哔哩（B 站，Phase F E1）

- 识别 `bilibili.com/video/BV…`、`/video/av…`、移动端 `m.bilibili.com` 与分享文案；显式 `?p=N` 会保留，`spm_id_from`、`vd_source` 等跟踪参数丢弃。链接先归一化成 `https://www.bilibili.com/video/BV…` 再交给随包引擎解析。
- `b23.tv` 短链逐跳展开：只跟随跳到 `bilibili.com` / `m.bilibili.com` / `b23.tv` 的 3xx，最终目标必须是单个视频页；跳到其它主机或 B 站的非视频页都按「无法展开」处理，不把开放重定向当成解析入口。
- 第一版只做单个视频页与分 P：番剧、直播、课程、音频、空间和频道列表直接显示「不支持」，不交给引擎。解析结果按 BILIBILI 标注，画质列表、下载、续传、已存与播放沿用既有流程。
- B 站画质来自 DASH：每个纯视频流与码率最高的音轨配成**同一个画质选择**（`格式+音频格式`），由随包引擎下载两路并合并成一个文件；合并完成后按 I2 的规则校验视频轨、音频轨与尾部完整性，缺轨或截断会失败而不是留下半个文件。DRM 格式不会出现在列表里，直播、付费与权限越权不尝试。
- 字幕只暴露可下载/内嵌的**文本**字幕：B 站引擎返回的 `danmaku`（弹幕 XML）与直播聊天、评论等非文本轨在解析层就被过滤，结果页与引擎请求都不会出现它们；文本字幕的下载与内嵌沿用既有 C2 路径。
- 「我的 → 网页与账号 → 哔哩哔哩登录」在应用内网页里登录，登录态由系统 WebView 持有，以 `SESSDATA` 判断是否登录（有效期由 B 站决定，应用不设过期时间）。解析时优先使用登录态，失败后退回匿名；两条路都要求授权时提示重新登录。只导出 B 站主机的 Cookie，不读取 Chrome 等外部浏览器账号，也不绕过大会员、付费或权限限制。
- 「解析合集」开启后由引擎枚举分 P，上限与其它站点一致为 20 条；关闭时只取链接指向的当前分 P。
- 验证与未覆盖边界见 [E1 验收记录](docs/validation/bilibili-e1-2026-11-17.md)：真实公开视频的匿名解析已跑通（链接不入库），多 P 枚举与真实 `b23.tv` 展开仍需专门链接复验。

## 断点续传实现

`ResumableDownloader` 的行为与 `curl -C -` 一致：

- 已有 `.part` 文件时发送 `Range: bytes=<n>-`，并带上首次响应记录的 `ETag` 作为 `If-Range`，
  远端文件变化时自动从头重下而不是拼出损坏文件；
- `206 Partial Content` → 追加写入；`200 OK`（服务端忽略 Range）→ 截断重写；
  `416 Range Not Satisfiable` → 判定本地已完整，直接改名为成品；
- 网络中断时保留已落盘的字节，记录 `PAUSED`（可继续），并对瞬时错误做 3 次指数退避重试；
  引擎任务（yt-dlp / HLS / DASH / 分轨）与直链共用同一套判定，冷却时间与重试预算落库；
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
- 图文/图集作品不再报「没有可下载的视频流」：抖音 `/note/`、微博多图、X 多图与快手 `atlas` 都会解析成图片条目；只有正文既没有视频也没有图片时才给出该提示。
- **X 的敏感内容/年龄门禁帖子必须先登录**：匿名接口对这类帖子只返回 `TweetTombstone`（不含任何媒体），此时解析会引导登录 X 后重试，而不是谎称「DRM/直播不支持」。登录后纯图片帖按原图保存；`TweetTombstone` 本身区分不出「未登录」「账号无权查看」还是「地区限制」，文案按需要登录处理，重试仍被拒时提示登录可能失效或账号看不到。
- 图片按**原图**保存，不做转码、压缩或去水印；看图器是否支持某种容器（如 HEIC/AVIF）取决于系统解码器，落盘与相册 MIME 仍正确。
- **有些 MP4 平台解析器读不了，但文件本身能用**：X 的动图 MP4 把 `ctts` 的 flags 写成 1（该字段应为 0），Android 的 `MediaExtractor` 会放弃整条轨道（`trackCount` 为 0，文件级 DRM 摘要直接抛 `IllegalArgumentException`），同一份字节在 ExoPlayer 里却能正常播放。下载完成校验在这种情况下退回纯 Kotlin 的 `data/download/Mp4Structure.kt`：盒子声明长度与文件一致、`moov` 里有任务要求的轨道就保存原字节；这一档不做 I2 的尾部样本检查，结构不完整（截断）仍按 `TRUNCATED` 失败。校验时的 DRM 判定也改为逐轨道标记，读不出的容器不再被误报成加密；平台异常不再落成「UNKNOWN + 空提示」，而是可读的校验失败原因。
- GIF 直链按图片保存并保留动画；微博「动图」若载荷同时给出 GIF 原图和 MP4 转码，结果页显示「选择下载格式」，默认仍选兼容性更好的 MP4，也可改选 GIF。站方没有返回 GIF URL 时只显示 MP4，不会在客户端把视频重新转码成 GIF。
- 图片中心只浏览仍存在于应用下载记录和私有文件目录中的原文件；删除下载记录不会连带删除用户此前另存到系统相册的副本。
- 打包下载的 ZIP 固定写入 `Download/拾影`；Android 9 及以下需要存储权限，未授权时只保留应用内副本并说明原因。用户只勾选了部分图片时，若签名地址过期，归档任务会按原清单失败并提示重新解析，不会把未勾选的图片悄悄塞进包里。
- 网页抓图只覆盖**页面已加载**的图片（`currentSrc`/`src`、`srcset` 的最大一档与资源计时），不抓 CSS 背景图与未触发的懒加载图；候选会按宽高/大小过滤图标，但无法保证过滤掉所有装饰图。同一份媒体在多个格式/尺寸地址下会被合并成一行（身份判定忽略 `format`/`name`/`w`/`h` 等纯格式参数），默认下载面积最大、其次格式质量最好的一份；实现见 `data/generic/MediaCandidates.kt`。
- **KUBB.TV 依赖公开网页结构而非私有签名 API**：站点改版、媒体 CDN 域名变化、地区限制或反自动化策略都可能让频道仍能浏览但解析暂不可用；此时会显示站点/解析错误，不会尝试登录、会员、付费、DRM 或直播绕过。在线播放会话仅在当前进程有效，重启后需返回观看记录重新解析最新地址。
- 快手图集依赖分享页内嵌 `atlas`；字段改名时需要更新 `KuaishouJsonParser`，与其它平台一样属于会随站点改版失效的适配点。
- 微博 Live Photo 会保存静帧和配对的 `.mov`，在应用内始终作为一条记录共同下载、续传、播放、分享、导出、重命名和删除；打包下载则写为 `images/NNN.*` 与 `livephotos/NNN.mov`。Android `MediaStore` 没有跨厂商统一的 Live Photo 配对字段，因此另存到系统相册后是 Pictures 与 Movies 中的两个原始媒体项，应用内的成对关系仍保留；动态 GIF 继续按既有路径存成 MP4。
- `ratio=` 派生档位属于兜底策略；若接口已提供完整 `bit_rate` 列表，则以官方档位为准。
- 合集解析最多 20 条，且**取决于各站 extractor 的能力**：需要登录、需要 JS 渲染或受地区限制的合集可能只返回部分条目，甚至与单视频一样只返回一条；实例化结果里只包含引擎能拿到直链的条目，直播、DRM 与私享内容会被过滤掉（不会以占位条目出现）。
- 合集解析的「整页枚举」只在引擎路径生效；被 `probeDirect` 直接识别的裸视频链接没有合集概念。
- 字幕同样只在引擎路径可用：被 `probeDirect` 直接识别的裸视频链接没有提取器上下文，结果页不会出现字幕选项。引擎侧的真实字幕可用性取决于站点（需要登录、只提供机器字幕、或字幕单独收费的源另说）。
- 字幕语言由应用挑选（最多一个中文 + 一个英文），不提供逐语言勾选；最多保留两条轨，不做双语以外的多语内嵌。
- 音频档位永远不取字幕；任务卡片也不显示「含字幕」，只能从播放页的字幕开关看出。
- **保险箱需要 Android 9 及以上**，且设备已设置锁屏或已录入指纹/人脸；否则入口会说明「请先在系统中设置锁屏密码或指纹」，而不是假装上锁。更低版本没有可供普通应用使用的验证弹窗，因此不支持。
- **导出回执随版本升级才有**：旧版本保存过的内容没有回执，升级后第一次保存同一条会再写一份；回执按记录 id 保存，删除记录不会删除已经另存到相册的副本，残留回执 90 天后清理；卸载重装会丢掉回执，之后可能重复保存一次。部分 OEM 相册把删除做成回收站、MediaStore 仍保留行时，会继续判为「已保存」。
- **系统验证弹窗本身没有被自动化测试覆盖**：测试用可替换的实现分别驱动「通过」与「拒绝」两条路径，验证的是门禁两侧的行为（隐藏、显示、通知与导出不外泄）。
- **设置备份里的「已存顺序」按记录 id 匹配**：同一台设备导出再恢复能把手动排好的顺序带回来；换机恢复时 id 一个都对不上，顺序保持原样（下载记录本身不随这份备份走）。
- **保险箱保护的是「看见」，不是「取出」**：文件仍在应用私有目录里，没有额外加密；有 root 或调试备份能力的人仍能拿到文件。
- 任务页不再列出私密完成项；进行中的私密任务只显示「保险箱内容」占位封面，且不提供播放/分享。任务页搜索仍会匹配进行中私密任务的隐藏标题，但卡片继续显示占位文案。
- 保险箱内手动「保存到相册」与「分享」仍然可用——门禁已经放行，这是用户的明确操作。
- **视频号分享链接不支持解析**（`weixin.qq.com/sph/…` 及其跳转后的 `channels.weixin.qq.com`）：那个页面只给标题、作者和播放数据，**播放地址在微信客户端内动态签发**，网页里没有可直接下载的地址；随包 yt-dlp 里也没有任何 weixin/wechat 提取器（实测逐个字符串搜过）。解析必然失败，所以失败时给的是专门的说明（「这是视频号链接：内容只能在微信里播放…」），不再提示「打开网页登录或播放后查找」——那条路对这个网站是死的；若已从别处拿到直链，粘贴直链可以正常下载。判定入口是 `VideoLinkExtractor.isWeChatChannelsLink`（`mp.weixin.qq.com` 的公众号文章不在此列）。
- **X 登录态复用只覆盖 X**：其他站点仍需在内置网页里用登录态手动重新解析；也不从外部浏览器导入 Cookie。
- **一次只能保存一个 X 账号**（系统 WebView 的 Cookie 空间）。切换账号要先「清除网页登录态」再登录，而该清除是整站级别的——`CookieManager` 没有可靠的按站点删除接口，所以不提供「只退出 X」。
- **真实 X 账号登录与真实受保护推文下载未自动化**：设备用例用本地写入的 Cookie 与 `about:blank` 网页验证识别、导出、界面入口与「登录后重试」；真实登录、账号权限、站点限流仍需手动验证（步骤见验证记录）。
- **仅登录态路径可用时，多视频推文的 `/video/N` 可能回落到第一条**：登录态重试走随包引擎，引擎按整页枚举媒体，原生路径能识别的媒体位置在这里不保证。
- **服务端拒绝登录态时无法区分成因**：登录已过期、账号没有查看权限都会表现为登录/授权要求，所以提示把两种可能都写出来，并指向重新登录，而不是断言「已过期」。
- **网页解析面板一定会给一句结论**：页面加载中、查找中、找到几项、一个都没有各有文案；视频号页面换成上面那句说明。候选判定见 `BrowserMediaDetector`，聚合见 `MediaCandidates`，状态由纯函数 `browserPanelStatus` 决定。扫描 12 秒仍未 settle 会强制给结论，避免永远停在「查找中」。
- **网页上的「打开 App」弹窗隐藏是尽力而为**：只对 `role="dialog"` / `aria-modal` / X 的 `data-testid="sheetDialog"` 且文案命中已知几句话的元素生效，站点改版或换文案后就不再隐藏（此时用「桌面版网页」兜底）；不碰登录墙、回复框等其它弹窗，也不会拦截任何请求。
- **「桌面版网页」与「隐藏 App 推广弹窗」只在本次面板会话内有效**，不进偏好设置；切换桌面版 UA 会重载页面，可能丢掉当前页面的滚动位置。
- **候选缩略图只对不超过 4 MiB 的图片加载**，更大的或拿不到尺寸的显示格式占位；缩略图失败不影响按原地址下载。
- **完整性校验不逐帧解码**：只要求近结尾处仍有样本，不检测文件中间的空洞，也不核对声明时长与实际样本数的细微差异；容器没有声明时长（直播、未知时长流）时只做首样本校验。**缺音轨的重试收益未量化**：分轨合并少了音轨会按可重试的网络类失败处理，站点本身就只提供纯视频流时会在三轮重试后失败。
- **B 站只支持单个视频页与分 P**：番剧、直播、课程、音频、空间/频道列表按链接形态直接拒绝。登录态只用于账号本来就能在网页里播放的内容，不绕过大会员、付费或地区限制；`SESSDATA` 失效时只在解析失败后提示重新登录，不做主动探测。弹幕等非文本字幕已在解析层过滤，但 B 站真实字幕的下载/内嵌未实测；真实账号登录与登录后的画质差异、多 P 合集、真实 `b23.tv` 展开仍需人工或专门链接验证。
- **引擎的 `Retry-After` 依赖 yt-dlp 的异常对象**：能从 `HTTPError.response` 取到头时按服务端时间等待，取不到（例如失败被包装成纯文本错误）时只能回落指数退避；单个引擎进程内的重试由应用控制，多个引擎进程之间的带宽与冷却协调仍属后续项（I5）。
- **不要用 `embedsubtitles` 参数**：它是 yt-dlp 的命令行专用开关，`YoutubeDL` 会直接忽略它（已实测：下载成功、无报错、文件里没有字幕轨）。引擎里必须显式添加 `FFmpegEmbedSubtitle` 后处理器。
