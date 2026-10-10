package com.videodown.douyin.data.repository

import com.videodown.douyin.core.AppError
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.map
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
                VideoPlatform.KUAISHOU -> parseKuaishou(link.url).map { ParsedVideos(listOf(it)) }
                VideoPlatform.X -> return@withContext parseX(link.url)
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

    private suspend fun parseX(url: String): AppResult<ParsedVideos> {
        val native = x.parse(url)
        val supplemental = fallback ?: return native
        val extracted = try {
            supplemental.parseVideos(url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return native
        }
        return when {
            native is AppResult.Success && extracted is AppResult.Success ->
                AppResult.Success(XVideoResultMerger.merge(url, native.value, extracted.value) ?: native.value)
            native is AppResult.Success -> native
            extracted is AppResult.Success ->
                AppResult.Success(XVideoResultMerger.merge(url, null, extracted.value) ?: extracted.value)
            else -> extracted
        }
    }

    private suspend fun parseWeibo(url: String): AppResult<ParsedVideos> {
        val id = VideoLinkExtractor.weiboId(url) ?: VideoLinkExtractor.weiboId(social.page(url, mobile = false).url)
            ?: return AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:weibo_link"))
        val response = social.weiboStatus(id)
        if (response.status !in 200..299) return unavailable(VideoPlatform.WEIBO, response.status)
        val videos = WeiboJsonParser().parseVideos(response.body, id)
        return videos?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Parse("", reasonKey = "weibo:no_mp4"))
    }

    private suspend fun parseKuaishou(url: String): AppResult<DouyinVideo> {
        val response = social.page(url)
        val parser = KuaishouJsonParser()
        val video = parser.parse(response.body, response.url)
        if (video?.variants?.isNotEmpty() == true) return AppResult.Success(video)
        val id = VideoLinkExtractor.kuaishouId(response.url) ?: VideoLinkExtractor.kuaishouId(url)
        if (id != null && !response.url.contains("/fw/photo/")) {
            val shared = social.page("https://v.m.chenzhongtech.com/fw/photo/$id")
            if (shared.status !in 200..299) return unavailable(VideoPlatform.KUAISHOU, shared.status)
            return parsed(parser.parse(shared.body, shared.url), VideoPlatform.KUAISHOU)
        }
        if (response.status !in 200..299) return unavailable(VideoPlatform.KUAISHOU, response.status)
        return parsed(video, VideoPlatform.KUAISHOU)
    }

    private fun parsed(video: DouyinVideo?, platform: VideoPlatform): AppResult<DouyinVideo> =
        if (video != null && video.variants.isNotEmpty()) AppResult.Success(video)
        else AppResult.Failure(AppError.Parse("", platform = platform, reasonKey = "platform:no_mp4"))

    private fun unavailable(platform: VideoPlatform, status: Int) = AppResult.Failure(
        // The status is carried as a number and the platform as an enum; the UI words both.
        AppError.Http(status, platform = platform, failureKind = DownloadFailureKind.LOGIN_REQUIRED),
    )
}
