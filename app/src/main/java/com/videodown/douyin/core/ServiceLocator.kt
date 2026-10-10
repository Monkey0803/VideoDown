package com.videodown.douyin.core

import android.content.Context
import com.videodown.douyin.data.generic.*
import com.videodown.douyin.core.AppResult
import kotlinx.coroutines.runBlocking
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.download.WifiDownloadNetworkPolicy
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.DownloadService
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.DouyinApiClient
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.data.repository.DouyinRepositoryImpl
import com.videodown.douyin.data.repository.MultiPlatformVideoRepository
import com.videodown.douyin.domain.repository.DouyinRepository
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled service locator.
 *
 * The dependency graph here is tiny and fully static, so a container of lazy singletons is
 * clearer (and much faster to build) than pulling in an annotation processor.
 */
object ServiceLocator {

    private lateinit var appContext: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appCoroutineScope: CoroutineScope get() = appScope

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val playbackRepository by lazy { com.videodown.douyin.data.playback.PlaybackPreferences(appContext) }

    val douyinApiClient: DouyinApiClient by lazy { DouyinApiClient() }

    /** Installed version, read from `PackageManager` once per process. */
    val appVersion: com.videodown.douyin.data.update.AppVersion by lazy {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        // A blank name is data: the update dialog substitutes its own wording.
        com.videodown.douyin.data.update.AppVersion(code, info.versionName?.takeIf(String::isNotBlank).orEmpty())
    }

    /** Reads the remote version manifest; never downloads or installs a package. */
    val appUpdateChecker: com.videodown.douyin.data.update.AppUpdateChecker by lazy {
        com.videodown.douyin.data.update.AppUpdateChecker(douyinApiClient.okHttpClient, appVersion)
    }

    val douyinRepository: DouyinRepository by lazy { DouyinRepositoryImpl(douyinApiClient) }
    val browserSessions by lazy { BrowserSessionStore() }
    val videoEngine by lazy { VideoEngineRuntime(appContext) }
    private val sessionDirectory by lazy { File(appContext.noBackupFilesDir, "media-sessions") }
    val genericRepository by lazy {
        GenericVideoRepository(videoEngine, browserSessions, douyinApiClient.okHttpClient, sessionDirectory,
            playlistEnabled = { preferences.playlistParsing })
    }
    val videoRepository: VideoRepository by lazy { MultiPlatformVideoRepository(douyinRepository, fallback = genericRepository) }

    val galleryExporter by lazy { com.videodown.douyin.data.gallery.GalleryExporter(appContext) }

    val preferences: AppPreferences by lazy { AppPreferences(appContext) }

    /**
     * The vault gate. A `var` on purpose: device tests replace it so both answers of the system
     * prompt can be exercised without driving system UI.
     */
    var vaultAuthenticator: VaultAuthenticator = SystemVaultAuthenticator()
    private val networkPolicy by lazy { WifiDownloadNetworkPolicy(appContext, preferences) }
    private val completionNotifier by lazy { DownloadCompletionNotifier(appContext, preferences) }

