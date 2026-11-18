package com.videodown.douyin.data.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页候选聚合：同一张图的多个格式/尺寸合并成一行，视频绝不合并。
 *
 * 对应用户在 x.com 帖子里看到的四行「Image · PNG / WEBP / JPG / JPG」——它们其实是同一张图。
 */
class MediaCandidateGroupingTest {

    private val page = "https://x.com/someone/status/1"
    private val ua = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36"

    private fun candidate(
        url: String,
        extension: String? = null,
        width: Int? = 1300,
        height: Int? = 1300,
        sizeBytes: Long? = 245_000L,
        type: String = BrowserMediaDetector.IMAGE,
    ) = BrowserMediaDetector.Candidate(url, type, page, ua, extension, width, height, sizeBytes)

    private fun image(url: String, extension: String, width: Int = 1300, height: Int = 1300, size: Long = 245_000L) =
        candidate(url, extension, width, height, size)

    private fun video(url: String) = candidate(url, extension = null, width = null, height = null, sizeBytes = null,
        type = "MP4")

    @Test
    fun `one X photo with four format URLs becomes a single group`() {
        val groups = groupCandidates(listOf(
            image("https://pbs.twimg.com/media/ABC123?format=png&name=large", "png", size = 900_000),
            image("https://pbs.twimg.com/media/ABC123?format=webp&name=large", "webp", size = 300_000),
            image("https://pbs.twimg.com/media/ABC123?format=jpg&name=large", "jpg", size = 245_000),
            image("https://pbs.twimg.com/media/ABC123?format=jpg&name=small", "jpg", size = 90_000),
        ))

        assertEquals(1, groups.size)
        val group = groups.single()
        assertTrue(group.isImage)
        // 每种扩展名只留一个变体：两张 jpg 合成一张。
        assertEquals(listOf("png", "webp", "jpg"), group.variants.map { it.extension })
        // 面积相同，PNG 无损优先。
        assertEquals("png", group.representative.extension)
        // 缩略图取组内已知最小字节数的变体（同扩展名的「小图」已按质量去重，不会留下）。
        assertEquals(245_000L, group.thumbnail.sizeBytes)
    }

    @Test
    fun `different paths stay separate even with identical query parameters`() {
        val groups = groupCandidates(listOf(
            image("https://pbs.twimg.com/media/AAA?format=jpg&name=large", "jpg"),
            image("https://pbs.twimg.com/media/BBB?format=jpg&name=large", "jpg"),
        ))
        assertEquals(2, groups.size)
    }

    @Test
    fun `only format parameters are ignored and other query parameters still identify the media`() {
        val sameAsset = groupCandidates(listOf(
            image("https://cdn.example.com/a/photo.jpg?w=640&format=webp", "webp", width = 640, height = 640),
            image("https://cdn.example.com/a/photo.jpg?w=1280&format=jpg", "jpg", width = 1280, height = 1280),
        ))
        assertEquals(1, sameAsset.size)
        assertEquals(1280, sameAsset.single().representative.width)

        val differentAsset = groupCandidates(listOf(
            image("https://cdn.example.com/a/photo.jpg?id=1", "jpg"),
            image("https://cdn.example.com/a/photo.jpg?id=2", "jpg"),
        ))
        assertEquals(2, differentAsset.size)
    }

    @Test
    fun `videos never merge and are listed before images`() {
        val groups = groupCandidates(listOf(
            image("https://cdn.example.com/photo.jpg", "jpg"),
            video("https://cdn.example.com/clip-720.mp4"),
            video("https://cdn.example.com/clip-1080.mp4"),
            video("https://cdn.example.com/clip-720.mp4"),
        ))
        assertEquals(3, groups.size)
        assertEquals(listOf(false, false, true), groups.map { it.isImage })
        assertEquals(listOf("clip-720", "clip-1080"), groups.filterNot { it.isImage }.map {
            it.representative.url.substringAfterLast('/').substringBefore('.')
        })
    }

    @Test
    fun `a larger resolution wins over a better format`() {
        val group = groupCandidates(listOf(
            image("https://cdn.example.com/photo.png?w=800", "png", width = 800, height = 800),
            image("https://cdn.example.com/photo.jpg?w=2400", "jpg", width = 2400, height = 2400),
        )).single()
        assertEquals("jpg", group.representative.extension)
        assertEquals(2400, group.representative.width)
    }

    @Test
    fun `the best variant of each extension is kept`() {
        val group = groupCandidates(listOf(
            image("https://cdn.example.com/photo.jpg?q=30", "jpg", size = 40_000),
            image("https://cdn.example.com/photo.jpg?q=90", "jpg", size = 400_000),
        )).single()
        assertEquals(1, group.variants.size)
        assertEquals(400_000L, group.variants.single().sizeBytes)
    }

    @Test
    fun `thumbnail falls back to the representative when no size is known`() {
        val group = groupCandidates(listOf(
            image("https://cdn.example.com/photo.webp?name=large", "webp", size = 0L),
        )).single()
        assertSame(group.representative, group.thumbnail)
    }

    @Test
    fun `thumbnail uses the smallest known variant across formats`() {
        val group = groupCandidates(listOf(
            image("https://cdn.example.com/photo.png?name=large", "png", size = 900_000),
            image("https://cdn.example.com/photo.webp?name=small", "webp", width = 640, height = 640, size = 60_000),
        )).single()
        assertEquals(60_000L, group.thumbnail.sizeBytes)
    }

    @Test
    fun `image groups are ordered by area`() {
        val groups = groupCandidates(listOf(
            image("https://cdn.example.com/small.jpg", "jpg", width = 300, height = 300),
            image("https://cdn.example.com/large.jpg", "jpg", width = 2000, height = 1500),
        ))
        assertEquals(listOf(2000, 300), groups.map { it.representative.width })
    }

    @Test
    fun `identity ignores the image extension but keeps the host`() {
        assertEquals(
            identityOf(image("https://cdn.example.com/a/photo.jpg", "jpg")),
            identityOf(image("https://cdn.example.com/a/photo.png?format=png", "png")),
        )
        assertNotEquals(
            identityOf(image("https://cdn.example.com/a/photo.jpg", "jpg")),
            identityOf(image("https://other.example.com/a/photo.jpg", "jpg")),
        )
    }
}
