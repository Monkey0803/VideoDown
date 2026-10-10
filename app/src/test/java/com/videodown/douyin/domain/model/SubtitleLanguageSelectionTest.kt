package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguageSelectionTest {

    @Test fun `a real chinese and english track are both kept, most specific first`() {
        val choice = SubtitleLanguageSelection.preferred(listOf("en", "zh-Hant", "zh-Hans", "ja"), emptyList())
        assertEquals(listOf("zh-Hans", "en"), choice.languages)
        assertFalse(choice.automatic)
    }

    @Test fun `traditional chinese is labelled as such`() {
        val choice = SubtitleLanguageSelection.preferred(listOf("zh-Hant"), emptyList())
        assertEquals(listOf("zh-Hant"), choice.languages)
        // The domain reports which name to use; the screen owns the wording.
        assertEquals(SubtitleLanguageKey.TRADITIONAL_CHINESE, SubtitleLanguageSelection.displayKey("zh-Hant"))
    }

    @Test fun `a bare zh is treated as simplified chinese`() {
        assertEquals(listOf("zh"), SubtitleLanguageSelection.preferred(listOf("zh"), emptyList()).languages)
    }

    @Test fun `one english track is kept, chosen by documented priority not input order`() {
        assertEquals(listOf("en-US"), SubtitleLanguageSelection.preferred(listOf("en-GB", "en-US"), emptyList()).languages)
        assertEquals(listOf("en-GB"), SubtitleLanguageSelection.preferred(listOf("en-GB"), emptyList()).languages)
        assertEquals(SubtitleLanguageKey.ENGLISH, SubtitleLanguageSelection.displayKey("en"))
    }

    @Test fun `an unrelated language falls back to what the source offers`() {
        val choice = SubtitleLanguageSelection.preferred(listOf("ja", "ko"), emptyList())
        assertEquals(listOf("ja"), choice.languages)
        assertEquals(SubtitleLanguageKey.JAPANESE, SubtitleLanguageSelection.displayKey(choice.languages.first()))
    }

    @Test fun `machine captions are used only when there is no real track`() {
        val withReal = SubtitleLanguageSelection.preferred(listOf("en"), listOf("zh", "de"))
        assertEquals(listOf("en"), withReal.languages)
        assertFalse(withReal.automatic)

        val onlyAuto = SubtitleLanguageSelection.preferred(emptyList(), listOf("de", "zh"))
        assertEquals(listOf("zh"), onlyAuto.languages)
        assertTrue(onlyAuto.automatic)
        // The "auto captions ·" prefix is added by the screen; the domain reports the flag.
        assertTrue(onlyAuto.automatic)
        assertEquals(listOf("zh"), onlyAuto.languages)
    }

    @Test fun `a source without subtitles selects nothing at all`() {
        val none = SubtitleLanguageSelection.preferred(emptyList(), emptyList())
        assertEquals(SubtitleSelection.NONE, none)
        assertFalse(none.available)
    }

    @Test fun `chat replays and junk keys are not treated as subtitles`() {
        val choice = SubtitleLanguageSelection.preferred(listOf("live_chat", "  ", "!!!!"), emptyList())
        assertFalse("No usable language means nothing to offer", choice.available)
    }

    @Test fun `duplicates and casing do not produce two tracks`() {
        val choice = SubtitleLanguageSelection.preferred(listOf("EN", "en", "zh", "zh"), emptyList())
        assertEquals(listOf("zh", "EN"), choice.languages)
    }

    @Test fun `an unknown language keeps its own code`() {
        // OTHER tells the screen to show the raw tag as the source gives it.
        assertEquals(SubtitleLanguageKey.OTHER, SubtitleLanguageSelection.displayKey("sv"))
        assertEquals("sv", SubtitleLanguageSelection.preferred(listOf("sv"), emptyList()).languages.single())
    }
}
