package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform

/** Clipboard text holding a link for a known platform that has not been offered yet. */
data class ClipboardCandidate(val text: String, val platform: VideoPlatform, val url: String)

/**
 * Decides whether clipboard text is worth offering to parse when 拾影 comes back to the foreground.
 *
 * Only a link for a known platform qualifies. A generic `http(s)` URL is far too easy to copy by
 * accident — a browser address, a file link, a password-reset mail — so it must never raise an
 * unrequested prompt; that also matches how 同类应用 bound their clipboard listeners to supported
 * sites. The dismissed link is suppressed, so an explicit 「忽略」 survives the next window focus
 * instead of being undone by it.
 */
object ClipboardLinkPolicy {
    fun candidate(text: String?, lastOfferedUrl: String?): ClipboardCandidate? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val link = VideoLinkExtractor.extract(raw) ?: return null
        if (link.platform == VideoPlatform.GENERIC) return null
        if (link.url == lastOfferedUrl?.trim()) return null
        return ClipboardCandidate(raw, link.platform, link.url)
    }
}
