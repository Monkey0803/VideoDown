package com.videodown.douyin.domain.repository

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.map
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos

interface VideoRepository {
    suspend fun parseShareText(shareText: String): AppResult<DouyinVideo>
    suspend fun parseUrl(url: String): AppResult<DouyinVideo> = parseShareText(url)
    suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> =
        parseShareText(shareText).map { ParsedVideos(listOf(it)) }
}
