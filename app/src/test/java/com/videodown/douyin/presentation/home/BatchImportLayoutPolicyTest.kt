package com.videodown.douyin.presentation.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportLayoutPolicyTest {
    @Test fun `full starts exactly at its threshold`() {
        assertEquals(ImportLayout.MINIMAL, BatchImportLayoutPolicy.layoutFor(0))
        assertEquals(ImportLayout.MINIMAL, BatchImportLayoutPolicy.layoutFor(319))
        assertEquals(ImportLayout.COMPACT, BatchImportLayoutPolicy.layoutFor(320))
        assertEquals(ImportLayout.COMPACT, BatchImportLayoutPolicy.layoutFor(479))
        assertEquals(ImportLayout.FULL, BatchImportLayoutPolicy.layoutFor(480))
        assertEquals(ImportLayout.FULL, BatchImportLayoutPolicy.layoutFor(900))
    }

    @Test fun `negative available height never escapes the minimal layout`() {
        assertEquals(ImportLayout.MINIMAL, BatchImportLayoutPolicy.layoutFor(-1))
        assertEquals(ImportLayout.MINIMAL, BatchImportLayoutPolicy.layoutFor(Int.MIN_VALUE))
    }

    @Test fun `only the full layout keeps the icon and subtitle`() {
        ImportLayout.entries.forEach { layout ->
            assertEquals(layout == ImportLayout.FULL, BatchImportLayoutPolicy.showsHeaderIcon(layout))
            assertEquals(layout == ImportLayout.FULL, BatchImportLayoutPolicy.showsHeaderSubtitle(layout))
        }
    }

    @Test fun `status strip is hidden only in the minimal layout`() {
        assertTrue(BatchImportLayoutPolicy.showsStatusStrip(ImportLayout.FULL))
        assertTrue(BatchImportLayoutPolicy.showsStatusStrip(ImportLayout.COMPACT))
        assertFalse(BatchImportLayoutPolicy.showsStatusStrip(ImportLayout.MINIMAL))
    }
}
