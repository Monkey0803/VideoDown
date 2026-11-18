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
import com.videodown.douyin.data.remote.XParseFailure
import com.videodown.douyin.data.remote.XVideoHttpClient
import com.videodown.douyin.data.repository.XVideoRepository
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * X 的失败归因与登录态原生解析（设备侧）。
 *
 * 覆盖三件事：受限帖子给登录入口、纯图片帖带 Cookie 解析成图片条目、没有视频的帖子用自己的文案。
 * 全部离线：Cookie 直接写进系统 CookieManager，GraphQL 响应由 OkHttp 拦截器伪造，不访问真实 x.com。
 */
@RunWith(AndroidJUnit4::class)
class XPostRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val captured = CopyOnWriteArrayList<Map<String, String>>()

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
    }

    private fun repositoryFailing(error: AppError) = object : VideoRepository {
        override suspend fun parseShareText(shareText: String) = AppResult.Failure(error)
        override suspend fun parseVideos(shareText: String) = AppResult.Failure(error)
    }

    private fun homeViewModel(repository: VideoRepository) = HomeViewModel(repository, downloader, { it.defaultVariant })

    @Test fun restrictedPostOffersTheXSignInAction() {
        val vm = homeViewModel(repositoryFailing(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
            reasonKey = XParseFailure.RESTRICTED.storageKey, platform = VideoPlatform.X)))
        models.put("restricted", vm)
        compose.setContent {
            VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), vm, xLoginStartUrl = "about:blank") }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(TWEET)
        compose.onNodeWithText(context.getString(R.string.home_parse_now)).performClick()
        compose.waitUntil(20_000) { vm.state.value.errorKind == DownloadFailureKind.LOGIN_REQUIRED }
        // 受限（tombstone）不是「不支持」：用户必须看到登录入口，而不是没有出路的通用文案。
        compose.onNodeWithText(context.getString(R.string.x_login_action)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.failure_no_video_title)).assertCountEquals(0)
    }

    @Test fun aNoVideoPostUsesItsOwnWordingInsteadOfUnsupported() {
        val vm = homeViewModel(repositoryFailing(AppError.Parse("", DownloadFailureKind.NO_VIDEO,
            reasonKey = XParseFailure.NO_VIDEO.storageKey, platform = VideoPlatform.X)))
        models.put("no-video", vm)
        compose.setContent {
            VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), vm, xLoginStartUrl = "about:blank") }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(TWEET)
        compose.onNodeWithText(context.getString(R.string.home_parse_now)).performClick()
        compose.waitUntil(20_000) { vm.state.value.errorKind == DownloadFailureKind.NO_VIDEO }
        compose.onNodeWithText(context.getString(R.string.failure_no_video_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.failure_no_video_body)).assertExists()
        // 没有视频不是登录问题，也不该显示 DRM / 直播那句。
        compose.onAllNodesWithText(context.getString(R.string.failure_unsupported_title)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.x_login_action)).assertCountEquals(0)
    }

    @Test fun theNativeSessionSendsWebViewCookiesAndParsesAPhotoPost() = runBlocking {
        setCookie("auth_token=fixture; Path=/; Secure; HttpOnly")
        setCookie("ct0=csrf; Path=/; Secure; HttpOnly")
        val repository = XVideoRepository(XVideoHttpClient(
            client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                captured += request.headers.toMultimap().mapValues { it.value.first() }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(PHOTO_TWEET.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
            sessionCookies = { ServiceLocator.browserSessions.xGraphqlCookieHeader() },
        ))

        val result = repository.parseWithSession(TWEET) as AppResult.Success
        val video = result.value.selectedVideo
        assertTrue("a photo-only post must become an image entry", video.isImagePost)
        assertEquals("https://pbs.twimg.com/media/HXRAvPzWwAADVjC.jpg?name=orig", video.variants.single().url)

        val sent = captured.single()
        assertTrue("the WebView cookies must be attached:\n${sent["cookie"]}", sent["cookie"].orEmpty().contains("auth_token=fixture"))
        assertTrue("ct0 must be attached", sent["cookie"].orEmpty().contains("ct0=csrf"))
        assertEquals("csrf", sent["x-csrf-token"])
        assertEquals("OAuth2Session", sent["x-twitter-auth-type"])
        assertNull("a signed request must not activate a guest session", sent["x-guest-token"])
    }

    private suspend fun setCookie(value: String) = withContext(Dispatchers.Main) {
        CookieManager.getInstance().setCookie(X_ORIGIN, value)
        CookieManager.getInstance().flush()
    }

    companion object {
        private const val X_ORIGIN = "https://x.com/"
        private const val TWEET = "https://x.com/ShexyoArt/status/2096916397947257248"

        /** 匿名接口只回 tweet tombstone 的敏感内容图片帖：带登录态才拿得到原图。 */
        private const val PHOTO_TWEET = """{"data":{"tweetResult":{"result":{"__typename":"Tweet",
            "rest_id":"2096916397947257248",
            "core":{"user_results":{"result":{"rest_id":"42","legacy":{"id_str":"42","name":"Shexyo","screen_name":"ShexyoArt"}}}},
            "legacy":{"id_str":"2096916397947257248","full_text":"momo seiko",
              "extended_entities":{"media":[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/HXRAvPzWwAADVjC.jpg"}]}}}}}}"""
    }
}
