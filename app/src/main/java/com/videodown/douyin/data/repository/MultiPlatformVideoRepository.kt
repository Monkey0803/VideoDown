package com.videodown.douyin.data.repository

import com.videodown.douyin.core.AppError
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.map
import com.videodown.douyin.data.generic.SessionVideoParser
import com.videodown.douyin.data.remote.*
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.repository.DouyinRepository
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 原生解析里比引擎兜底结论更值得展示的原因。 */
private val DEFINITIVE_X_REASON_KEYS = setOf(
    XParseFailure.NO_VIDEO.storageKey,
    XParseFailure.LOGIN_REQUIRED.storageKey,
    XParseFailure.RESTRICTED.storageKey,
    XParseFailure.UNAVAILABLE.storageKey,
    XParseFailure.NOT_FOUND.storageKey,
    XParseFailure.UNSUPPORTED_FORMAT.storageKey,
)

class MultiPlatformVideoRepository(
    private val douyin: DouyinRepository,
    private val social: SocialVideoHttpClient = SocialVideoHttpClient(),
    private val x: XVideoRepository = XVideoRepository(),
    private val fallback: VideoRepository? = null,
    /** 用本机 WebView 登录态解析，仅当匿名路径都失败时才使用；null 表示该站点不参与。 */
    private val sessionFallback: SessionVideoParser? = null,
    /** 用原生 X 接口 + 本机登录态解析；返回 null 表示没有可用登录态。 */
    private val xNativeSession: SessionVideoParser? = null,
    /** 展开 b23.tv 短链；null 时短链解析直接报告「无法展开」。返回 null 表示目标不可信。 */
    private val resolveBilibiliShortLink: (suspend (String) -> BilibiliVideoLink?)? = null,
    /** 用应用内 B 站登录态解析；null 表示当前没有登录态。 */
    private val bilibiliSession: SessionVideoParser? = null,
) : VideoRepository {
    override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = parseVideos(shareText).map { it.selectedVideo }

    override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> = withContext(Dispatchers.IO) {
        val link = VideoLinkExtractor.extract(shareText)
        if (link == null) {
            // Preserve existing support for a raw Douyin video ID.
            if (shareText.trim().matches(Regex("[0-9]{15,22}"))) return@withContext douyin.parseVideos(shareText)
            return@withContext AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:needs_link"))
        }
        try {
            val result = when (link.platform) {
                VideoPlatform.GENERIC -> return@withContext fallback?.parseVideos(link.url)
                    ?: AppResult.Failure(AppError.Parse("", reasonKey = "input:generic_only"))
                VideoPlatform.DOUYIN -> douyin.parseVideos(link.url)
                VideoPlatform.WEIBO -> parseWeibo(link.url)
                VideoPlatform.KUAISHOU -> parseKuaishou(link.url)
                VideoPlatform.X -> return@withContext parseX(link.url)
                VideoPlatform.BILIBILI -> return@withContext parseBilibili(link.url)
            }
            if (result is AppResult.Failure && fallback != null) fallback.parseVideos(link.url) else result
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fallback?.parseVideos(link.url) ?: AppResult.Failure(AppError.Network("", e, platform = link.platform, reasonKey = "platform:network"))
        } catch (_: Exception) {
            fallback?.parseVideos(link.url) ?: AppResult.Failure(AppError.Parse("", platform = link.platform, reasonKey = "platform:no_data"))
        }
    }

    /**
     * X 的解析顺序：原生匿名 → （需要登录时）原生登录态 → 引擎登录态 → 引擎匿名。
     *
     * 原生说「需要登录」时先跑登录态：敏感内容/年龄门禁的帖子匿名只看得到 `TweetTombstone`，
     * 带上 WebView 的 `auth_token` + `ct0` 才能拿到真实媒体（含纯图片帖的原图）；
     * 其它情况匿名优先，公开推文保持完全匿名，也避免一份陈旧 Cookie 把本来能成功的解析弄坏。
     */
    private suspend fun parseX(url: String): AppResult<ParsedVideos> {
        val native = x.parse(url)
        val supplemental = fallback ?: return native
        val session = sessionFallback

        var sessionTarget: String? = null
        var sessionFailure: AppResult<ParsedVideos>? = null
        var nativeSessionFailure: AppResult<ParsedVideos>? = null
        var anonymousFailure: AppResult<ParsedVideos>? = null

        suspend fun attemptNativeSession(): AppResult<ParsedVideos>? {
            val parser = xNativeSession ?: return null
            val result = try {
                parser.parseWithSession(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
            nativeSessionFailure = result
            return result
        }

        suspend fun attemptSession(): AppResult<ParsedVideos>? {
            if (session == null) return null
            // 短链要先展开：引擎按页面地址取媒体，合并也要用真正的推文地址。
            val target = try { x.resolve(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
            sessionTarget = target
            val result = try {
                session.parseWithSession(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 一个不可用的登录态不能把整次解析弄失败：当作没有登录态继续匿名路径。
                null
            } ?: return null
            sessionFailure = result
            return result
        }

        suspend fun attemptAnonymous(): AppResult<ParsedVideos>? {
            val result = try {
                supplemental.parseVideos(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
            anonymousFailure = result
            return result
        }

        val attempts: List<suspend () -> AppResult<ParsedVideos>?> = if (native.needsLogin()) {
            listOf(::attemptNativeSession, ::attemptSession, ::attemptAnonymous)
        } else {
            listOf(::attemptAnonymous, ::attemptSession)
        }
        for (attempt in attempts) {
            val result = attempt() ?: continue
            if (result is AppResult.Success) return mergedX(sessionTarget ?: url, native, result)
        }

        // 登录态尝试被服务端拒绝：这条比引擎的通用授权/网络失败更能指导用户（重新登录）。
        if (sessionFailure?.isAuthFailure() == true || nativeSessionFailure?.isAuthFailure() == true) {
            return AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
                reasonKey = XParseFailure.SESSION_AUTH_FAILED.storageKey, platform = VideoPlatform.X))
        }
        val anonymous = anonymousFailure
        val rejected = sessionFailure
        val withSession = nativeSessionFailure
        return when {
            native is AppResult.Success -> native
            // 登录态已经拿到更准确的原因（例如登录后确认帖子里没有视频），不要退回「需要登录」。
            withSession != null && withSession.isDefinitiveXFailure() &&
                (anonymous == null || anonymous.isGenericEngineFailure()) -> withSession
            // 引擎只会说「不支持/没有视频」时，原生的 typed 原因（没有视频、需要登录）更准确，
            // 不能被兜底引擎的通用结论盖掉。
            native.isDefinitiveXFailure() && (anonymous == null || anonymous.isGenericEngineFailure()) -> native
            anonymous != null -> anonymous
            rejected != null -> rejected
            else -> native
        }
    }

    /** 把引擎结果与原生结果按媒体 ID 合并；原生失败时只保留引擎结果，并标回 X 平台。 */
    private fun mergedX(url: String, native: AppResult<ParsedVideos>, extracted: AppResult<ParsedVideos>): AppResult<ParsedVideos> =
        when {
            native is AppResult.Success && extracted is AppResult.Success ->
                AppResult.Success(XVideoResultMerger.merge(url, native.value, extracted.value) ?: native.value)
            native is AppResult.Success -> native
            extracted is AppResult.Success ->
                AppResult.Success(XVideoResultMerger.merge(url, null, extracted.value) ?: extracted.value)
            else -> extracted
        }

    /** 原生结果是否明确要求登录（嵌入页回退也失败、或 X 用 tombstone 表示需要登录时才会这样）。 */
    private fun AppResult<ParsedVideos>.needsLogin(): Boolean = this is AppResult.Failure && when (val error = error) {
        is AppError.Parse -> error.reasonKey == XParseFailure.LOGIN_REQUIRED.storageKey ||
            error.reasonKey == XParseFailure.RESTRICTED.storageKey
        is AppError.Http -> error.code == 401 || error.code == 403
        else -> false
    }

    /** 原生解析给出、值得直接告诉用户的原因；比引擎的通用「不支持」更准确。 */
    private fun AppResult<ParsedVideos>.isDefinitiveXFailure(): Boolean {
        val error = if (this is AppResult.Failure) error else return false
        val key = (error as? AppError.Parse)?.reasonKey ?: return false
        return key in DEFINITIVE_X_REASON_KEYS
    }

    /** 引擎只说「不支持 / 没有视频」时，它的结论不该盖掉原生的 typed 原因。 */
    private fun AppResult<ParsedVideos>.isGenericEngineFailure(): Boolean =
        this is AppResult.Failure &&
            error.failureKind in setOf(DownloadFailureKind.UNSUPPORTED, DownloadFailureKind.NO_VIDEO)

    /** 引擎说「需要登录」时才会被判定为登录态被拒（403 访问受限不算过期）。 */
    private fun AppResult<ParsedVideos>.isAuthFailure(): Boolean =
        this is AppResult.Failure && error.failureKind == DownloadFailureKind.LOGIN_REQUIRED

    /**
     * B 站：先归一化链接（含 b23.tv 展开），再交给随包引擎解析。
     *
     * 第一版只支持单个视频页与分 P；番剧、直播、课程、音频、空间/频道列表返回「不支持」而不是
     * 伪装成网络失败。合集枚举由 `GenericVideoRepository` 的「解析合集」开关决定（最多 20 P）。
     */
    private suspend fun parseBilibili(url: String): AppResult<ParsedVideos> {
        val link = when (val parsed = BilibiliLink.parse(url)) {
            is BilibiliLinkResult.Page -> parsed.link
            is BilibiliLinkResult.ShortLink -> resolveBilibiliShortLink?.invoke(parsed.url)
                ?: return AppResult.Failure(
                    AppError.InvalidLink("", reasonKey = "bilibili:short_unresolved"),
                )
            BilibiliLinkResult.Unsupported -> return AppResult.Failure(
                AppError.InvalidLink("", reasonKey = "bilibili:unsupported"),
            )
        }
        val engine = fallback ?: return AppResult.Failure(
            AppError.Parse("", reasonKey = "bilibili:missing_engine", platform = VideoPlatform.BILIBILI),
        )
        // 有登录态时先试登录态，失败再退回匿名：匿名通常仍能拿到低清结果，不能因为登录态坏了就整次失败。
        val session = bilibiliSession
        if (session != null) {
            val withSession = try {
                session.parseWithSession(link.url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                withSession is AppResult.Success -> return AppResult.Success(retagBilibili(withSession.value, link))
                withSession is AppResult.Failure && withSession.isAuthFailure() -> {
                    val anonymous = engine.parseVideos(link.url)
                    return if (anonymous is AppResult.Success) AppResult.Success(retagBilibili(anonymous.value, link))
                    else AppResult.Failure(
                        AppError.Parse(
                            "",
                            com.videodown.douyin.domain.model.DownloadFailureKind.LOGIN_REQUIRED,
                            reasonKey = com.videodown.douyin.data.remote.BilibiliSession.SESSION_AUTH_FAILED,
                            platform = VideoPlatform.BILIBILI,
                        ),
                    )
                }
                // 其它失败照常上报（引擎已经给了具体原因），不再多跑一次匿名。
                withSession is AppResult.Failure -> return withSession
            }
        }
        return when (val result = engine.parseVideos(link.url)) {
            is AppResult.Success -> AppResult.Success(retagBilibili(result.value, link))
            is AppResult.Failure -> result
        }
    }

    /** 引擎不知道 B 站是哪个平台：在仓库边界统一标注，并保证来源地址是归一化后的视频页。 */
    private fun retagBilibili(parsed: ParsedVideos, link: BilibiliVideoLink): ParsedVideos = ParsedVideos(
        videos = parsed.videos.map { video ->
            video.copy(
                platform = VideoPlatform.BILIBILI,
                shareUrl = link.url,
                variants = video.variants.map { variant ->
                    variant.copy(referer = variant.referer ?: VideoPlatform.BILIBILI.referer)
                },
            )
        },
        selectedIndex = parsed.selectedIndex,
    )

    private suspend fun parseWeibo(url: String): AppResult<ParsedVideos> {
        val id = VideoLinkExtractor.weiboId(url) ?: VideoLinkExtractor.weiboId(social.page(url, mobile = false).url)
            ?: return AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:weibo_link"))
        val response = social.weiboStatus(id)
        if (response.status !in 200..299) return unavailable(VideoPlatform.WEIBO, response.status)
        val videos = WeiboJsonParser().parseVideos(response.body, id)
        return videos?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Parse("", reasonKey = "weibo:no_mp4"))
    }

    private suspend fun parseKuaishou(url: String): AppResult<ParsedVideos> {
        val response = social.page(url)
        val parser = KuaishouJsonParser()
        val videos = parser.parseVideos(response.body, response.url).filter { it.variants.isNotEmpty() }
        if (videos.isNotEmpty()) return AppResult.Success(ParsedVideos(videos))
        val id = VideoLinkExtractor.kuaishouId(response.url) ?: VideoLinkExtractor.kuaishouId(url)
        if (id != null && !response.url.contains("/fw/photo/")) {
            val shared = social.page("https://v.m.chenzhongtech.com/fw/photo/$id")
            if (shared.status !in 200..299) return unavailable(VideoPlatform.KUAISHOU, shared.status)
            return parsed(parser.parseVideos(shared.body, shared.url).filter { it.variants.isNotEmpty() }, VideoPlatform.KUAISHOU)
        }
        if (response.status !in 200..299) return unavailable(VideoPlatform.KUAISHOU, response.status)
        return parsed(videos, VideoPlatform.KUAISHOU)
    }

    private fun parsed(videos: List<DouyinVideo>, platform: VideoPlatform): AppResult<ParsedVideos> =
        if (videos.isNotEmpty()) AppResult.Success(ParsedVideos(videos))
        else AppResult.Failure(AppError.Parse("", platform = platform, reasonKey = "platform:no_mp4"))

    private fun unavailable(platform: VideoPlatform, status: Int) = AppResult.Failure(
        // The status is carried as a number and the platform as an enum; the UI words both.
        AppError.Http(status, platform = platform, failureKind = DownloadFailureKind.LOGIN_REQUIRED),
    )
}
