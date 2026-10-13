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
import com.videodown.douyin.presentation.components.APP_DIALOG_CARD_TAG
import com.videodown.douyin.presentation.components.APP_DIALOG_SCRIM_TAG
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

    /** 页脚两条判据一起过：按钮落在卡片内、卡片落在容器（底部内边距 = 键盘顶边）内。 */
    private fun footerVisible(text: String = "保存修改"): Boolean =
        compose.footerInsideCard(APP_DIALOG_CARD_TAG, text) &&
            compose.cardInsideScrim(APP_DIALOG_CARD_TAG, APP_DIALOG_SCRIM_TAG)

    /** 把证据截图存进应用缓存目录，跑完用 adb 取回（见 docs/validation 的对应记录）。 */
    private fun evidence(name: String) {
        val directory = File(context.cacheDir, "dialog-evidence").apply { mkdirs() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .takeScreenshot().let { bitmap ->
                File(directory, "$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
    }
    @Test fun tappingOutsideTheCardDismissesButTappingTheCardBodyDoesNot() = runBlocking {
        menu(); compose.onNodeWithText("重命名与分类").performClick()
        compose.onNodeWithText("重命名与分类").assertIsDisplayed()
        // 点卡片自己的空白处（标题）不能把弹窗关掉：卡片必须挡住底下那层遮罩。
        compose.onNodeWithText("重命名与分类").performClick()
        compose.onNodeWithText("重命名与分类").assertIsDisplayed()
        // 点遮罩左上角（卡片在窗口里居中，这个点一定在卡片之外）应当关闭弹窗。
        compose.onNodeWithTag(APP_DIALOG_SCRIM_TAG)
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 30f)) }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("重命名与分类")).fetchSemanticsNodes().isEmpty() }
        Unit
    }

    @Test fun favoriteControlFiltersAndPersistsAfterHistoryReload() = runBlocking {
        menu(); compose.onNodeWithText("收藏视频", substring = false).performClick()
        compose.waitUntil(5000) { model.state.value.records.first().favorite }
        compose.onNodeWithTag("library-controls").performScrollToNode(hasText("仅收藏"))
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
            // 与真实使用一致：横屏 + 输入法弹起时，页脚按钮必须完整露在键盘之上。
            // 旋转会把键盘收起来（输入框仍持有焦点，点它不会重新弹出）：显式叫出来。
            compose.waitUntil(8000) {
                runCatching { showDialogKeyboard() }
                imeVisible(compose.activity)
            }
            // 键盘动画期间可用高度还在变、排布也可能从「固定页脚」切到「整卡滚动」：每轮重新滚一次，
            // 滚到按钮既落在卡片内、又落在键盘之上，**且键盘不再变化**为止——否则键盘弹了一半时
            // 判据会短暂成立，紧接着的断言就翻车。
            val settler = ImeSettler(compose.activity)
            compose.waitUntil(8000) {
                runCatching { compose.onNode(hasText("保存修改") and hasClickAction()).performScrollTo() }
                settler.settled() && footerVisible()
            }
            assertTrue("「保存修改」必须完整露在键盘之上，而不是被卡片挤扁或落在键盘背后", footerVisible())
            evidence("dialog-ime-landscape-after")
            compose.onNode(hasSetTextAction() and hasText("文件名称")).assertTextContains("旅行声音")
            compose.onNodeWithText("保存修改").performClick()
            compose.waitUntil(5000) { model.state.value.records.first().title == "旅行声音" && !organization.state.value.busy }
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("library-controls").performScrollToNode(hasContentDescription("选择分类"))
            compose.onNodeWithContentDescription("选择分类").performClick()
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
