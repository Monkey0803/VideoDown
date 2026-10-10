package com.videodown.douyin.core

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * 应用语言：跟随系统，或在 Android 13 及以上为「拾影」单独指定一种。
 *
 * 选择不存在应用自己的偏好文件里——系统的「按应用语言」（[LocaleManager]）就是唯一状态，
 * 所以设置页里的这一行与系统设置里的「应用语言」永远显示同一个值，不会互相打架。
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * 由系统保存的语言标签还原选择；认不出的语言一律当作跟随系统。
         *
         * 只认 `zh` / `zh-…` 与 `en` / `en-…`，`zhx`、`english` 这类前缀相同的假标签不算数。
         */
        fun fromTag(tag: String?): AppLanguage = when {
            tag.isNullOrBlank() -> SYSTEM
            tag.isLanguageTag("zh") -> CHINESE
            tag.isLanguageTag("en") -> ENGLISH
            else -> SYSTEM
        }

        private fun String.isLanguageTag(code: String) =
            equals(code, ignoreCase = true) || startsWith("$code-", ignoreCase = true)
    }
}

/**
 * 按应用语言是 Android 13（API 33）才有的能力。更早的系统只有系统语言、应用只能跟随，
 * 因此设置页此时只做说明并给出前往系统设置的入口，不提供选择。
 */
fun appLanguageSwitchSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** 当前生效的应用语言；没有单独设置（或系统过旧）时是跟随系统。 */
fun currentAppLanguage(context: Context): AppLanguage {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return AppLanguage.SYSTEM
    val locales = runCatching {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales
    }.getOrNull()
    if (locales == null || locales.isEmpty) return AppLanguage.SYSTEM
    // 只设置一种语言，多标签串（如 "en,zh"）取第一个。
    return AppLanguage.fromTag(locales.toLanguageTags().substringBefore(','))
}

/**
 * 写入选择；写 [AppLanguage.SYSTEM] 即清空按应用语言、回到跟随系统。
 *
 * 系统会因此重建 Activity，界面语言随之切换，调用方不需要自己 recreate。
 */
fun applyAppLanguage(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val locales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
}
