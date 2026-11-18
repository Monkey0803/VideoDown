package com.videodown.douyin.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionControlsTest {
    @Test fun `all selected wins over a stale indeterminate flag`() {
        assertEquals(SelectAllVisual.ON, selectAllVisual(allSelected = true, indeterminate = true))
        assertEquals(SelectAllVisual.ON, selectAllVisual(allSelected = true, indeterminate = false))
    }

    @Test fun `partial selection is indeterminate`() {
        assertEquals(SelectAllVisual.INDETERMINATE, selectAllVisual(allSelected = false, indeterminate = true))
    }

    @Test fun `nothing selected is off`() {
        assertEquals(SelectAllVisual.OFF, selectAllVisual(allSelected = false, indeterminate = false))
    }
}
