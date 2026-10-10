package com.videodown.douyin.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {

    private val full = SettingsSnapshot(
        themeMode = "DARK",
        dynamicColor = true,
        preferredQuality = "STANDARD",
        libraryLayout = "LIST",
        wifiOnly = true,
        autoResume = false,
        maxConcurrentDownloads = 4,
        notifyOnCompletion = true,
        individualTaskNotifications = true,
        clipboardDetect = false,
        autoSaveToGallery = true,
        globalSpeedLimitKbps = 1024,
        subtitlesEnabled = false,
        updateManifestUrl = "https://example.com/version.json",
    )

    @Test
    fun `a snapshot survives a round trip unchanged`() {
        assertEquals(full, SettingsBackupCodec.decode(SettingsBackupCodec.encode(full)))
    }

    @Test
    fun `only the fields a file carries are non-null`() {
        val partial = SettingsBackupCodec.decode("""{"version":1,"wifiOnly":true}""")!!
        assertEquals(true, partial.wifiOnly)
        assertNull("An absent field must leave the current setting alone", partial.themeMode)
        assertNull(partial.maxConcurrentDownloads)
    }

    @Test
    fun `unknown keys from a newer build are ignored`() {
        val decoded = SettingsBackupCodec.decode("""{"version":9,"futureSetting":"x","themeMode":"LIGHT"}""")
        assertEquals("LIGHT", decoded?.themeMode)
    }

    @Test
    fun `text that is not a backup is rejected instead of guessed`() {
        assertNull(SettingsBackupCodec.decode(null))
        assertNull(SettingsBackupCodec.decode(""))
        assertNull(SettingsBackupCodec.decode("   "))
        assertNull(SettingsBackupCodec.decode("hello"))
        assertNull(SettingsBackupCodec.decode("[1,2,3]"))
        assertNull(SettingsBackupCodec.decode("""{"themeMode":}"""))
    }

    @Test
    fun `an unknown enum name is dropped rather than applied`() {
        val sanitized = SettingsBackupCodec.decode("""{"themeMode":"NEON","libraryLayout":"GRID","preferredQuality":"ULTRA"}""")!!.sanitized()
        assertNull(sanitized.themeMode)
        assertNull(sanitized.libraryLayout)
        assertNull(sanitized.preferredQuality)
    }

    @Test
    fun `known enum names survive sanitizing`() {
        val sanitized = SettingsBackupCodec.decode("""{"themeMode":"SYSTEM","libraryLayout":"WATERFALL","preferredQuality":"SAVER"}""")!!.sanitized()
        assertEquals("SYSTEM", sanitized.themeMode)
        assertEquals("WATERFALL", sanitized.libraryLayout)
        assertEquals("SAVER", sanitized.preferredQuality)
    }

    @Test
    fun `concurrency and speed limits are clamped to what the UI can express`() {
        val tooMany = SettingsBackupCodec.decode("""{"maxConcurrentDownloads":99}""")!!.sanitized()
        assertEquals(SettingsSnapshot.MAX_CONCURRENT, tooMany.maxConcurrentDownloads)
        val tooFew = SettingsBackupCodec.decode("""{"maxConcurrentDownloads":0}""")!!.sanitized()
        assertEquals(SettingsSnapshot.MIN_CONCURRENT, tooFew.maxConcurrentDownloads)
        val negative = SettingsBackupCodec.decode("""{"globalSpeedLimitKbps":-5}""")!!.sanitized()
        assertNull(negative.globalSpeedLimitKbps)
    }

    @Test
    fun `an absurd speed limit is treated as unusable instead of clamped`() {
        assertNull(SettingsBackupCodec.decode("""{"globalSpeedLimitKbps":999999999}""")!!.sanitized().globalSpeedLimitKbps)
        assertEquals(5120, SettingsBackupCodec.decode("""{"globalSpeedLimitKbps":5120}""")!!.sanitized().globalSpeedLimitKbps)
    }

    @Test
    fun `a manifest url must look like an http endpoint`() {
        assertEquals("https://example.com/v.json",
            SettingsBackupCodec.decode("""{"updateManifestUrl":"  https://example.com/v.json  "}""")!!.sanitized().updateManifestUrl)
        assertNull(SettingsBackupCodec.decode("""{"updateManifestUrl":"file:///etc/passwd"}""")!!.sanitized().updateManifestUrl)
        assertNull(SettingsBackupCodec.decode("""{"updateManifestUrl":"   "}""")!!.sanitized().updateManifestUrl)
    }

    @Test
    fun `the encoded file is human readable json`() {
        val text = SettingsBackupCodec.encode(full)
        assertTrue(text, text.startsWith("{"))
        assertTrue(text, text.contains("\n"))
        assertTrue(text, text.contains("\"themeMode\": \"DARK\""))
    }
}
