package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPolicyTest {

    private fun record(private: Boolean = false, title: String = "海边的日落") =
        DownloadRecord(id = "id", awemeId = "aweme", title = title, isPrivate = private)

    @Test fun `a private record belongs to the vault and a normal one does not`() {
        assertTrue(VaultPolicy.belongsToVault(record(private = true)))
        assertFalse(VaultPolicy.belongsToVault(record()))
    }

    @Test fun `a locked vault shows no private item at all`() {
        assertFalse(VaultPolicy.visibleInVault(record(private = true), unlocked = false))
        assertTrue(VaultPolicy.visibleInVault(record(private = true), unlocked = true))
        // An unlocked vault still does not contain ordinary records.
        assertFalse(VaultPolicy.visibleInVault(record(), unlocked = true))
    }

    @Test fun `a private title is only readable while the vault is unlocked`() {
        // null means "hidden"; the UI substitutes its own wording.
        assertNull(VaultPolicy.title(record(private = true), unlocked = false))
        assertEquals("海边的日落", VaultPolicy.title(record(private = true), unlocked = true))
        assertEquals("海边的日落", VaultPolicy.title(record(), unlocked = false))
    }

    @Test fun `the notification never names a private record, even while unlocked`() {
        // Hidden title: the notification substitutes the wording, so the domain reports null.
        assertNull(VaultPolicy.notificationTitle(record(private = true)))
        assertEquals("海边的日落", VaultPolicy.notificationTitle(record()))
    }

    @Test fun `a private record is never copied into the shared gallery automatically`() {
        assertEquals(VaultPolicy.VAULT_GALLERY_KEY, VaultPolicy.autoGalleryReasonKey(record(private = true)))
        assertNull(VaultPolicy.autoGalleryReasonKey(record()))
    }

    @Test fun `an exported link list skips private records`() {
        assertFalse(VaultPolicy.linkExportable(record(private = true)))
        assertTrue(VaultPolicy.linkExportable(record()))
    }

    @Test fun `a private record may not be deep-linked from a notification`() {
        assertFalse(VaultPolicy.deepLinkable(record(private = true)))
        assertTrue(VaultPolicy.deepLinkable(record()))
    }
}