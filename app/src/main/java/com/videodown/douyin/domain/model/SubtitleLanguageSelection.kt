package com.videodown.douyin.domain.model

/**
 * The subtitle track a download should keep, plus how to describe it.
 *
 * The app picks languages rather than listing every track the source offers: a page can advertise
 * dozens (YouTube auto-captions reach into the hundreds), and fetching all of them would turn a
 * video download into a subtitle archive.
 */
data class SubtitleSelection(
    val languages: List<String>,
    val automatic: Boolean = false,
) {
    val available: Boolean get() = languages.isNotEmpty()

    companion object {
        val NONE = SubtitleSelection(emptyList(), false)
    }
}

/** A language the UI can name; anything else keeps its raw tag. */
enum class SubtitleLanguageKey { TRADITIONAL_CHINESE, CHINESE, ENGLISH, JAPANESE, KOREAN, OTHER }

object SubtitleLanguageSelection {
    /**
     * Preference order, most specific first. `zh-Hans` before `zh` because a plain `zh` track is
     * often Traditional in practice, and the app's own copy is Simplified.
     */
    private val priority = listOf(
        "zh-Hans", "zh-CN", "zh-SG", "zh", "zh-Hant", "zh-TW", "zh-HK", "zh-MO",
        "en-US", "en-GB", "en",
    )

    /**
     * Language-shaped keys only: `live_chat` is a chat replay the extractor reports next to real
     * subtitles, and it must never end up in the file. Accepts `zh`, `zh-Hans`, `pt-BR`.
     */
    private val languageTag = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*$")

    private fun usable(languages: List<String>) = languages
        .map { it.trim() }
        .filter { languageTag.matches(it) }

    private fun rank(language: String): Int = priority.indexOfFirst {
        language.equals(it, ignoreCase = true) || language.startsWith("$it-", ignoreCase = true)
    }.let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * Real subtitles first; machine captions only when the source has no real track at all.
     *
     * At most one Chinese and one English track are kept — the two languages a viewer of this app
     * is most likely to read — and otherwise the first language the source offers.
     */
    fun preferred(subtitles: List<String>, automaticCaptions: List<String>): SubtitleSelection {
        real(subtitles)?.let { return SubtitleSelection(it, automatic = false) }
        real(automaticCaptions)?.let { return SubtitleSelection(it, automatic = true) }
        return SubtitleSelection.NONE
    }

    private fun real(available: List<String>): List<String>? {
        val usable = usable(available).distinct()
        if (usable.isEmpty()) return null
        val chinese = usable.filter { it.startsWith("zh", ignoreCase = true) }.minByOrNull(::rank)
        val english = usable.filter { it.startsWith("en", ignoreCase = true) }.minByOrNull(::rank)
        val chosen = listOfNotNull(chinese, english)
        return chosen.ifEmpty { listOf(usable.first()) }
    }

    /**
     * Which name a track gets, or `OTHER` when only its raw tag describes it.
     *
     * Returns a key rather than a name: the app language decides the wording, and this file is
     * framework-free.
     */
    fun displayKey(language: String): SubtitleLanguageKey = when {
        language.startsWith("zh-Hant", true) || language.startsWith("zh-TW", true) ||
            language.startsWith("zh-HK", true) || language.startsWith("zh-MO", true) -> SubtitleLanguageKey.TRADITIONAL_CHINESE
        language.startsWith("zh", true) -> SubtitleLanguageKey.CHINESE
        language.startsWith("en", true) -> SubtitleLanguageKey.ENGLISH
        language.startsWith("ja", true) -> SubtitleLanguageKey.JAPANESE
        language.startsWith("ko", true) -> SubtitleLanguageKey.KOREAN
        else -> SubtitleLanguageKey.OTHER
    }
}
