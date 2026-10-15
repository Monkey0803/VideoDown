# F-Droid 主仓库预检（2026-10-15）

目标是评估官方 `f-droid.org` 主仓库收录；本轮没有提交 fdroiddata 元数据、申请审核或发布安装包。检查对象为 `main` 的 `0496487`（1.2.0 / versionCode 7）及本轮未提交的许可证说明。开始前工作区另有未跟踪的 `google-play-precheck-2026-10-15.md`，本轮未修改它。

## 已确认

| 项目 | 结果与边界 |
| --- | --- |
| 源码可见性 | `gh repo view Monkey0803/VideoDown --json visibility,isPrivate,url` 返回 `PUBLIC`、`false`。先前“私有仓库”的记录已经过时。 |
| 自有代码许可 | 本轮新增根目录 `LICENSE`，全文与现有的 GPLv3 许可文本逐字节相同，并在 README 声明自有源码使用 `GPL-3.0-or-later`。这不替第三方代码或素材重新授权。 |
| Gradle 运行时依赖 | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --offline` 可解析；直接依赖中没有 Firebase、Play Services 或广告 SDK。依赖名称扫描不能替代许可证审计。 |
| APK 中已知非自由类 | 使用 `fdroidserver 2.4.5` 的 `fdroid scanner app/build/outputs/apk/release/videodown-universal-release.apk --verbose`，退出码 0，没有报告命中。扫描使用本机 Android SDK 的 `dexdump`；它不是源码构建或收录通过证明。 |
| 干净源码扫描 | 对 `git archive HEAD` 解出的隔离副本执行 `fdroidserver 2.4.5` 的 `scan_source`，得到 **13 项致命命中**：`org.gradle.toolchains.foojay-resolver`、内置 yt-dlp 二进制、10 个 libwebp `.so`、`docs/design/shiying-icons/shiying-icon-kit.zip`。扫描器会删除识别出的 Gradle 启动脚本和 JAR，因此只能在可丢弃副本上运行。 |
| 签名选择 | 允许 F-Droid 独立签名。现有 GitHub 安装版和未来 F-Droid 版即使包名相同，也不能在签名不同的情况下直接覆盖升级。 |

扫描操作记录：首次直接对工作目录调用 `scan_source` 时，工具清理了它识别到的 Gradle 启动文件和若干被忽略的本地 APK；这次结果被构建缓存污染，不计入上表。跟踪的启动文件已恢复，公开的 v1.1.0、v1.1.1、v1.2.0 Release APK 已从 GitHub 重新下载，SHA-256 与原验证记录一致；当前 universal release APK 和 arm64 debug/test APK 已重新构建。本地旧版预发布 APK 与旧调试验收 APK 没有公开副本，尚不能按原校验和恢复，不能再把它们作为现存附件引用。有效的 13 项源码扫描来自隔离副本。

## 收录前必须解决

1. **核对完整许可和素材权属。** 目前引擎附带 `youtubedl-android` GPL-3.0、yt-dlp Unlicense/第三方许可、libwebp BSD 文本；图标及 `designs/` 素材尚无逐项来源和再分发许可清单。还需审计 Gradle 传递依赖及 Python、QuickJS、FFmpeg 内嵌组件，并为发行物提供对应源码和许可说明。根目录许可证只覆盖有权授权的项目自有内容。
2. **证明预编译资源可被接受。** `app/src/main/assets/engine/yt-dlp` 为约 2.9 MiB 的发行包；另有 10 个预编译 libwebp `.so`，已提供固定源码校验和和 `tools/build-webp-16k.sh`，但尚未在 F-Droid 构建环境重建并对照。这 11 项均被源码扫描直接命中。`youtubedl-android` 0.18.1 的 Maven AAR 还内含 Python、QuickJS、FFmpeg 和另一份 yt-dlp 包。需要为这些二进制核对源码、构建参数和依赖许可；不能用 APK 扫描结果代替源码扫描。
3. **核对 FFmpeg 构建。** 本机缓存的 0.18.1 arm64 FFmpeg AAR 中，`libffmpeg.so` 可读配置含 `--enable-gpl --enable-version3`、`--disable-libfdk-aac`，未见 `--enable-nonfree`；内置库还含 x264、x265 等。应按实际构建配置确定许可、对应源码与重建方法，其他 ABI 及内嵌库也要核对。本项不能仅凭这一段可读字符串判定全部二进制合规。
4. **建立 F-Droid 构建配方并消除源码扫描命中。** 目前没有 `.fdroid.yml`、fdroiddata 配方或 `fastlane/metadata/android/`。foojay 工具链解析插件被扫描器标为 usual suspect，F-Droid 构建需固定 JDK 而不依赖此插件；设计 ZIP 不参与 APK 构建，可在配方中排除。然后在干净的 Linux/F-Droid 环境按公开 tag 构建，确认 AGP 9.4.1、Gradle 9.7.1、SDK/Build Tools 36.1.0 和全部依赖可用。目前只完成 macOS 本地 Gradle 依赖解析、现有 APK 扫描和隔离源码副本扫描。
5. **制作 F-Droid 发行行为。** 当前更新页可读取用户自定义 GitHub 清单并打开外部 APK 链接。F-Droid 版应由 F-Droid 客户端管理应用更新，界面说明和偏好迁移需要单独处理；GitHub 版保留原行为。引擎已随 APK 固定，不在线下载可执行更新。渠道版本完成后按仓库约定补 JVM、设备测试和 `docs/validation/` 证据。
6. **准备商店元数据。** 补中文/英文短文案、完整介绍、截图、图标与 versionCode 对应的更新说明；按 F-Droid 指南建立上游元数据，再提交 fdroiddata 审核。提交前复核下载站点的使用边界与 F-Droid Anti-Features 标记。

## 发行与升级决定

- 首轮按 F-Droid 自有签名推进，减少可复现开发者签名的前置工作；这不意味着可以直接提交当前 APK。正式发行前须在真机验证安装、更新和数据保留。
- 已安装 GitHub 签名版的用户无法直接安装不同签名的同包名 F-Droid 版；卸载重装可能删除应用私有数据，应在迁移说明中明确，并验证现有导出/备份实际覆盖范围。若将来要求原地迁移，再单独评估 F-Droid 的可复现构建与开发者签名发布方式。

## 依据

- [F-Droid 收录政策](https://f-droid.org/eu/docs/Inclusion_Policy/)
- [提交快速指南](https://f-droid.org/pl/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid 可复现构建和开发者签名](https://f-droid.org/en/docs/Reproducible_Builds/)
- [FFmpeg 许可说明](https://ffmpeg.org/legal.html)
