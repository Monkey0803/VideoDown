package com.videodown.douyin.presentation.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionPolicyTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test fun `range selects every id between the anchor and the target`() {
        assertEquals(setOf("b", "c", "d"), selectionRange(ids, "b", "d"))
    }

    @Test fun `range works in both directions`() {
        assertEquals(setOf("b", "c", "d"), selectionRange(ids, "d", "b"))
    }

    @Test fun `no anchor only selects the target`() {
        assertEquals(setOf("c"), selectionRange(ids, null, "c"))
        assertEquals(setOf("c"), selectionRange(ids, "gone", "c"))
    }

    @Test fun `unknown target selects nothing`() {
        assertTrue(selectionRange(ids, "a", "gone").isEmpty())
    }
}
