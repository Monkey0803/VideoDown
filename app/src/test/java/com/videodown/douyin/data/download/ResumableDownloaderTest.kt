package com.videodown.douyin.data.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [ResumableDownloader] against a real HTTP server that implements
 * `Range`/`Content-Range`, so resumption is verified end-to-end rather than mocked.
 */
class ResumableDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val servers = mutableListOf<RangeAwareTestServer>()

    /** Deterministic payload large enough to span several buffer reads. */
    private val payload: ByteArray = ByteArray(300_000) { index -> ((index * 31 + 7) % 251).toByte() }

    private val downloader = ResumableDownloader(
        OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    )

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(
        truncateFirstResponse: Boolean = false,
        supportRanges: Boolean = true,
        statusCode: Int = 200,
    ): RangeAwareTestServer =
        RangeAwareTestServer(
            payload = payload,
            truncateFirstResponse = truncateFirstResponse,
            supportRanges = supportRanges,
            statusCode = statusCode,
        ).also { servers.add(it) }

    private fun request(
        server: RangeAwareTestServer,
        partial: java.io.File,
        final: java.io.File,
        alreadyDownloaded: Long = 0L,
        etag: String? = null,
        onProgress: suspend (Long, Long, Long, Boolean, String?) -> Unit = { _, _, _, _, _ -> },
    ) = ResumableDownloader.DownloadRequest(
        url = "http://127.0.0.1:${server.port}/video.mp4",
        partialFile = partial,
        finalFile = final,
        alreadyDownloaded = alreadyDownloaded,
        etag = etag,
        onProgress = onProgress,
    )

    @Test fun `disk full preserves partial data and reports a storage failure`() = runBlocking {
        val srv = server()
        val partial = tempFolder.newFile("space.part")
        val final = java.io.File(tempFolder.root, "space.mp4")
        val outcome = downloader.download(request(srv, partial, final, onProgress = { _, _, _, _, _ ->
            throw java.io.IOException("write failed: ENOSPC (No space left on device)")
        }))
        assertTrue(outcome is ResumableDownloader.Outcome.Failed)
        assertTrue((outcome as ResumableDownloader.Outcome.Failed).error is com.videodown.douyin.core.AppError.Storage)
        assertTrue(partial.length() > 0)
        assertFalse(final.exists())
    }

    @Test fun `416 without a matching remote length cannot complete a partial file`() = runBlocking {
        val srv = server(statusCode = 416)
        val partial = tempFolder.newFile("invalid.part").apply { writeBytes(payload.copyOf(1024)) }
        val final = java.io.File(tempFolder.root, "invalid.mp4")
        val outcome = downloader.download(request(srv, partial, final, 1024))
        assertTrue(outcome is ResumableDownloader.Outcome.Failed)
        assertFalse(final.exists())
        assertEquals(1024, partial.length())
    }

    @Test fun `416 complete partial reserves fallback copy before writing`() = runBlocking {
        val srv = server(statusCode = 416)
        val partial = tempFolder.newFile("complete.part").apply { writeBytes(payload) }
        // A nonempty destination directory forces rename to fail without depending on filesystems.
        val final = tempFolder.newFolder("complete.mp4")
        java.io.File(final, "keep").writeText("keep")
        val checked = mutableListOf<Long>()
        val outcome = downloader.download(request(srv, partial, final, payload.size.toLong()).copy(
            checkStorage = { _, through ->
                checked.add(through)
                if (through > payload.size) throw InsufficientStorageException(spaceFailure(0, 1)!!)
            },
        ))
        assertTrue(outcome is ResumableDownloader.Outcome.Failed)
        assertEquals(com.videodown.douyin.domain.model.DownloadFailureKind.STORAGE_FULL,
            (outcome as ResumableDownloader.Outcome.Failed).error.failureKind)
        assertEquals(listOf(payload.size.toLong(), payload.size.toLong() * 2), checked)
        assertArrayEquals(payload, partial.readBytes())
        assertTrue(java.io.File(final, "keep").exists())
    }

    @Test
    fun `downloads the whole file in one pass`() = runBlocking {
        val srv = server()
        val partial = tempFolder.newFile("one.part")
        val final = tempFolder.newFile("one.mp4").also { it.delete() }

        val outcome = downloader.download(request(srv, partial, final))

        val completed = outcome as ResumableDownloader.Outcome.Completed
        assertEquals(payload.size.toLong(), completed.totalBytes)
        assertArrayEquals(payload, final.readBytes())
        assertFalse("partial file should be gone", partial.exists())
    }

    @Test
    fun `resumes from the bytes already on disk`() = runBlocking {
        val srv = server()
        val partial = tempFolder.newFile("resume.part")
        val final = tempFolder.newFile("resume.mp4").also { it.delete() }

        val alreadyOnDisk = 120_000L
        partial.writeBytes(payload.copyOfRange(0, alreadyOnDisk.toInt()))

        val outcome = downloader.download(
            request(srv, partial, final, alreadyDownloaded = alreadyOnDisk, etag = "\"fixture-etag\""),
        )

        assertTrue(outcome is ResumableDownloader.Outcome.Completed)
        assertArrayEquals(payload, final.readBytes())
        assertEquals("bytes=$alreadyOnDisk-", srv.rangeHeadersSeen.single())
    }

    @Test
    fun `keeps partial bytes when the connection drops and finishes on the next attempt`() = runBlocking {
        val srv = server(truncateFirstResponse = true)
        val partial = tempFolder.newFile("drop.part")
        val final = tempFolder.newFile("drop.mp4").also { it.delete() }

        val first = downloader.download(request(srv, partial, final))

        // Whatever the failure classification, the received bytes must be preserved so the
        // transfer resumes instead of restarting from zero.
        val rescuedBytes = when (first) {
            is ResumableDownloader.Outcome.Paused -> first.downloadedBytes
            is ResumableDownloader.Outcome.Failed -> first.downloadedBytes
            is ResumableDownloader.Outcome.Completed -> error("expected an interrupted transfer")
        }
        assertTrue("expected some bytes on disk, got $rescuedBytes", rescuedBytes > 0L)
        assertEquals(rescuedBytes, partial.length())
        assertTrue("resumed size must fall short of the whole file", partial.length() < payload.size)

        val second = downloader.download(
            request(srv, partial, final, alreadyDownloaded = partial.length(), etag = "\"fixture-etag\""),
        )

        assertTrue("second attempt should complete", second is ResumableDownloader.Outcome.Completed)
        assertArrayEquals("resumed download must equal the remote file", payload, final.readBytes())
        assertTrue(
            "a Range request should have been issued, saw ${srv.rangeHeadersSeen}",
            srv.rangeHeadersSeen.last().startsWith("bytes="),
        )
    }

    @Test
    fun `restarts from zero when the server ignores the Range header`() = runBlocking {
        val srv = server(supportRanges = false)
        val partial = tempFolder.newFile("norange.part")
        val final = tempFolder.newFile("norange.mp4").also { it.delete() }
        // Seed with garbage so a naive append would visibly corrupt the file.
        partial.writeBytes(ByteArray(5_000) { 0x7F })

        val outcome = downloader.download(
            request(srv, partial, final, alreadyDownloaded = 5_000L),
        )

        assertTrue(outcome is ResumableDownloader.Outcome.Completed)
        assertArrayEquals(
            "file must be rewritten from zero, not appended to",
            payload,
            final.readBytes(),
        )
    }

    @Test
    fun `reports progress and the range capability`() = runBlocking {
        val srv = server()
        val partial = tempFolder.newFile("progress.part")
        val final = tempFolder.newFile("progress.mp4").also { it.delete() }
        val samples = mutableListOf<Long>()
        var rangeSupported = false
        var etag: String? = null

        val outcome = downloader.download(
            request(srv, partial, final) { downloaded, _, _, ranges, tag ->
                samples.add(downloaded)
                rangeSupported = ranges
                etag = tag
            },
        )

        assertTrue(outcome is ResumableDownloader.Outcome.Completed)
        assertTrue("expected progress callbacks, got $samples", samples.isNotEmpty())
        assertEquals("progress must be monotonic", samples.sorted(), samples)
        assertEquals(payload.size.toLong(), samples.last())
        assertTrue("server advertises Accept-Ranges", rangeSupported)
        assertEquals("\"fixture-etag\"", etag)
    }

    @Test
    fun `surfaces a failure when the server returns an error status`() = runBlocking {
        val srv = server(statusCode = 404)
        val partial = tempFolder.newFile("err.part")
        val final = tempFolder.newFile("err.mp4").also { it.delete() }

        val outcome = downloader.download(request(srv, partial, final))

        assertTrue("expected a failure, got $outcome", outcome is ResumableDownloader.Outcome.Failed)
        assertFalse(final.exists())
    }
}
