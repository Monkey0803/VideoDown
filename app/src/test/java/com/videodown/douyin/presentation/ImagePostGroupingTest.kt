package com.videodown.douyin.presentation

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagePostGroupingTest {
    private fun image(id: String, page: String) = DownloadRecord(
        id = id,
        awemeId = "${page}_$id",
        title = "帖子",
        filePath = "/images/$id.jpg",
        mediaKind = MediaKind.IMAGE,
        sourcePageUrl = page,
    )

    @Test fun `images from the same post share one group size`() {
        val records = listOf(image("a", "post-1"), image("b", "post-1"), image("c", "post-2"))

        val sizes = imagePostSizes(records)

        assertEquals(2, sizes[imagePostKey(records[0])])
        assertEquals(1, sizes[imagePostKey(records[2])])
    }

    @Test fun `position reports the place inside the post`() {
        val records = listOf(image("a", "post-1"), image("b", "post-1"))

        assertEquals(1 to 2, imagePostPosition(records, records[0]))
        assertEquals(2 to 2, imagePostPosition(records, records[1]))
    }

    @Test fun `a single-image post has no position badge`() {
        val records = listOf(image("a", "post-1"), image("b", "post-2"))

        assertNull(imagePostPosition(records, records[0]))
    }

    @Test fun `records without a source page are not merged together`() {
        val records = listOf(
            image("a", ""),
            image("b", ""),
        )

        val sizes = imagePostSizes(records)

        assertEquals(1, sizes[imagePostKey(records[0])])
        assertEquals(1, sizes[imagePostKey(records[1])])
    }
}
