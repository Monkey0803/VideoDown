package com.videodown.douyin.data.generic

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.ParsedVideos

/**
 * 用本机 WebView 保存的登录态解析一个页面。
 *
 * 返回 `null` 表示「没有可用登录态」，与「有登录态但解析失败」是两件事：
 * 前者应当直接跳过、不影响匿名路径，后者要把失败上报给用户。
 *
 * 放在 data 层而不是 domain：实现要碰 Android WebView 与随包引擎，domain 保持无框架依赖。
 */
fun interface SessionVideoParser {
    suspend fun parseWithSession(url: String): AppResult<ParsedVideos>?
}
