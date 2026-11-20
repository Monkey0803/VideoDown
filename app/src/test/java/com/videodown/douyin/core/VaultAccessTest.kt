package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultAccessTest {
    @Before fun reset() = VaultAccess.resetForTests()

    private fun record(private: Boolean) =
        DownloadRecord("id", "aweme", "title", isPrivate = private)

    @Test fun `public records are always readable`() {
        assertTrue(VaultAccess.allows(record(private = false)))
        VaultAccess.unlock()
        assertTrue(VaultAccess.allows(record(private = false)))
    }

    @Test fun `private records stay locked until the session is unlocked`() {
        assertFalse(VaultAccess.allows(record(private = true)))
        VaultAccess.unlock()
        assertTrue(VaultAccess.allows(record(private = true)))
        VaultAccess.lock()
        assertFalse(VaultAccess.allows(record(private = true)))
    }
}
