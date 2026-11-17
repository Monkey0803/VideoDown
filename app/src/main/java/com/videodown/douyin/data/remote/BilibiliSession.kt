package com.videodown.douyin.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * E3：B 站应用内登录态。
 *
 * 登录态完全由系统 WebView 的 `CookieManager` 持有并持久化：以 `SESSDATA` 是否存在来判断
 * 「本机现在有没有登录」，**不设自己的过期时间**，也不读取外部浏览器的 Cookie。
 * 登录只用于访问账号本来就能播放的内容，不绕过大会员、付费或权限限制。
 */
object BilibiliSession {
    /** 登录页：只在本应用内打开，用户手动登录。 */
    const val LOGIN_URL = "https://passport.bilibili.com/login"

    /** 引擎解析/下载时需要的 Cookie 来源；登录态只导出给这些 B 站主机。 */
    val cookieOrigins = listOf("https://www.bilibili.com/", "https://api.bilibili.com/")

    /** 登录被服务端拒绝时的原因 key；界面据此提示重新登录。 */
    const val SESSION_AUTH_FAILED = "bilibili:session_auth_failed"

    private const val SESSDATA = "SESSDATA"

    /** Cookie 头里是否有非空的 `SESSDATA`。 */
    fun signedIn(cookieHeader: String?): Boolean = cookieHeader
        ?.split(';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.any { pair ->
            pair.substringBefore('=').trim() == SESSDATA && pair.substringAfter('=', "").trim().isNotEmpty()
        } == true

    /** 页面本身属于 B 站时才附带登录态；其它站点一律不导出这份 Cookie（Referer 不参与判断）。 */
    fun appliesTo(pageUrl: String): Boolean =
        listOf(pageUrl).mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }.any { host ->
            host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" || host.endsWith(".b23.tv")
        }

    /**
     * 解析顺序：有登录态时先试登录态，失败再退回匿名。
     *
     * 匿名在 B 站通常仍能拿到低清结果，所以登录态只用来「多拿一点」，不能因为它坏了就让整次解析失败。
     */
    fun preferSession(signedIn: Boolean): Boolean = signedIn
}
