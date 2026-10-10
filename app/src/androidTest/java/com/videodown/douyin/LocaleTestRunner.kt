package com.videodown.douyin

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner

/**
 * Pins the app under test to Simplified Chinese.
 *
 * The device suite asserts the Chinese copy of the UI, but the app now follows the system language
 * (that is the point of the localization work): on an English host device every such assertion would
 * fail. Pinning the *app's* locale keeps the suite independent of the host's language, and
 * `LocalizationDeviceTest` switches it explicitly when it needs to check the English resources.
 *
 * Android 13 and newer only: older releases have no per-app locale, and the device the suite runs on
 * for those versions is Chinese already.
 */
class LocaleTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                targetContext.getSystemService(LocaleManager::class.java)
                    ?.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"))
            }
        }
        super.onStart()
    }
}
