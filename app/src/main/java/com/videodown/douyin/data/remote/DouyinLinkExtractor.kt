package com.videodown.douyin.data.remote

/**
 * Pure-Kotlin extraction of Douyin identifiers from arbitrary share text.
 *
 * Douyin's share sheet produces captions such as:
 * `7.42 复制打开抖音，看看【某人的作品】... https://v.douyin.com/iRNBho6u/ 复制此链接...`
 * so the link has to be fished out of surrounding prose, emoji and hashtags.
 */
object DouyinLinkExtractor {

    /** Any http(s) URL inside a blob of text, longest-first so a full link wins over a prefix. */
    private val URL_REGEX = Regex("""https?://[-\w@:%+.,~#?&/=()\[\]!*;]+""", RegexOption.IGNORE_CASE)

    /** Numeric video id in any of Douyin's URL shapes. */
    private val AWEME_PATH_REGEX = Regex(
        """(?:/video/|/share/video/|/note/|/share/note/|aweme_id=|item_ids=|modal_id=)(\d{15,20})""",
        RegexOption.IGNORE_CASE,
    )

    private val SHORT_LINK_REGEX = Regex("""https?://v\.douyin\.com/[A-Za-z0-9_\-]+/?""", RegexOption.IGNORE_CASE)
    private val BARE_ID_REGEX = Regex("""(?<!\d)(7\d{18})(?!\d)""")

    private val DOUYIN_HOST_SUFFIXES = listOf(
        "douyin.com",
        "iesdouyin.com",
        "amemv.com",
    )

    /** True when [text] looks like it could contain a Douyin video reference. */
    fun containsDouyinReference(text: String): Boolean =
        extractShareUrl(text) != null || extractAwemeId(text) != null

    /**
     * Finds the most useful Douyin link in [text]: a `v.douyin.com` short link is preferred
     * because it is unambiguous, otherwise the first Douyin-hosted URL.
     */
    fun extractShareUrl(text: String): String? {
        val trimmed = text.trim()
        if (SHORT_LINK_REGEX.matches(trimmed)) return trimmed.trimEnd('/') + "/"

        val urls = URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', '，', '。', ')', '）') }.toList()
        if (urls.isEmpty()) return null

        return urls.firstOrNull { SHORT_LINK_REGEX.matches(it) }
            ?: urls.firstOrNull { url -> DOUYIN_HOST_SUFFIXES.any { url.contains(it, ignoreCase = true) } }
    }

    /** Pulls a 15–20 digit aweme id out of a URL, a share caption, or a bare id. */
    fun extractAwemeId(text: String): String? {
        AWEME_PATH_REGEX.find(text)?.let { return it.groupValues[1] }
        extractShareUrl(text)?.let { url ->
            AWEME_PATH_REGEX.find(url)?.let { return it.groupValues[1] }
        }
        return BARE_ID_REGEX.find(text)?.groupValues?.get(1)
    }

    /** True when the link needs one HTTP round-trip before an aweme id can be read from it. */
    fun isShortLink(url: String): Boolean = SHORT_LINK_REGEX.matches(url.trim())

    /** Best-effort account id extraction, used when the user pastes a profile link. */
    fun extractSecUid(text: String): String? =
        Regex("""/user/([A-Za-z0-9_\-]{20,})""").find(text)?.groupValues?.get(1)
}