    /**
     * Copies finished downloads into the system gallery when the user enabled it.
     *
     * Android 9 and below write through MediaStore, which needs the legacy storage permission; a
     * background completion cannot ask for it, so the outcome is reported as a reason instead.
     */
    private val autoGalleryExport by lazy {
        com.videodown.douyin.data.gallery.AutoGalleryExport(
            enabled = { preferences.autoSaveToGallery },
            canWrite = {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        appContext, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            export = { record -> galleryExporter.export(record).getOrThrow() },
        )
    }

    /**
     * Runs the optional gallery copy off the transfer coroutine.
     *
     * The engine calls this before releasing the queue slot, so the copy must not block it. The
     * completion notification is posted afterwards, so one card can report both facts.
     */
    private fun completeDownload(record: DownloadRecord) {
        appScope.launch {
            val gallery = runCatching { autoGalleryExport.save(record) }
                .getOrElse { com.videodown.douyin.data.gallery.GalleryAutoSave.Failed(com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED.storageKey) }
            runCatching { completionNotifier.notifyCompleted(record, gallery) }
        }
    }

    val downloadPaths: DownloadPaths by lazy { DownloadPaths(appContext) }

    val historyStore: DownloadHistoryStore by lazy {
        DownloadHistoryStore(File(appContext.filesDir, "download_history.json"))
    }

    val historyRepository: DownloadHistoryRepository by lazy {
        DownloadHistoryRepositoryImpl(historyStore)
    }

    private val maintenanceMutex = kotlinx.coroutines.sync.Mutex()
    val downloader: Downloader get() = downloadEngine
    private val downloadEngine by lazy {
        DownloadEngine(
            history = historyRepository,
            maxConcurrent = preferences.maxConcurrentDownloads,
            concurrencyChanges = preferences.maxConcurrentChanges,
            autoResumeChanges = preferences.autoResumeChanges,
            autoResumeEnabled = { preferences.autoResume },
            globalSpeedLimitChanges = preferences.globalSpeedLimitChanges,
            globalSpeedLimitKbps = { preferences.globalSpeedLimitKbps },
            maintenanceMutex = maintenanceMutex,
            downloader = ResumableDownloader(douyinApiClient.okHttpClient),
            paths = downloadPaths,
            scope = appScope,
            referer = DouyinApiClient.REFERER,
            userAgent = DouyinApiClient.USER_AGENT,
            networkPolicy = networkPolicy,
            onCompleted = ::completeDownload,
            onTaskScheduled = ::startDownloadService,
            engineDownloader = EngineMediaDownloader(videoEngine, browserSessions, sessionDirectory, historyRepository, maintenanceMutex),
            validateFile = ::validateVideo,
            browserCookie = { url -> runBlocking { browserSessions.cookie(url) } },
            refreshSource = { record ->
                val source = if (record.media?.useBrowserSession == true) record.sourcePageUrl
                    else record.media?.pageUrl?.ifBlank { null } ?: record.sourcePageUrl
                val result = if (record.media?.useBrowserSession == true)
                    genericRepository.parse(source, true, record.downloadReferer, record.media.userAgent)
                    else videoRepository.parseVideos(source)
                (result as? AppResult.Success)?.value?.videos?.flatMap { video ->
                    (video.variants + video.audioVariants).mapNotNull { fresh ->
                        val sameSelection = record.media?.let { previous -> fresh.media?.let(previous::sameMedia) }
                            ?: (fresh.label == record.resolutionLabel)
                        val sameItem = video.awemeId == record.awemeId ||
                            (record.platform == VideoPlatform.X && !record.media?.mediaId.isNullOrBlank() &&
                                record.media?.mediaId == fresh.media?.mediaId)
                        fresh.takeIf { sameItem && sameSelection }
                    }
                }?.singleOrNull()
            },
        )
    }

    /**
     * Called once from [com.videodown.douyin.VideoDownApp.onCreate]. Anything left RUNNING by a
     * previous process is demoted to PAUSED so it can be resumed rather than shown as stuck.
     */
    fun bootstrap() {
        // Remove session exports left by a killed process; cookies remain owned by WebView.
        sessionDirectory.listFiles()?.forEach { it.delete() }
        appScope.launch {
            runCatching { fileExportRepository.recoverInterruptedExports() }
            runCatching { libraryMetadataRepository.recoverRenames() }
            runCatching { historyRepository.recoverInterruptedDownloads() }
            // Continue whatever the previous process left behind, when the user allows it.
            if (preferences.autoResume) runCatching { downloader.autoResume() }
        }
    }

    /** Keeps the process alive while a transfer runs and mirrors progress to a notification. */
    val storageRepository: com.videodown.douyin.domain.repository.StorageRepository by lazy {
        com.videodown.douyin.data.local.DownloadStorageRepository({ downloadPaths.downloadDir }, historyRepository,
            maintenanceMutex, { downloadEngine.activeTaskIds() }, budget = { downloadEngine.storageBudget() })
    }

    val fileExportRepository: com.videodown.douyin.domain.repository.FileExportRepository by lazy {
        com.videodown.douyin.data.export.DocumentTreeExporter(appContext.contentResolver,
            historyRepository, downloadPaths, maintenanceMutex, File(appContext.filesDir, "export-transactions"))
    }

    val libraryMetadataRepository: com.videodown.douyin.domain.repository.LibraryMetadataRepository by lazy {
        com.videodown.douyin.data.local.DownloadLibraryMetadataRepository(downloadPaths, historyRepository,
            File(appContext.filesDir, "library-renames"), maintenanceMutex, { downloadEngine.activeTaskIds() })
    }

    suspend fun startDownloadService() {
        DownloadService.start(appContext)
    }

    fun interruptBackgroundDownloads(message: String) {
        appScope.launch { downloadEngine.interruptForBackgroundFailure(message) }
    }
}
