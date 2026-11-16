package com.videodown.douyin.data.generic

import com.videodown.douyin.core.TestText
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageMediaTest {

    @Before fun installTextResolver() = TestText.install()

    @Test fun `extension prefers the content type then the url then jpg`() {
        assertEquals("webp", ImageMedia.extensionOf("https://cdn.example/a.jpg", "image/webp"))
        assertEquals("jpg", ImageMedia.extensionOf("https://cdn.example/a.JPEG?token=1", null))
        assertEquals("png", ImageMedia.extensionOf("https://cdn.example/a.png", null))
        assertEquals("jpg", ImageMedia.extensionOf("https://cdn.example/download?id=7", null))
    }

    @Test fun `tiny images are treated as noise only when a dimension is known`() {
        assertTrue(ImageMedia.isNoise(32, 32, null))
        assertFalse(ImageMedia.isNoise(1080, 1920, null))
        assertTrue(ImageMedia.isNoise(null, null, 2048L))
        assertFalse(ImageMedia.isNoise(null, null, 200_000L))
        // 两者都未知时不猜：宁可让用户看到，也不要静默丢掉一张真实图片。
        assertFalse(ImageMedia.isNoise(null, null, null))
    }

    @Test fun `entry carries a stable identity and a direct image download`() {
        val entry = ImageMedia.entry(
            url = "https://wx1.sinaimg.cn/large/a.jpg",
            pageUrl = "https://m.weibo.cn/status/42",
            extension = "jpg",
            width = 1080,
            height = 1440,
            sizeBytes = 123456L,
            referer = "https://weibo.com/",
            userAgent = "UA",
            browserSession = true,
            platform = VideoPlatform.WEIBO,
        )

        assertEquals(VideoPlatform.WEIBO, entry.platform)
        assertEquals("https://m.weibo.cn/status/42", entry.shareUrl)
        assertEquals("https://wx1.sinaimg.cn/large/a.jpg", entry.coverUrl)
        assertEquals("a", entry.title)
        assertTrue(entry.isImagePost)

        val variant = entry.variants.single()
        assertEquals(MediaKind.IMAGE, variant.kind)
        assertEquals("jpg", variant.extensionOrDefault)
        assertEquals(MediaSourceType.DIRECT, variant.media?.type)
        assertEquals("image", variant.media?.extractor)
        assertEquals("https://weibo.com/", variant.referer)
        assertEquals("UA", variant.media?.userAgent)
        assertTrue(variant.media?.useBrowserSession == true)
        assertEquals(entry.awemeId, variant.media?.mediaId)
        assertEquals(entry.awemeId, ImageMedia.entry(
            url = "https://wx1.sinaimg.cn/large/a.jpg",
            pageUrl = "https://m.weibo.cn/status/42",
            extension = "jpg",
        ).awemeId)
        assertNotEquals(entry.awemeId, ImageMedia.entry("https://wx1.sinaimg.cn/large/b.jpg", "", "jpg").awemeId)
    }

    @Test fun `a direct image entry keeps the page url for refreshes`() {
        val entry = ImageMedia.entry("https://cdn.example/photo.png", "https://page.example/post", "png")
        assertEquals("https://page.example/post", entry.variants.single().media?.pageUrl)
        assertEquals("https://page.example/post", entry.shareUrl)
        assertFalse(entry.title.isBlank())
    }
}
