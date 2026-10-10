package com.videodown.douyin.data.remote

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.repository.DouyinRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test of the parse pipeline: share caption -> aweme id -> share-page fetch ->
 * `_ROUTER_DATA` extraction -> selectable renditions.
 *
 * Runs against a local stub that serves the same shapes Douyin does (a `302` short link and an
 * SSR share page), so the whole path is exercised without depending on the live service.
 */
class DouyinRepositoryIntegrationTest {

    private val servers = mutableListOf<StubHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private val awemeId = "7123456789012345678"

    private fun startServer(
        sharePage: String = SHARE_PAGE,
        legacyItemInfo: String = "",
        webDetail: String = "",
    ): StubHttpServer {
        val server = StubHttpServer(
            mapOf(
                "/share/video/$awemeId/" to StubResponse(body = sharePage),
                "/web/api/v2/aweme/iteminfo/" to StubResponse(body = legacyItemInfo),
                "/aweme/v1/web/aweme/detail/" to StubResponse(
                    body = webDetail,
                    contentType = "application/json",
                ),
            ),
        )
        servers.add(server)
        return server
    }

    private fun repositoryFor(server: StubHttpServer) = DouyinRepositoryImpl(
        DouyinApiClient(iesdouyinBase = server.baseUrl, douyinBase = server.baseUrl),
    )

    @Test
    fun `parses a share caption into a video with resolution choices`() = runBlocking {
        val server = startServer()
        val repository = repositoryFor(server)

        val caption = "7.42 复制打开抖音，看看【测试创作者的作品】海上日出 " +
            "https://www.douyin.com/video/$awemeId 复制此链接，打开Dou音搜索"

        val result = repository.parseShareText(caption)

        assertTrue("expected success, got $result", result is AppResult.Success)
        val video = (result as AppResult.Success).value
        assertEquals(awemeId, video.awemeId)
        assertEquals("海上日出延时摄影 #风景 #摄影", video.title)
        assertEquals("测试创作者", video.author.nickname)
        assertEquals("MS4wLjABAAAAtestsecuid0001", video.author.secUid)
        assertTrue("renditions must be selectable", video.sortedVariants.size >= 2)
        assertEquals("1080P", video.defaultVariant?.label)
        assertTrue(
            "the share page must be the endpoint that was used",
            server.paths.any { it.startsWith("/share/video/$awemeId/") },
        )
    }

    @Test
    fun `falls back to the legacy api when the share page has no video`() = runBlocking {
        val server = startServer(sharePage = "<html><body>blocked</body></html>", legacyItemInfo = LEGACY_JSON)
        val repository = repositoryFor(server)

        val result = repository.parseShareText("https://www.douyin.com/video/$awemeId")

        assertTrue("expected legacy fallback to succeed, got $result", result is AppResult.Success)
        val video = (result as AppResult.Success).value
        assertEquals("legacy fallback", video.title)
        assertTrue(server.paths.any { it.startsWith("/web/api/v2/aweme/iteminfo/") })
    }

    @Test
    fun `prefers the share page over the legacy api`() = runBlocking {
        val server = startServer(legacyItemInfo = LEGACY_JSON)
        val repository = repositoryFor(server)

        val result = repository.parseShareText("https://www.douyin.com/video/$awemeId")

        val video = (result as AppResult.Success).value
        assertEquals("share page wins", "海上日出延时摄影 #风景 #摄影", video.title)
        assertTrue(
            "legacy endpoint must not be hit when the share page works",
            server.paths.none { it.startsWith("/web/api/v2/aweme/iteminfo/") },
        )
    }

    @Test
    fun `reports a clear error for text without a douyin link`() = runBlocking {
        val server = startServer()
        val repository = repositoryFor(server)

        val result = repository.parseShareText("今天天气不错，去公园散步")

        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        // The wording lives in resources; the reason is typed.
        assertEquals("input:douyin_unrecognised", (error as com.videodown.douyin.core.AppError.InvalidLink).reasonKey)
    }

    @Test
    fun `reports which video failed when every source is unusable`() = runBlocking {
        val server = startServer(sharePage = "<html>nope</html>")
        val repository = repositoryFor(server)

        val result = repository.parseShareText("https://www.douyin.com/video/$awemeId")

        assertTrue(result is AppResult.Failure)
        // The failure still names the video, now as a data argument to the sentence.
        val error = (result as AppResult.Failure).error as com.videodown.douyin.core.AppError.Parse
        assertEquals("douyin:id_unresolved", error.reasonKey)
        assertEquals(awemeId, error.detail)
    }

    private companion object {
        val SHARE_PAGE = """
            <!doctype html><html><body>
            <script>window._ROUTER_DATA = {
              "loaderData": {"video_(id)/page": {"videoInfoRes": {"item_list": [{
                "aweme_id": "7123456789012345678",
                "desc": "海上日出延时摄影 #风景 #摄影",
                "duration": 15320,
                "author": {"uid":"1","sec_uid":"MS4wLjABAAAAtestsecuid0001","nickname":"测试创作者"},
                "video": {
                  "ratio": "1080p",
                  "width": 1080, "height": 1920,
                  "cover": {"url_list": ["https://p3-sign.douyinpic.com/cover.jpeg"]},
                  "play_addr": {"url_list": ["https://v3-web.douyinvod.com/video/x/?ratio=1080p"],"width":1080,"height":1920,"data_size":4183220},
                  "bit_rate": [
                    {"bit_rate": 2185330, "play_addr": {"url_list": ["https://v3-web.douyinvod.com/video/x/?br=1080"],"width":1080,"height":1920,"data_size":4183220}},
                    {"bit_rate": 1120450, "play_addr": {"url_list": ["https://v3-web.douyinvod.com/video/x/?br=720"],"width":720,"height":1280,"data_size":2143010}}
                  ]
                }
              }]}}},
              "errors": null
            }</script></body></html>
        """.trimIndent()

        val LEGACY_JSON = """
            {"status_code":0,"item_list":[{
              "aweme_id":"7123456789012345678",
              "desc":"legacy fallback",
              "video":{"play_addr":{"url_list":["https://v3-web.douyinvod.com/legacy/?ratio=720p"],"width":720,"height":1280}}
            }]}
        """.trimIndent()
    }
}
