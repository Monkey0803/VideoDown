package com.videodown.douyin.data.backup

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkListTest {

    private fun record(
        id: String,
        sourcePageUrl: String = "",
        sourceUrl: String = "",
        pageUrl: String? = null,
        private: Boolean = false,
    ) = DownloadRecord(
        id = id, awemeId = id, title = "标题-$id",
        sourcePageUrl = sourcePageUrl, sourceUrl = sourceUrl,
        media = pageUrl?.let { MediaDownload(pageUrl = it) },
        isPrivate = private,
    )

    @Test
    fun `the exported file carries a comment header and one link per line`() {
        val text = LinkList.format(listOf(
            record("a", sourcePageUrl = "https://v.douyin.com/aaa/"),
            record("b", sourcePageUrl = "https://v.douyin.com/bbb/"),
        ))
        assertEquals(listOf(LinkList.HEADER, "https://v.douyin.com/aaa/", "https://v.douyin.com/bbb/"), text.trim().lines())
    }

    @Test
    fun `the source page wins, then the media page, then the direct url`() {
        assertEquals("https://page/1", LinkList.sourceUrl(record("a", sourcePageUrl = "https://page/1", sourceUrl = "https://cdn/1", pageUrl = "https://media/1")))
        assertEquals("https://media/1", LinkList.sourceUrl(record("a", sourceUrl = "https://cdn/1", pageUrl = "https://media/1")))
        assertEquals("https://cdn/1", LinkList.sourceUrl(record("a", sourceUrl = "https://cdn/1")))
    }

    @Test
    fun `a record with no http link is skipped instead of written blank`() {
        val text = LinkList.format(listOf(
            record("a", sourceUrl = "file:///tmp/x.mp4"),
            record("b"),
            record("c", sourcePageUrl = "https://v.douyin.com/ccc/"),
        ))
        assertEquals(listOf(LinkList.HEADER, "https://v.douyin.com/ccc/"), text.trim().lines())
    }

    @Test
    fun `duplicate links are written once, in history order`() {
        val text = LinkList.format(listOf(
            record("a", sourcePageUrl = "https://v.douyin.com/same/"),
            record("b", sourcePageUrl = "https://v.douyin.com/other/"),
            record("c", sourcePageUrl = "https://v.douyin.com/same/"),
        ))
        assertEquals(listOf(LinkList.HEADER, "https://v.douyin.com/same/", "https://v.douyin.com/other/"), text.trim().lines())
    }

    @Test
    fun `an exported file reads back as exactly the same links`() {
        val records = listOf(
            record("a", sourcePageUrl = "https://v.douyin.com/aaa/"),
            record("b", sourcePageUrl = "https://m.weibo.cn/status/RklMe8iOM"),
        )
        assertEquals(listOf("https://v.douyin.com/aaa/", "https://m.weibo.cn/status/RklMe8iOM"), LinkList.parse(LinkList.format(records)))
    }

    @Test
    fun `comments and prose are tolerated and duplicates collapse`() {
        val imported = LinkList.parse(
            """
            # 手工整理的清单
            https://v.douyin.com/aaa/ 复制此链接
            这是分享文案 https://v.douyin.com/aaa/
            https://v.douyin.com/bbb/
            """.trimIndent(),
        )
        assertEquals(listOf("https://v.douyin.com/aaa/", "https://v.douyin.com/bbb/"), imported)
    }

    @Test
    fun `text without links imports as nothing`() {
        assertTrue(LinkList.parse(null).isEmpty())
        assertTrue(LinkList.parse("").isEmpty())
        assertTrue(LinkList.parse("# 只有注释").isEmpty())
        assertTrue(LinkList.parse("中午吃什么").isEmpty())
    }

    @Test fun `a private record's link never leaves the device in a list`() {
        val text = LinkList.format(listOf(
            record("a", sourcePageUrl = "https://v.douyin.com/public/"),
            record("p", sourcePageUrl = "https://v.douyin.com/secret/", private = true),
        ))
        assertEquals(listOf(LinkList.HEADER, "https://v.douyin.com/public/"), text.trim().lines())
    }
}