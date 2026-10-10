# 英文名 Keepsake 验证（2026-10-10）

## 结论

中文名仍是「拾影」，**英文名定为 Keepsake**。

## 为什么是这个名字

- **贴合「拾影」的语义**：拾 = 拾起、收下；影 = 影像。`keepsake` 是英语里的常用词，指「留下来作纪念的东西」——正是「留住心动瞬间」那件事，也正对上应用里的「已存」与保险箱。
- **贴合产品与气质**：它是暖色、安静、私人的收藏，而不是「下载器」式的工具词；奶白暖橙深褐的品牌配色与这个词的温度一致。
- **英文用户可用**：真词、两段音节（keep-sake）、好读好记好拼；`Shiying` 对英文用户既读不出也无信息量。

## 已确认的名称占用（选名前查过）

| 候选 | 占用情况 | 结论 |
| :--- | :--- | :--- |
| **Keepsake** | Play 上有一个活动相册应用 [Keepsake（Dustworks）](https://play.google.com/store/apps/details?hl=en_US&id=com.keepsakeapp.app)（50+ 下载）与一个已下架的纪念应用；iOS 有 [Keepsake AI](https://apps.apple.com/us/app/keepsake-ai/id1672180624)（U 盘备份）。**没有视频下载器**。与知名照片保险箱 Keepsafe 只差一个字母 | 可用，但名字不独占；商用前建议查第 9/42 类商标 |
| ReelKeep | [就是一个 Instagram / X 视频下载器](https://reelkeep.en.uptodown.com/android)，同品类直接撞名 | 不用 |
| ReelVault | [Instagram Reel 下载器](https://github.com/shaiknagapasha/reelvault)、Play 上的「保存 Reels」应用、iOS 素材库 | 不用 |
| ReelPick / ReelPicks | [Play 上 50k+ 安装的 ReelPicks Home](https://appinsider.io/android/app/com.rpick.hme.dram)、多个影视发现项目 | 不用 |
| Glean | 同名产品很多（企业搜索 Glean、学生笔记应用） | 不用 |

搜索结论很清楚：`Reel* + 动词` 与 `Keep*` 这两个格子在本品类里已经挤满，所以取了词义更靠「留住」的 Keepsake。

## 实现范围

- `app/src/main/res/values-en/strings.xml` 共 **9 处**：
  - `app_name`（桌面标签 + 解析页标题）；
  - 英文文案里的品牌名：`settings_backup_invalid`、`settings_language_body`、`settings_about_title`、`settings_about_body`、`settings_dynamic_off`、`settings_about_row`；
  - 导出文件名：`backup_links_file_name`（`keepsake-links.txt`）、`backup_settings_file_name`（`keepsake-settings-backup.json`）。
- README 标题与「语言与文案」章节补记英文名与不改动的标识清单。
- **不随品牌名改动的标识**（改了会丢数据或换资源）：偏好文件名 `shiying_preferences`、通知深链 scheme `shiying://`、MediaStore 目录 `Movies/拾影`、`Music/拾影`、图标资源前缀 `ic_shiying_*`、链接清单格式标识行 `# 拾影链接清单 v1`、`values/` 中文名 `拾影`。

## 验证

- 构建：`./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL；JVM **391 项通过，0 失败**（含 `LocalizationGuardTest` 的中英 key 一一对应）。
- 桌面标签：`aapt2 dump badging` 读出 `application-label:'拾影'` 与 `application-label-en:'Keepsake'`（`-en-AU/CA/GB/IN/XC` 同为 Keepsake），也就是英文系统下桌面图标下面是 Keepsake（`evidence.txt`）。
- 应用内：把应用语言设为 `en` 后，解析页标题渲染 `Keepsake`，设置页出现 `About Keepsake`（`emulator-parse-header-keepsake.png`、`emulator-settings-en.png`、`emulator-settings-en.xml` 的 `uiautomator` 节点文本）。
- 英文资源里 `Shiying` / `shiying` 已为 0 处（`evidence.txt` 末尾两条 grep）。

## 未覆盖边界

- **商标与商店名称可用性没有查证**：本文只做了公开搜索，没有查商标库（第 9/42 类）也没有上架 Play，同名应用存在（见上表）。
- **图标、设计源文件仍叫「拾影」**（`docs/design/shiying-icons/`、`ic_shiying_*`）：品牌图标与设计稿未随英文名重做，桌面图标本身没有文字，不影响显示。
- **`Movies/拾影` / `Music/拾影` 仍是中文目录名**（既有决定：路径是数据，跟随语言会让用户切语言后「找不到文件」）。英文用户会在相册里看到这个目录名；要改需要迁移已导出的文件，本轮没动。
- 英文文案由本次一并替换品牌词，**未经母语者校对**（与既有英文文案同一状态）。
