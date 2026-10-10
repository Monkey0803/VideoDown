package com.videodown.douyin.data.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** X's anonymous web client. Account cookies are never loaded, accepted, or persisted. */
class XVideoHttpClient(
    client: OkHttpClient = OkHttpClient(),
    private val guestUrl: String = "https://api.x.com/1.1/guest/activate.json",
    private val graphqlUrl: String = "https://x.com/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId",
    private val syndicationUrl: String = "https://cdn.syndication.twimg.com/tweet-result",
) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).build()
    private val guestMutex = Mutex()
    private var guestToken: String? = null
    private var guestCreatedAt = 0L

    suspend fun graphql(id: String): SocialPage {
        val query = buildJsonObject {
            put("tweetId", id); put("withCommunity", false)
            put("includePromotedContent", false); put("withVoice", false)
        }
        val url = graphqlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("variables", query.toString())
            .addQueryParameter("features", FEATURES)
            .addQueryParameter("fieldToggles", """{"withArticleRichContentState":false}""").build()
        var token = guest()
        var response = execute(base(url.toString()).header("Authorization", "Bearer $WEB_BEARER")
            .header("x-guest-token", token).build())
        if (response.status == 401) {
            guestMutex.withLock { if (guestToken == token) guestToken = null }
            token = guest()
            response = execute(base(url.toString()).header("Authorization", "Bearer $WEB_BEARER")
                .header("x-guest-token", token).build())
        }
        return response
    }

    suspend fun syndication(id: String): SocialPage = execute(base(syndicationUrl.toHttpUrl().newBuilder()
        .addQueryParameter("id", id).addQueryParameter("token", XSyndicationToken.generate(id)).build().toString())
        .header("User-Agent", "Googlebot").build())

    suspend fun expandShortLink(url: String): String {
        var current = url.toHttpUrl()
        repeat(5) {
            if (current.username.isNotEmpty() || current.password.isNotEmpty() ||
                VideoLinkExtractor.platformForHost(current.host) != com.videodown.douyin.domain.model.VideoPlatform.X) {
                throw IOException("短链未指向 X 推文")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
            }
            if (XVideoLink.parse(current.toString()) != null) return current.toString()
            val next = withContext(Dispatchers.IO) {
                val call = http.newCall(base(current.toString()).build())
                val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try { call.execute().use { r ->
                    if (r.code !in listOf(301, 302, 303, 307, 308)) throw IOException("X 短链未返回推文地址")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
                    current.resolve(r.header("Location").orEmpty()) ?: throw IOException("X 短链地址无效")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
                } } finally { cancel.cancel() }
            }
            current = next
        }
        throw IOException("X 短链跳转次数过多")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
    }

    private suspend fun guest(): String = guestMutex.withLock {
        val now = System.nanoTime()
        guestToken?.takeIf { now - guestCreatedAt < TimeUnit.MINUTES.toNanos(10) }?.let { return@withLock it }
        val response = execute(base(guestUrl).header("Authorization", "Bearer $WEB_BEARER")
            .post(ByteArray(0).toRequestBody()).build())
        if (response.status !in 200..299) throw XHttpException(response.status)
        val token = runCatching { (Json.parseToJsonElement(response.body) as? JsonObject)?.text("guest_token") }
            .getOrNull()?.takeIf { it.matches(Regex("[0-9]{1,100}")) }
            ?: throw IOException("X 匿名会话初始化失败")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
        guestToken = token
        guestCreatedAt = now
        token
    }

    private fun base(url: String) = Request.Builder().url(url)
        .header("User-Agent", DouyinApiClient.DESKTOP_USER_AGENT).header("Referer", "https://x.com/")

    private suspend fun execute(request: Request): SocialPage = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { response ->
            val source = response.body?.source() ?: throw IOException("X 返回空响应")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) throw IOException("X 返回的数据过大")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
            SocialPage(source.readUtf8(), response.request.url.toString(), response.code)
        } } finally { cancel.cancel() }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        // Public web-client identifier, not a user's account token or a developer API key.
        // Protocol reference: https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py
        private const val WEB_BEARER = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        private val FEATURES = buildJsonObject {
            listOf("creator_subscriptions_tweet_preview_api_enabled", "tweetypie_unmention_optimization_enabled",
                "responsive_web_edit_tweet_api_enabled", "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
                "view_counts_everywhere_api_enabled", "longform_notetweets_consumption_enabled",
                "freedom_of_speech_not_reach_fetch_enabled", "standardized_nudges_misinfo",
                "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
                "longform_notetweets_rich_text_read_enabled", "longform_notetweets_inline_media_enabled",
                "responsive_web_graphql_exclude_directive_enabled", "responsive_web_graphql_timeline_navigation_enabled")
                .forEach { put(it, true) }
            listOf("responsive_web_twitter_article_tweet_consumption_enabled", "tweet_awards_web_tipping_enabled",
                "verified_phone_label_enabled", "responsive_web_media_download_video_enabled",
                "responsive_web_graphql_skip_user_profile_image_extensions_enabled", "responsive_web_enhance_cards_enabled")
                .forEach { put(it, false) }
        }.toString()
    }
}

internal class XHttpException(val status: Int) : IOException("X 返回 HTTP $status")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
