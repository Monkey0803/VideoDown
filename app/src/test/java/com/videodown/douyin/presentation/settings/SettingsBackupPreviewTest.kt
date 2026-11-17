package com.videodown.douyin.presentation.settings

import com.videodown.douyin.core.SettingsChange
import com.videodown.douyin.data.backup.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackupPreviewTest {
    private val current = SettingsSnapshot(
        themeMode = "SYSTEM",
        preferredQuality = "HIGHEST",
        maxConcurrentDownloads = 3,
        wifiOnly = false,
        globalSpeedLimitKbps = 0,
    )

    @Test fun `preview reports only valid values that would change`() {
        val incoming = SettingsSnapshot(
            themeMode = "DARK",
            preferredQuality = "UNKNOWN",
            maxConcurrentDownloads = 4,
            wifiOnly = false,
            globalSpeedLimitKbps = -1,
        )

        assertEquals(
            listOf(SettingsChange.THEME_MODE, SettingsChange.CONCURRENCY, SettingsChange.LIBRARY_ORDER),
            previewSettingsBackup(incoming, current, libraryOrderChanges = true),
        )
    }

    @Test fun `preview is empty when backup carries no effective changes`() {
        assertEquals(
            emptyList<SettingsChange>(),
            previewSettingsBackup(SettingsSnapshot(themeMode = "SYSTEM"), current, libraryOrderChanges = false),
        )
    }
}
