package com.videodown.douyin.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按应用语言」写进系统后要能原样读回来：设置页的那一行显示什么、以及默认该跟随系统，
 * 都由这里的映射决定。
 */
class AppLanguageTest {

    @Test
    fun `每一条可选语言都有写进系统的标签`() {
        assertEquals(null, AppLanguage.SYSTEM.tag)
        assertEquals("zh-CN", AppLanguage.CHINESE.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }

    @Test
    fun `中文与英文的完整标签都认得出来`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("ZH-cn"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN-gb"))
    }

    @Test
    fun `没有设置或不认识的语言都回落到跟随系统`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("   "))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zhx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("english"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr-FR"))
    }

    @Test
    fun `每种语言标签读回来仍是它自己`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }
}
