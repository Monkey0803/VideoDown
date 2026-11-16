package com.videodown.douyin.data.repository

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.util.Log
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.map
import com.videodown.douyin.core.toAppError
import com.videodown.douyin.data.remote.DouyinApiClient
import com.videodown.douyin.data.remote.DouyinJsonParser
import com.videodown.douyin.data.remote.DouyinLinkExtractor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.repository.DouyinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves Douyin share text into video or image-post metadata.
 *
 * Resolution is tried against several sources in order of reliability, because any single
 * Douyin endpoint tends to break for weeks at a time:
 *
 *  1. the anonymous share page (no signature required, most reliable) — `/share/video/` for a
 *     video link and `/share/note/` for an image post, with the other path as a fallback;
 *  2. the signed web detail endpoint (works when a valid cookie/signature is present);
 *  3. the legacy v2 `iteminfo` endpoint (kept only as a last resort).
 */
class DouyinRepositoryImpl(
    private val api: DouyinApiClient,
    private val parser: DouyinJsonParser = DouyinJsonParser(),
) : DouyinRepository {

    override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> =
        parseVideos(shareText).map { it.selectedVideo }

    override suspend fun parseUrl(url: String): AppResult<DouyinVideo> = parseShareText(url)

    override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> = withContext(Dispatchers.IO) {
        val trimmed = shareText.trim()
        if (trimmed.isEmpty()) {
            return@withContext AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:douyin_link"))
        }

        val awemeId = resolveAwemeId(trimmed)
            ?: return@withContext AppResult.Failure(
                AppError.InvalidLink("", reasonKey = "input:douyin_unrecognised"),
            )

        var videos: List<DouyinVideo> = emptyList()

        // Strategy 1: anonymous share page. 图文链接先试 /share/note/，其余先试 /share/video/。
        val pageOrders = if (DouyinLinkExtractor.isNoteLink(trimmed)) listOf(true, false) else listOf(false, true)
        for (note in pageOrders) {
            if (videos.isNotEmpty()) break
            videos = runCatching { api.fetchSharePageHtml(awemeId, note) }
                .onFailure { Log.w(TAG, AppText.get(R.string.log_share_page_failed, it.message.orEmpty())) }
                .getOrNull()
                ?.let { parser.parseSharePageVideos(it) }
                ?: emptyList()
        }

        // Strategy 2: signed web detail endpoint.
        if (videos.isEmpty()) {
            videos = runCatching { api.fetchWebDetailJson(awemeId) }
                .onFailure { Log.w(TAG, AppText.get(R.string.log_web_detail_failed, it.message.orEmpty())) }
                .getOrNull()
                ?.let { parser.parseJsonVideos(it) }
                ?: emptyList()
        }

        // Strategy 3: legacy v2 endpoint.
        if (videos.isEmpty()) {
            videos = runCatching { api.fetchLegacyItemInfoJson(awemeId) }
                .onFailure { Log.w(TAG, AppText.get(R.string.log_legacy_iteminfo_failed, it.message.orEmpty())) }
                .getOrNull()
                ?.let { parser.parseJsonVideos(it) }
                ?: emptyList()
        }

        if (videos.isEmpty()) {
            return@withContext AppResult.Failure(
                // The id is data passed to the sentence, not part of the copy.
                AppError.Parse("", reasonKey = "douyin:id_unresolved", detail = awemeId),
            )
        }
        // 丢掉没有可用地址的条目，避免结果页出现点不动的空条目。
        val usable = videos.filter { it.variants.isNotEmpty() }
        if (usable.isEmpty()) {
            AppResult.Failure(AppError.Parse("", reasonKey = "douyin:no_video"))
        } else {
            AppResult.Success(ParsedVideos(usable))
        }
    }

    /** Extracts the numeric aweme id, following a `v.douyin.com` redirect when necessary. */
    private fun resolveAwemeId(text: String): String? {
        DouyinLinkExtractor.extractAwemeId(text)?.let { return it }

        val shareUrl = DouyinLinkExtractor.extractShareUrl(text) ?: return null
        if (!DouyinLinkExtractor.isShortLink(shareUrl)) return null

        val expanded = runCatching { api.expandShortLink(shareUrl) }
            .onFailure { Log.w(TAG, AppText.get(R.string.log_short_link_failed, it.message.orEmpty())) }
            .getOrDefault(shareUrl)

        return DouyinLinkExtractor.extractAwemeId(expanded)
            ?: DouyinLinkExtractor.extractAwemeId(api.expandShortLink(expanded))
    }

    /** Converts an unexpected throwable into a user-facing failure. */
    @Suppress("unused")
    private fun Throwable.asFailure(): AppResult.Failure = AppResult.Failure(toAppError())

    private companion object {
        const val TAG = "DouyinRepository"
    }
}
