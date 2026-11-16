package com.videodown.douyin.data.generic

/**
 * 本机 WebView 里保存的 X（Twitter）登录态。
 *
 * 只判断「Cookie 还在不在」，**不判断有效期**：有效期完全由 X 的 `Set-Cookie` 决定，
 * 过期后系统 `CookieManager` 自己就不再返回它，这里因此不需要（也不允许）另设 TTL。
 * 登录时 X 会写入这两个 Cookie，第二个同时是接口要求的 CSRF 头，两者缺一都发不出已登录请求。
 */
internal object XSession {
    /** 内置网页默认打开的登录页。 */
    const val LOGIN_URL = "https://x.com/i/flow/login"

    /** 按域单独查询：`.x.com` 的 Cookie 对两个源都适用，但 `getCookie` 只按 URL 返回。 */
    val origins = listOf("https://x.com/", "https://twitter.com/")

    /** `auth_token` 表示已登录，`ct0` 是 GraphQL 请求的 `x-csrf-token`。 */
    val cookieNames = listOf("auth_token", "ct0")

    /**
     * 一条 `Cookie` 头里是否同时带着两个登录 Cookie 且有非空值。
     *
     * 这是存在性检查，不是有效性检查：被服务端吊销的会话同样会返回 true，
     * 那种情况只能在真正使用时由引擎报告（见 `x:session_auth_failed`）。
     */
    fun signedIn(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val values = cookieHeader.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty() || !pair.contains('=')) return@mapNotNull null
            name to pair.substringAfter('=', "").trim()
        }.toMap()
        return cookieNames.all { values[it]?.isNotEmpty() == true }
    }
}
