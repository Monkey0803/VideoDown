# AGENTS.md

拾影 (VideoDown) is a single-module Android video downloader: Kotlin + Jetpack Compose, package
`com.videodown.douyin`, `minSdk 26` / `targetSdk 36` / `compileSdk 36`, AGP 9.4.1, JDK 17. Sources
live in `app/src/main/java/com/videodown/douyin/` in four layers — `core/`, `domain/`, `data/`,
`presentation/` — wired by a hand-rolled service locator (`core/ServiceLocator.kt`). Features, build
detail, per-platform parse strategy and known limits live in `README.md` (Chinese). This file is the
agent contract for *changing* the repo, not a description of the app.

## Build & test

Fast path first:

```bash
./gradlew testDebugUnitTest                  # 41 JVM tests, no device needed
./gradlew assembleDebug                      # app/build/outputs/apk/debug/videodown-arm64-v8a-debug.apk
./gradlew assembleRelease                    # unsigned when keystore.properties is absent — expected
./gradlew assembleRelease -Pabi=universal    # add x86_64 so an emulator can install it
./gradlew installDebug
./gradlew connectedDebugAndroidTest          # 41 device tests; live_* groups skip unless opted in
```

The default ABI is `arm64-v8a`; `-Pabi=` also accepts `arm32`, `x86`, `x86_64`, `universal`.
`README.md` keeps the long-form, annotated build notes.

Online acceptance tests are opt-in and cost real bytes, so they never run by default:

| Flag | Test class | Downloads |
| :--- | :--- | :--- |
| `live_platforms` | `SocialVideoLiveDeviceTest` | ~81 MiB |
| `live_x` | `XVideoDeviceTest` | ~17 MiB |
| `live_x_engine` (+ `live_x_engine_download`) | `XVideoDeviceTest#liveUnifiedXAddsEngineFormats` | varies |
| `live_weibo_gif` | `WeiboGifDeviceTest` | ~865 KiB |

Pass them as `-Pandroid.testInstrumentationRunnerArguments.<flag>=true` alongside
`-Pandroid.testInstrumentationRunnerArguments.class=<class>`; exact invocations are in `README.md`.

## Architecture hard rules

- **`domain/` stays framework-free.** No `import android.*` / `androidx.*`, Compose or OkHttp under
  `domain/` — it is plain Kotlin precisely so the JVM suite can cover it. Verified current state:
  zero such imports. Keep it that way.
- **`ServiceLocator` is the composition root.** Presentation reads repositories and singletons from
  it (`ServiceLocator.videoRepository`, `.downloader`, `.preferences`, …) and never constructs a
  repository itself. Direct use of `data/` leaf helpers from presentation is existing practice and
  fine: `VideoSizeProbe`, `VideoLinkExtractor`, `GalleryExporter`, `DownloadCompletionNotifier`,
  `BrowserMediaDetector`, `AudioPlaybackService`, `AppUpdateChecker`, `UpdateCheckResult`.
- **No new `getSharedPreferences` calls, no new preference files.** Settings go through
  `core/AppPreferences.kt` (`shiying_preferences`) and `data/playback/PlaybackPreferences.kt`
  (`playback_preferences`). Those two are the only raw accesses — don't add a third. Keep
  `PlaybackPreferences(context, name)`'s `name` parameter; device tests rely on it for isolation.
- **No new raw logging.** Don't add `android.util.Log` or `print()` in new code. Existing debt is
  confined to five files — `DownloadEngine`, `DownloadService`, `ResumableDownloader`,
  `DownloadHistoryStore`, `DouyinRepositoryImpl` — which may be changed but not extended. When new
  code genuinely needs logging, introduce one facade first and call that.
- **Log privacy defaults to private.** Ids, URLs and file paths may be logged; clipboard text and
  downloaded media content must not. (Clipboard detection is in-flight work — `docs/TODO.md` A1.)
- **No string-matching on platform.** Branch on the `VideoPlatform` enum
  (`DOUYIN` / `WEIBO` / `KUAISHOU` / `X` / `GENERIC`), as the domain models already do
  exhaustively; never compare against `"douyin"`-style literals.

## Tests & device verification

Two suites run in parallel and neither replaces the other:

- `app/src/test/` — 41 JVM tests, no device. `testOptions.unitTests.isReturnDefaultValues = true`
  makes Android framework calls *silently return stub defaults* instead of failing, so anything
  depending on real framework behavior belongs in `androidTest`, not here.
- `app/src/androidTest/` — 41 device tests. Reset shared state (preferences, `ServiceLocator`
  singletons) between tests.

`live_*` groups being skipped by default is deliberate cost control, not quarantined flakiness —
don't "fix" it by enabling them.

A work item counts as complete only when all three exist: a JVM test, a device test, and evidence
under `docs/validation/`. Uncovered boundaries must be reported as uncovered; never substitute an
adjacent passing result. `docs/TODO.md` holds the exact checkbox rules.

## Release & signing

Signing is opt-in: without `keystore.properties` the release build is unsigned on purpose, so a
clean clone builds with no key of your own and no credentials.

- `keystore.properties` (copy from `keystore.properties.example`) and `keystore/*.jks` / `*.keystore`
  are git-ignored — never commit signing material.
- `keystore/debug.keystore` exists on developer machines but is **not** committed (it matches
  `*.keystore`), so a fresh clone must supply it before `assembleDebug` / `installDebug` succeed.
- Bump `versionCode` / `versionName` in `app/build.gradle.kts`.

## Repo boundary

`git ls-files` is the authority on what belongs here. Local-only, never committed: `app/build/`,
`build/`, `.gradle/`, `.kotlin/`, `.idea/`, `.tooling/` (local dependency builds and
device-verification artifacts), `local.properties`, signing keys. Tracked design and asset tooling:
`designs/`, `docs/design/`, `scripts/generate_shiying_icons.py`, `tools/`. `.gitattributes` pins
`gradlew.bat` line endings and whitespace inside upstream third-party license/asset text — leave
those entries alone.

## Docs & housekeeping

- `README.md` — the hub: features, build, architecture, per-platform parse strategy, known limits.
- `docs/TODO.md` — unimplemented work only, with checkbox semantics and a strict completion bar.
- `docs/validation/` — acceptance evidence; `docs/research/` — investigations;
  `docs/function-backlog-*.md` — historical, not maintained.
- Touch `data/remote/` or `data/generic/` parsing and the README 「已知限制」 section must follow.

## Don't "fix" these

Each looks wrong and is deliberate; `app/build.gradle.kts` carries the full story in comments:

- `ndk.abiFilters`, **not** ABI splits: with splits enabled, `testDebugUnitTest` next to
  `assemble*` intermittently fails inside AGP's `PackageAndroidArtifact$IncrementalSplitterRunnable`.
- `buildToolsVersion = "36.1.0"` pinned together with `android.builder.sdkDownload=false`: builds
  must use exactly the installed SDK and never reach out for another.
- `base.archivesName` tags artifacts `videodown-<abi>` so an arm64 APK cannot be mistaken for a
  universal one.
- `applicationIdSuffix = ".debug"` on debug so debug and release install side by side.

## Copy & comments

KDoc and code comments are in Chinese, and user-facing copy is Chinese. There is **no i18n layer**:
`res/values/strings.xml` holds about six strings, most UI copy is hardcoded in Kotlin, and only
`values/` and `values-night/` resource directories exist. Don't introduce a localization framework
speculatively; match the existing wording and style.
