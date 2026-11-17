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

class MultiPlatformVideoRepository(
    private val douyin: DouyinRepository,
    private val social: SocialVideoHttpClient = SocialVideoHttpClient(),
    private val x: XVideoRepository = XVideoRepository(),
    private val fallback: VideoRepository? = null,
    /** 用本机 WebView 登录态解析，仅当匿名路径都失败时才使用；null 表示该站点不参与。 */
    private val sessionFallback: SessionVideoParser? = null,
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
     * X 的三段式解析：原生匿名 → 随包引擎（匿名）→ 本机登录态重试。
     *
     * 顺序不是固定三段：原生已经说「需要登录」且有登录态时，先跑登录态、再退回匿名，
     * 免得受保护内容白白多跑一次匿名引擎。登录态只在匿名路径拿不到结果时使用，
     * 公开推文因此保持完全匿名，也避免一份陈旧 Cookie 把本来能成功的解析弄坏。
     */
    private suspend fun parseX(url: String): AppResult<ParsedVideos> {
        val native = x.parse(url)
        val supplemental = fallback ?: return native
        val session = sessionFallback
        val sessionFirst = session != null && native.needsLogin()

        var sessionTarget: String? = null
        var sessionFailure: AppResult<ParsedVideos>? = null
        var anonymousFailure: AppResult<ParsedVideos>? = null

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

        val first = if (sessionFirst) attemptSession() else attemptAnonymous()
        if (first is AppResult.Success) return mergedX(sessionTarget ?: url, native, first)
        val second = if (sessionFirst) attemptAnonymous() else attemptSession()
        if (second is AppResult.Success) return mergedX(sessionTarget ?: url, native, second)

        // 登录态尝试被服务端拒绝：这条比引擎的通用授权/网络失败更能指导用户（重新登录）。
        if (sessionFailure?.isAuthFailure() == true) {
            return AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
                reasonKey = XParseFailure.SESSION_AUTH_FAILED.storageKey, platform = VideoPlatform.X))
        }
        val anonymous = anonymousFailure
        val rejected = sessionFailure
        return when {
            native is AppResult.Success -> native
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

    /** 原生结果是否明确要求登录（嵌入页回退也失败时才会这样）。 */
    private fun AppResult<ParsedVideos>.needsLogin(): Boolean = this is AppResult.Failure && when (val error = error) {
        is AppError.Parse -> error.reasonKey == XParseFailure.LOGIN_REQUIRED.storageKey
        is AppError.Http -> error.code == 401 || error.code == 403
        else -> false
    }

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
