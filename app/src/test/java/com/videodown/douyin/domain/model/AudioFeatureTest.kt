package com.videodown.douyin.domain.model

import com.videodown.douyin.data.generic.GenericMetadataParser
import com.videodown.douyin.data.generic.VideoEngineException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.*

class AudioFeatureTest {
    @Test fun `actual audio formats stay independent of video quality and use m4a engine output`() {
        val metadata = Json.parseToJsonElement("""{"id":"media","title":"test","formats":[
            {"url":"https://cdn.test/video.mp4","format_id":"video","vcodec":"h264","acodec":"none","ext":"mp4","height":720},
            {"url":"https://cdn.test/audio.webm","format_id":"audio","vcodec":"none","acodec":"opus","ext":"webm","tbr":128},
            {"url":"https://cdn.test/unknown","format_id":"unknown","vcodec":"none","ext":"m4a"},
            {"url":"https://cdn.test/protected","format_id":"protected","vcodec":"none","acodec":"aac","has_drm":true}]}""").jsonObject
        val video = GenericMetadataParser().parse(metadata, "https://page.test/video").selectedVideo
        assertEquals(1, video.variants.size)
        assertEquals(1, video.audioVariants.size)
        val audio = video.audioVariants.single()
        assertEquals("audio", audio.media!!.formatSelector)
        assertTrue(audio.media!!.audioOnly); assertTrue(audio.media!!.usesEngine)
        assertEquals("m4a", audio.media!!.extension)
        assertEquals("audio/mp4", videoMimeType(audio.media!!.extension))
        assertEquals("video", video.defaultVariant!!.media!!.formatId)
    }
    @Test fun `audio and video identities differ even with the same media and format id`() {
        val video = DouyinVideo("media", "test", platform = VideoPlatform.GENERIC)
        val media = MediaDownload(MediaSourceType.DASH, mediaId = "media", formatId = "same")
        val record = DownloadRecord("id", video.awemeId, video.title, platform = video.platform, resolutionLabel = "same", media = media)
        assertTrue(record.matchesSelection(video, VideoVariant("url", "same", media = media)))
        assertFalse(record.matchesSelection(video, VideoVariant("url", "same", media = media.copy(audioOnly = true))))
    }
    @Test fun `local extraction identifies source record and remains separate from other saved renditions`() {
        val first = DownloadRecord("first", "media", "test", status = DownloadStatus.COMPLETED)
        val second = first.copy(id = "second")
        val (video, variant) = first.audioExtractionSelection()
        val record = first.copy(id = "audio", resolutionLabel = variant.label, media = variant.media)
        assertTrue(record.isAudio)
        assertTrue(record.matchesSelection(video, variant))
        assertFalse(record.matchesSelection(second.audioExtractionSelection().first, second.audioExtractionSelection().second))
        assertEquals(first.id, variant.media!!.localRecordId)
        assertTrue(variant.media!!.usesEngine)
    }
    @Test fun `no audio failure has explicit guidance and no futile retry`() {
        val failure = VideoEngineException("NO_AUDIO")
        assertEquals(DownloadFailureKind.NO_AUDIO, failure.failureKind)
        assertEquals(RecoveryAction.NONE, failureActions(failure.failureKind).primary)
        assertFalse(DownloadRecord("id", "media", "test", failureKind = failure.failureKind).canRetryDownload)
    }
    @Test fun `legacy history remains video and audio settings round trip`() {
        val json = Json { encodeDefaults = true }
        val old = json.decodeFromString<DownloadRecord>("""{"id":"old","awemeId":"media","title":"old"}""")
        assertFalse(old.isAudio)
        val audio = old.copy(media = MediaDownload(audioOnly = true, localRecordId = "original", mediaId = "original"))
        assertEquals(audio, json.decodeFromString<DownloadRecord>(json.encodeToString(DownloadRecord.serializer(), audio)))
    }
    @Test fun `extraction rejects unfinished or already audio records`() {
        for (record in listOf(DownloadRecord("id", "media", "unfinished"),
            DownloadRecord("id", "media", "audio", status = DownloadStatus.COMPLETED, media = MediaDownload(audioOnly = true)))) {
            assertTrue(runCatching { record.audioExtractionSelection() }.isFailure)
        }
    }
}
