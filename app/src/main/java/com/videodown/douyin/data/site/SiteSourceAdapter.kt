package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.SitePageMetadata
import com.videodown.douyin.domain.model.SiteSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** 站点定义与只读 DOM 元数据提取规则；脚本不接受页面或用户输入。 */
data class SiteSourceAdapter(
    val source: SiteSource,
    val metadataJavascript: String = GENERIC_PAGE_METADATA_JAVASCRIPT,
    val interactionJavascript: String? = null,
)

object SitePageMetadataCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** WebView 把 JavaScript 字符串结果再包成一层 JSON 字符串，需要先解一层。 */
    fun decodeJavascriptResult(encoded: String): SitePageMetadata? = runCatching {
        val inner = json.parseToJsonElement(encoded).jsonPrimitive.content
        json.decodeFromString<SitePageMetadata>(inner)
    }.getOrNull()
}

/** 通用 Open Graph / video poster 规则；站点适配器可在此基础上增加自己的选择器。 */
const val GENERIC_PAGE_METADATA_JAVASCRIPT = """
(() => {
  const content = selector => document.querySelector(selector)?.content?.trim() || '';
  const text = selector => document.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() || '';
  const image = selector => {
    const node = document.querySelector(selector);
    const raw = node?.currentSrc || node?.src || node?.poster || node?.content || node?.dataset?.src || '';
    try { return raw ? new URL(raw, location.href).href : ''; } catch (_) { return ''; }
  };
  return JSON.stringify({
    title: content('meta[property="og:title"]') || content('meta[name="twitter:title"]') || text('h1') || document.title,
    coverUrl: image('meta[property="og:image"]') || image('meta[name="twitter:image"]') || image('video[poster]'),
    episodeLabel: '',
    pageUrl: location.href
  });
})()
"""

const val KUBB_PAGE_METADATA_JAVASCRIPT = """
(() => {
  const content = selector => document.querySelector(selector)?.content?.trim() || '';
  const text = selector => document.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() || '';
  const firstText = selectors => selectors.map(text).find(Boolean) || '';
  const image = selector => {
    const node = document.querySelector(selector);
    const raw = node?.currentSrc || node?.src || node?.poster || node?.content || node?.dataset?.src || '';
    try { return raw ? new URL(raw, location.href).href : ''; } catch (_) { return ''; }
  };
  const firstImage = selectors => selectors.map(image).find(Boolean) || '';
  const selection = window.__shiyingSiteSelection || null;
  return JSON.stringify({
    title: selection?.title || firstText(['main h1', '[class*="detail"] h1', '[class*="video"] h1']) ||
      content('meta[property="og:title"]') || content('meta[name="twitter:title"]') || document.title,
    coverUrl: selection?.coverUrl || firstImage(['meta[property="og:image"]', 'meta[name="twitter:image"]', 'video[poster]',
      '[class*="detail"] [class*="cover"] img', '[class*="poster"] img', '[class*="cover"] img']),
    episodeLabel: firstText(['[class*="episode"] .active', '[class*="anthology"] .active',
      '[class*="episode"] [aria-current="true"]', '[class*="play"] [aria-current="true"]']),
    pageUrl: selection?.pageUrl || location.href
  });
})()
"""

/** 在 Angular 导航和媒体请求发生前记录用户点击的影片卡片。 */
const val KUBB_INTERACTION_JAVASCRIPT = """
(() => {
  if (window.__shiyingSelectionBound) return;
  window.__shiyingSelectionBound = true;
  document.addEventListener('click', event => {
    const target = event.target instanceof Element ? event.target : null;
    const anchor = target?.closest('a[href*="/play/"]');
    if (!anchor) return;
    const card = anchor.closest('.video-teaser') || anchor.parentElement;
    const image = anchor.querySelector('img') || card?.querySelector('img');
    const titleLink = card?.querySelector('.title a[href*="/play/"]');
    const title = anchor.getAttribute('title') || titleLink?.textContent?.trim() || image?.alt || anchor.textContent?.trim() || '';
    const rawCover = image?.currentSrc || image?.src || image?.dataset?.src || '';
    try {
      window.__shiyingSiteSelection = {
        title,
        coverUrl: rawCover ? new URL(rawCover, location.href).href : '',
        pageUrl: new URL(anchor.href, location.href).href
      };
    } catch (_) {}
  }, true);
})()
"""
