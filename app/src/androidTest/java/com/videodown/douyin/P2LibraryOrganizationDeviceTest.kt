package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.local.*
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.library.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class P2LibraryOrganizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var paths: DownloadPaths
    private lateinit var history: DownloadHistoryRepositoryImpl
    private lateinit var record: DownloadRecord
    private lateinit var scope: CoroutineScope
    private lateinit var model: HistoryViewModel
    private lateinit var organization: LibraryOrganizationViewModel
    private val models = ViewModelStore()
    private var oldLayout = LibraryLayout.WATERFALL
    @Before fun setup() = runBlocking {
        directory = File(context.cacheDir, "p2-library-${UUID.randomUUID()}").apply { mkdirs() }
        paths = DownloadPaths(directory)
        history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        val file = File(paths.downloadDir, "original.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        record = DownloadRecord("source", "media", "整理界面验证", fileName = file.name, filePath = file.path,
            partialPath = file.path + ".part", status = DownloadStatus.COMPLETED)
        history.upsert(record)
        oldLayout = ServiceLocator.preferences.libraryLayout; ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = Mutex()
        val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient), paths, scope, "", "test", maintenanceMutex = gate)
        val repository = DownloadLibraryMetadataRepository(paths, history, File(directory, "journal"), gate) { emptySet() }
        compose.runOnUiThread {
            model = HistoryViewModel(history, engine); models.put("history", model)
            organization = LibraryOrganizationViewModel(repository); models.put("organization", organization)
            compose.activity.setContent { VideoDownTheme { LibraryScreen(model, {}, organizationViewModel = organization) } }
        }
        compose.waitUntil(5000) { model.state.value.records.isNotEmpty() }
    }
    @After fun close() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        compose.runOnUiThread { models.clear() }
        ServiceLocator.preferences.libraryLayout = oldLayout
        directory.deleteRecursively(); Unit
    }
    private fun menu(title: String = record.title) { compose.onNodeWithText(title).performTouchInput { longClick() } }
    @Test fun favoriteControlFiltersAndPersistsAfterHistoryReload() = runBlocking {
        menu(); compose.onNodeWithText("收藏视频", substring = false).performClick()
        compose.waitUntil(5000) { model.state.value.records.first().favorite }
        compose.onNodeWithText("仅收藏").performClick()
        compose.onNodeWithText(record.title).assertIsDisplayed()
        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json"))).getById(record.id)!!
        assertTrue(reopened.favorite)
        menu(); compose.onNodeWithText("取消收藏").performClick()
        compose.waitUntil(5000) { !model.state.value.records.first().favorite }
        compose.onNodeWithText("没有符合条件的视频").assertIsDisplayed()
        Unit
    }
    @Test fun actualRenameAndCategorySurviveRotationAndFilterSavedFile() = runBlocking {
        menu(); compose.onNodeWithText("重命名与分类").performClick()
        compose.onNode(hasSetTextAction() and hasText("文件名称")).performTextReplacement("旅行声音")
        compose.onNode(hasSetTextAction() and hasText("分类名称（留空表示未分类）")).performTextReplacement("旅行")
        val previous = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("保存修改").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("文件名称")).assertTextContains("旅行声音")
            compose.onNodeWithText("保存修改").performClick()
            compose.waitUntil(5000) { model.state.value.records.first().title == "旅行声音" && !organization.state.value.busy }
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("旅行", substring = false).performClick()
            compose.onNodeWithText("旅行声音").assertIsDisplayed()
            val after = history.getById(record.id)!!
            assertEquals("旅行", after.folder); assertFalse(File(record.filePath!!).exists())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(after.filePath!!).readBytes())
            assertEquals("mp4", File(after.filePath!!).extension)
        } finally { compose.runOnUiThread { compose.activity.requestedOrientation = previous } }
        Unit
    }
    @Test fun realPersistenceFailureShowsErrorAndRetryPreservesOriginalThenSucceeds() = runBlocking {
        val blocker = File(directory, "history.json.tmp").apply { mkdir() }
        menu(); compose.onNodeWithText("收藏视频", substring = false).performClick()
        compose.waitUntil(5000) { organization.state.value.error != null && !organization.state.value.busy }
        compose.onNodeWithText("整理失败，请检查名称、文件和可用空间后重试").assertIsDisplayed()
        assertFalse(history.getById(record.id)!!.favorite)
        assertTrue(File(record.filePath!!).isFile)
        blocker.delete()
        menu(); compose.onNodeWithText("收藏视频", substring = false).performClick()
        compose.waitUntil(5000) { model.state.value.records.first().favorite }
        assertNull(organization.state.value.error)
    }
}
