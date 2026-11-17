# 导出幂等化验证（I3，2026-11-17）

## 结论

I3 已完成实现与自动化验证：保存到相册（视频 / 音频 / 图片 / Live Photo）与打包 ZIP 写入公共
下载目录时，按「记录 id + 源文件指纹 + 目标 URI」保存导出回执；再次导出先验证目标仍然存在，
存在时报告「已保存」并只在用户明确选择后再写一份；用户从系统相册或下载目录删除目标后回执
自动失效。Live Photo 的静帧与 MOV 按事务写入与记录，任何一步失败都不会留下半套回执。

## 实现范围

- `data/gallery/ExportReceipt.kt`（新增，纯 Kotlin）：`ExportReceipt`（记录 id、指纹、目标列表、
  写入时间）与 `ExportReceipts`。判重要求四个条件同时成立：同一条记录、指纹一致的源文件、
  至少一个目标、以及**每个**目标仍然存在；Live Photo 少一半即整对失效。
  指纹 = 每个文件的「名称 : 大小 : 修改时间」，不做全文件哈希，几 GB 的视频不会因为一次
  「保存到相册」在前后台被整份读一遍，同时能识别「同名不同内容」。
- `data/gallery/GalleryExportReceiptStore.kt`（新增）：单 JSON 文件、`.tmp` + 原子改名；
  目标是否存在由构造时注入的 lambda 判断，因此存储本身不依赖 Android 框架；
  `find` 发现目标已删或指纹已变时顺手清掉失效回执；90 天没用过的回执会被清理；
  回执文件损坏时按空处理，不让「保存到相册」失败。
- `data/gallery/ExportTargetLookup.kt`（新增）：MediaStore 条目按 `_ID` 查询确认存在，
  Android 9 及以下的公共目录按文件路径确认。
- `data/gallery/GalleryExporter.kt`：返回 `GalleryExportOutcome.Saved / AlreadySaved`；
  `replaceExisting = true` 才跳过回执再写一份。静帧与 MOV 一起插入、一起记回执，
  任一步失败删除本次已创建的条目（原来的实现只会在 MOV 失败时删静帧，现在覆盖回执写入失败）。
- `data/download/ArchivePublisher.kt`：ZIP 发布同样先查回执，目标仍在时不再重复写，
  但仍按「已保存」报告给完成通知。
- `presentation/library/LibraryActions.kt`：保存到相册遇到 `AlreadySaved` 时弹出确认框
  「已经保存过 / 再保存一份」，确认后才以 `replaceExisting = true` 再写。
- `presentation/player/PlayerScreen.kt`、`presentation/viewer/ImageViewerScreen.kt`：
  `AlreadySaved` 时提示「已保存，未重复写入」，并在同一提示条上给出「再存一份」动作。
- `core/ServiceLocator.kt`：`exportReceipts` 单例（`filesDir/export-receipts.json`，
  目标存在性走 `ContentResolver`），`galleryExporter` / `archivePublisher` 共用它。

## 自动化证据

### JVM

- `./gradlew testDebugUnitTest --rerun-tasks`：**607 个用例通过**。
- 新增 `ExportReceiptTest`（9 例）：指纹由名称 / 大小 / 修改时间决定；Live Photo 指纹覆盖两个
  文件且顺序稳定；同记录 + 同指纹 + 目标存在才算已保存；换记录、换内容、目标被删、目标列表为空、
  Live Photo 少一半都判为失效；JSON 往返。
- 新增 `GalleryExportReceiptStoreTest`（7 例）：回执落盘并被新实例读回；目标删除后 `find` 返回
  null 且顺手清掉条目；源文件变化同样失效；Live Photo 少一半失效；`clear` 只清指定记录；
  90 天过期清理；损坏文件按空处理且后续仍能正常写入。

### 设备（Android 17 / API 37 模拟器 Pixel_10_Pro，arm64）

新增 `GalleryExportReceiptDeviceTest` **4/4 通过**（真实 MediaStore）：

1. 同一图片连续保存两次：第二次返回 `AlreadySaved`、相册里仍然只有一条；从系统相册删除目标后
   第三次返回 `Saved` 并写入新条目；再把源文件追加内容后保存，得到第二份内容不同的副本
   （同名会被 MediaStore 自动改名，用例按去掉扩展名的前缀统计）。
2. Live Photo：一次写入静帧 + MOV 两条并记下两个目标；第二次返回 `AlreadySaved`；只删除 MOV
   后整对回执失效，下一次把静帧与 MOV 都换成新的目标。
3. MOV 容器不受支持时整次导出失败，静帧被回滚删除，回执为空（不留半套）。
4. 打包 ZIP 连续发布两次只保留一份公共下载条目；从下载目录删除后重新发布会补一份。

受影响的既有设备类另行复跑：

- `ImageGalleryDeviceTest`、`ImageArchiveDeviceTest`、`LibraryActionsDeviceTest`：通过。
- `BatchFeaturesDeviceTest`：5/7 通过；失败的两条
  （`batchDownloadKeepsIndividualQualityAndSkipsDuplicates` 找不到「360P」节点、
  `sortingPickerAppliesSelectionAndDismissKeepsCurrentQuery` 的「文件最大」chip 不可见）
  在改动前的基线 `615c104`（仅补上缺失的版本目录条目以便构建）上以**完全相同**的方式失败，
  与本轮改动无关，属于既有失败项。同类的批量导出用例
  `platformFilterAndListLayoutRetainSearchAndBatchExportCopiesAllSelections` 通过。
- `AutoGalleryDeviceTest`：因「系统通知未启用」前置断言失败，同样在基线复现，属模拟器环境前提，
  与本轮无关。

### 构建

- `./gradlew compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin`
  通过；`assembleDebug`、`assembleRelease` 通过（未配置签名时 Release 保持未签名）。

## 未覆盖边界

1. **升级前的旧导出没有回执**：从旧版本升级后第一次保存同一条内容会再写一份，之后才按回执判重。
2. **批量「保存到相册」不逐项提示**：回执说已保存的条目按成功计入批量结果，不弹确认框；
   逐项「再存一份」只在单条操作（已存操作面板、播放页、看图器）里提供。
3. **回执按记录 id 保存**：删除记录不会删除用户已经拿到的相册副本；残留回执在 90 天后清理。
   卸载重装会丢掉回执，之后可能重复保存一次。
4. **Android 9 及以下**：回执目标是公共目录里的文件路径，删除目标按文件是否存在判断；
   该分支只在代码层覆盖，未在 API 26/28 设备上实跑（本轮只有 API 37 模拟器）。
5. **系统相册/下载目录的「回收站」行为**：部分 OEM 相册删除后先进入回收站、MediaStore 仍有行，
   此时回执会继续判为已保存；这一差异未在非原生相册上验证。
6. 平板 / 折叠屏未验证；本轮只跑了手机模拟器。
