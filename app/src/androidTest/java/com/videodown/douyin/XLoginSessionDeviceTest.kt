package com.videodown.douyin

import android.webkit.CookieManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.BrowserSessionStore
import com.videodown.douyin.data.remote.XParseFailure
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.login.X_LOGIN_STATUS_TAG
import com.videodown.douyin.presentation.settings.SettingsScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * X 登录态：识别、导出、界面入口与「登录后重试」。
 *
 * 全部离线：Cookie 直接写进系统 CookieManager，登录网页用 about:blank 代替，真实 x.com 不访问。
 * 真实账号登录、真实受保护推文下载属于未覆盖边界（见验证记录）。
 */
@RunWith(AndroidJUnit4::class)
class XLoginSessionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "x-login-${UUID.randomUUID()}").apply { mkdirs() }
    private val models = ViewModelStore()
    private val parsed = CopyOnWriteArrayList<String>()

    private val fixtureVideo = DouyinVideo("2096916397947257248_11111", "登录后才出现的视频",
        platform = VideoPlatform.X, mediaLabel = "视频 1", shareUrl = TWEET,
        variants = listOf(VideoVariant("https://video.twimg.com/fixture.mp4", "1080P", sizeBytes = 1234)))

    private val repository = object : VideoRepository {
        override suspend fun parseShareText(shareText: String) =
            AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
                reasonKey = XParseFailure.LOGIN_REQUIRED.storageKey, platform = VideoPlatform.X))
        override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> {
            parsed += shareText
            return if (parsed.size == 1) {
                AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
                    reasonKey = XParseFailure.LOGIN_REQUIRED.storageKey, platform = VideoPlatform.X))
            } else AppResult.Success(ParsedVideos(listOf(fixtureVideo)))
        }
    }

    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection) =
            error("下载不在本用例范围内")
        override suspend fun pause(id: String) {}
        override suspend fun resume(id: String) {}
        override suspend fun cancel(id: String, removeRecord: Boolean) {}
        override suspend fun resumeAll() {}
        override suspend fun pauseAll() {}
        override suspend fun restart(id: String) {}
        override fun observeRecord(id: String): kotlinx.coroutines.flow.Flow<DownloadRecord?> =
            kotlinx.coroutines.flow.flowOf(null)
    }

    @Before fun resetSession() = runBlocking { ServiceLocator.browserSessions.clear() }
    @After fun cleanup() {
        runBlocking { ServiceLocator.browserSessions.clear() }
        compose.runOnUiThread { models.clear() }
        directory.deleteRecursively()
    }

    private suspend fun setCookie(url: String, value: String) = withContext(Dispatchers.Main) {
        CookieManager.getInstance().setCookie(url, value)
        CookieManager.getInstance().flush()
    }

    private suspend fun signedIn() = ServiceLocator.browserSessions.xSignedIn()

    @Test fun signedInFollowsLiveCookieStateAndSiteExpiry() = runBlocking {
        assertFalse(signedIn())
        setCookie(X_ORIGIN, "auth_token=fixture; Path=/; Secure; HttpOnly")
        assertFalse("auth_token alone is not a usable session", signedIn())
        setCookie(X_ORIGIN, "ct0=csrf; Path=/; Secure; HttpOnly")
        assertTrue(signedIn())
        // 站点把它设成过期之后，系统不再返回该 Cookie：App 不需要自己的过期时间。
        setCookie(X_ORIGIN, "auth_token=fixture; Path=/; Secure; HttpOnly; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        assertFalse("an expired cookie must not count as signed in", signedIn())
    }

    @Test fun bootstrapNeverClearsStoredSession() = runBlocking {
        setCookie(X_ORIGIN, "auth_token=fixture; Path=/; Secure; HttpOnly")
        setCookie(X_ORIGIN, "ct0=csrf; Path=/; Secure; HttpOnly")
        assertTrue(signedIn())
        ServiceLocator.bootstrap()
        assertTrue("startup must not drop the WebView login", signedIn())
    }

    @Test fun exportCarriesSessionForXAndApiOrigins() = runBlocking {
        // HttpOnly 也必须能被 getCookie 读到，否则随包引擎拿不到登录态。
        setCookie("https://x.com/", "auth_token=fixture; Path=/; Secure; HttpOnly")
        setCookie("https://api.x.com/1.1/", "ct0=csrf; Path=/; Secure; HttpOnly")
        val file = File(directory, "session.txt")
        ServiceLocator.browserSessions.export(BrowserSessionStore.extractorCookieUrls(TWEET), file)
        val lines = file.readLines()
        assertTrue(lines.toString(), lines.any { it.startsWith("x.com\t") && it.contains("\tauth_token\tfixture") })
        assertTrue(lines.toString(), lines.any { it.startsWith("api.x.com\t") && it.contains("\tct0\tcsrf") })
    }

    @Test fun settingsRowWordsUnsignedState() {
        val signedInLabel = context.getString(R.string.settings_x_login_signed_out)
        compose.setContent { VideoDownTheme { SettingsScreen() } }
        compose.onNodeWithTag("settings-open-accounts").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(context.getString(R.string.settings_x_login_row)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.settings_x_login_row)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(signedInLabel).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_x_login_note)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_clear_session_scope)).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsRowReportsStoredSession() {
        runBlocking {
            setCookie(X_ORIGIN, "auth_token=fixture; Path=/; Secure; HttpOnly")
            setCookie(X_ORIGIN, "ct0=csrf; Path=/; Secure; HttpOnly")
        }
        compose.setContent { VideoDownTheme { SettingsScreen() } }
        compose.onNodeWithTag("settings-open-accounts").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(context.getString(R.string.settings_x_login_signed_in)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.settings_x_login_row)).performScrollTo().assertIsDisplayed()
    }

    @Test fun parseErrorOffersXLoginAndRetriesAfterIt() {
        runBlocking {
            // 登录态已经存在：弹窗一打开就该认出它，不需要真的连 x.com。
            setCookie(X_ORIGIN, "auth_token=fixture; Path=/; Secure; HttpOnly")
            setCookie(X_ORIGIN, "ct0=csrf; Path=/; Secure; HttpOnly")
        }
        val vm = HomeViewModel(repository, downloader, { it.defaultVariant })
        models.put("home", vm)
        compose.setContent {
            VideoDownTheme {
                HomeScreen(null, {}, {}, {}, {}, emptyList(), vm, xLoginStartUrl = "about:blank")
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(TWEET)
        compose.onNodeWithText(context.getString(R.string.home_parse_now)).performClick()
        compose.waitUntil(20_000) { vm.state.value.error != null && !vm.state.value.isParsing }
        compose.onNodeWithText(context.getString(R.string.x_relogin_action)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(X_LOGIN_STATUS_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.x_login_signed_in)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.action_done)).performClick()
        compose.waitUntil(20_000) { vm.state.value.video != null }
        assertEquals("登录后应当用同一份输入重试一次", 2, parsed.size)
        compose.onNodeWithText(fixtureVideo.title).assertExists()
    }

    @Test fun accessDeniedKeepsLoginRecoveryWordingNeutral() {
        val accessDeniedRepository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) =
                AppResult.Failure(AppError.Parse("", DownloadFailureKind.ACCESS_DENIED, platform = VideoPlatform.X))
        }
        val vm = HomeViewModel(accessDeniedRepository, downloader, { it.defaultVariant })
        models.put("access-denied", vm)
        compose.setContent {
            VideoDownTheme {
                HomeScreen(null, {}, {}, {}, {}, emptyList(), vm, xLoginStartUrl = "about:blank")
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(TWEET)
        compose.onNodeWithText(context.getString(R.string.home_parse_now)).performClick()
        compose.waitUntil(20_000) { vm.state.value.errorKind == DownloadFailureKind.ACCESS_DENIED }
        compose.onNodeWithText(context.getString(R.string.x_check_login_access_action)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.x_relogin_action)).assertCountEquals(0)
    }

    companion object {
        private const val X_ORIGIN = "https://x.com/"
        private const val TWEET = "https://x.com/fixture/status/2096916397947257248"
    }
}
