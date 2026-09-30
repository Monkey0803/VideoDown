package com.videodown.douyin

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.generic.*
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.history.*
import com.videodown.douyin.presentation.library.*
import com.videodown.douyin.presentation.player.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class P2AudioUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var history: DownloadHistoryRepositoryImpl
    private lateinit var paths: DownloadPaths
    private lateinit var source: DownloadRecord
    private lateinit var engine: DownloadEngine
    private lateinit var scope: CoroutineScope
    private lateinit var model: HistoryViewModel
    private val models = ViewModelStore()
    private var oldLayout = LibraryLayout.WATERFALL
    @Before fun setup() = runBlocking {
        directory = File(context.cacheDir, "p2-audio-ui-${UUID.randomUUID()}").apply { mkdirs() }
        paths = DownloadPaths(directory)
        history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        val file = File(paths.downloadDir, "source.mp4")
        instrumentation.context.assets.open("aac-video-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        source = DownloadRecord("source", "media", "界面提取音频验证", fileName = file.name, filePath = file.path,
            partialPath = file.path + ".part", status = DownloadStatus.COMPLETED, totalBytes = file.length(), downloadedBytes = file.length())
        history.upsert(source)
        val gate = Mutex()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient), paths, scope, "", "VideoDown-Test",
            maintenanceMutex = gate, engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions"), history, gate))
        oldLayout = ServiceLocator.preferences.libraryLayout; ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
        compose.runOnUiThread {
            model = HistoryViewModel(history, engine); models.put("history", model)
            val audio = AudioExtractionViewModel(engine); models.put("audio", audio)
            compose.activity.setContent { VideoDownTheme { LibraryScreen(model, {}, audioExtractionViewModel = audio) } }
        }
        compose.waitUntil(5000) { model.state.value.records.isNotEmpty() }
    }
    @After fun close() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        compose.runOnUiThread { models.clear() }
        ServiceLocator.preferences.libraryLayout = oldLayout
        directory.deleteRecursively(); Unit
    }
    private fun requestExtraction() {
        compose.onNodeWithText(source.title).performTouchInput { longClick() }
        compose.onNodeWithText("提取音频", substring = false).performClick()
        compose.onNodeWithText("开始提取").performClick()
    }
    @Test fun actualLibraryExtractionControlsCreateAudioAndWarnBeforeDuplicate() {
        requestExtraction()
        compose.waitUntil(30000) { model.state.value.records.any { it.isAudio && it.status == DownloadStatus.COMPLETED } }
        assertTrue(File(source.filePath!!).isFile)
        // Open the actual source extraction dialog again against the completed queue record.
        val audio = AudioExtractionViewModel(engine)
        compose.runOnUiThread { models.put("duplicate", audio); compose.activity.setContent { VideoDownTheme { AudioExtractionDialog(source, audio, {}, {}) } } }
        compose.onNodeWithText("开始提取").performClick()
        compose.waitUntil(5000) { audio.state.value.duplicate != null }
        compose.onNodeWithText("仍然提取一份").assertIsDisplayed()
        assertEquals(1, model.state.value.records.count { it.isAudio })
    }
    @Test fun noAudioTypedFailureIsExplicitOnActualTaskScreen() = runBlocking {
        history.upsert(source.copy(id = "silent-task", status = DownloadStatus.FAILED, filePath = null,
            media = MediaDownload(audioOnly = true, localRecordId = source.id), failureKind = DownloadFailureKind.NO_AUDIO))
        compose.runOnUiThread { compose.activity.setContent { VideoDownTheme { HistoryScreen({}, model) } } }
        compose.waitUntil(5000) { model.state.value.records.size == 2 }
        compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-silent-task"))
        compose.onNodeWithText("原视频没有音轨").assertIsDisplayed()
        compose.onNodeWithText("该视频不包含可提取的音频，请选择另一个有声音的视频。原视频已保留。").assertIsDisplayed()
        Unit
    }
    @Test fun audioShareOpensSystemChooserWithReadableAudioUri() {
        val file = File(paths.downloadDir, "share.m4a")
        instrumentation.context.assets.open("audio-fixture.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        var chooser: android.content.Intent? = null
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == android.content.Intent.ACTION_CHOOSER) chooser = intent
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.runOnUiThread { assertTrue(DouyinIntents.shareFile(compose.activity, file)) }
            instrumentation.waitForIdleSync()
            val captured = checkNotNull(chooser)
            @Suppress("DEPRECATION")
            val send = captured.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals(android.content.Intent.ACTION_SEND, send.action)
            assertEquals("audio/mp4", send.type)
            assertEquals("分享音频", captured.getStringExtra(android.content.Intent.EXTRA_TITLE))
            assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            val deadline = System.currentTimeMillis() + 5000
            var systemChooser = false
            while (System.currentTimeMillis() < deadline && !systemChooser) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                systemChooser = root?.packageName?.toString() in setOf("android", "com.android.intentresolver")
                if (!systemChooser) Thread.sleep(100)
            }
            assertTrue("System share chooser did not open", systemChooser)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun savedAudioActuallyDecodesPlaysAndPausesInsideApp() = runBlocking {
        val file = File(paths.downloadDir, "player.m4a")
        instrumentation.context.assets.open("audio-fixture.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        val record = source.copy(id = "audio-player", title = "音频播放验证", fileName = file.name, filePath = file.path, media = MediaDownload(audioOnly = true))
        history.upsert(record)
        compose.runOnUiThread {
            val player = PlayerViewModel(history); models.put("player", player)
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, player) } }
        }
        compose.waitUntil(10000) {
            var playing = false
            compose.runOnUiThread { playing = playerView(compose.activity.window.decorView)?.player?.let { it.isPlaying && it.playbackState == Player.STATE_READY && it.currentPosition > 100 } == true }
            playing
        }
        compose.onNodeWithText("暂停", substring = false).performScrollTo().performClick()
        compose.runOnUiThread { assertFalse(playerView(compose.activity.window.decorView)!!.player!!.isPlaying) }
        compose.onNodeWithText("转存到音乐").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("横屏全屏").assertIsNotEnabled()
        Unit
    }
}
