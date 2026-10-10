package com.videodown.douyin.data.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SocialPage(val body: String, val url: String, val status: Int)

/** Anonymous website access only. No browser/account cookies are read or persisted. */
class SocialVideoHttpClient(
    private val client: OkHttpClient = newClient(),
    private val weiboBase: String = "https://weibo.com",
    private val passportBase: String = "https://passport.weibo.com",
) {
    private val visitorMutex = Mutex()

    suspend fun page(url: String, mobile: Boolean = true): SocialPage = execute(
        Request.Builder().url(url)
            .header("User-Agent", if (mobile) DouyinApiClient.USER_AGENT else DouyinApiClient.DESKTOP_USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build(),
    )

    suspend fun weiboStatus(id: String): SocialPage {
        val request = Request.Builder().url("$weiboBase/ajax/statuses/show".toHttpUrl().newBuilder()
            .addQueryParameter("id", id).build())
            .header("Referer", "$weiboBase/")
            .header("User-Agent", DouyinApiClient.DESKTOP_USER_AGENT).build()
        val first = execute(request)
        if (!needsVisitor(first)) return first
        visitorMutex.withLock { createWeiboVisitor() }
        return execute(request)
    }

    private fun needsVisitor(page: SocialPage) = page.status in listOf(401, 403) ||
        page.url.toHttpUrl().host.contains("passport.weibo") ||
        page.body.contains("\"error\":\"Forbidden\"") || page.body.trimStart().startsWith("<")

    private suspend fun createWeiboVisitor() {
        val chrome = Regex("Chrome/(\\d+)").find(DouyinApiClient.DESKTOP_USER_AGENT)?.groupValues?.get(1) ?: "126"
        val fingerprint = buildJsonObject {
            put("os", JsonPrimitive("1"))
            put("browser", JsonPrimitive("Chrome$chrome,0,0,0"))
            put("fonts", JsonPrimitive("undefined"))
            put("screenInfo", JsonPrimitive("1920*1080*24"))
            put("plugins", JsonPrimitive(""))
        }
        val generated = execute(Request.Builder().url("$passportBase/visitor/genvisitor")
            .header("Referer", "$weiboBase/")
            .header("User-Agent", DouyinApiClient.DESKTOP_USER_AGENT)
            .post(FormBody.Builder().add("cb", "gen_callback").add("fp", fingerprint.toString()).build()).build())
        val jsonText = generated.body.substringAfter('(', "").substringBeforeLast(')', "")
        val data = runCatching { Json.parseToJsonElement(jsonText).jsonObject["data"] as? JsonObject }.getOrNull()
        val tid = (data?.get("tid") as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("微博匿名访问初始化失败，请稍后重试")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
        val isNew = (data["new_tid"] as? JsonPrimitive)?.content in listOf("true", "1")
        val confidence = (data["confidence"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 100
        val url = "$passportBase/visitor/visitor".toHttpUrl().newBuilder()
            .addQueryParameter("a", "incarnate").addQueryParameter("t", tid)
            .addQueryParameter("w", if (isNew) "3" else "2")
            .addQueryParameter("c", confidence.toString().padStart(3, '0'))
            .addQueryParameter("gc", "").addQueryParameter("cb", "cross_domain")
            .addQueryParameter("from", "weibo").build()
        val result = execute(Request.Builder().url(url).header("Referer", "$weiboBase/")
            .header("User-Agent", DouyinApiClient.DESKTOP_USER_AGENT).build())
        if (result.status !in 200..299) throw IOException("微博匿名访问返回 HTTP ${result.status}")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
    }

    private suspend fun execute(request: Request): SocialPage = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancelSocket = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                val source = response.body?.source() ?: throw IOException("平台返回空响应")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
                source.request(MAX_PAGE_BYTES + 1)
                if (source.buffer.size > MAX_PAGE_BYTES) throw IOException("平台页面过大，暂时无法解析")  // l10n-ignore: 开发者诊断：异常消息仅用于排查，不作为界面文案显示
                SocialPage(source.readUtf8(), response.request.url.toString(), response.code)
            }
        } finally { cancelSocket.cancel() }
    }

    companion object {
        private const val MAX_PAGE_BYTES = 4L * 1024 * 1024
        fun newClient() = OkHttpClient.Builder().cookieJar(VisitorCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).build()
    }
}

/** Passport issues parent-domain cookies; an exact-host bucket would lose those on weibo.com. */
internal class VisitorCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies.add(cookie)
        }
        this.cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
        while (this.cookies.size > 256) this.cookies.removeAt(0)
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt >= System.currentTimeMillis() && it.matches(url) }
}
