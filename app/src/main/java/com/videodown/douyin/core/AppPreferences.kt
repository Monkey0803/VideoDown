package com.videodown.douyin.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoVariant

/** Persistent preferences shared by the settings screen and download pipeline. */
class AppPreferences(context: Context) {
    private val store = context.getSharedPreferences("shiying_preferences", Context.MODE_PRIVATE)
    private val _maxConcurrent = MutableStateFlow(store.getInt("max_concurrent_downloads", 3).coerceIn(1, 5))
    val maxConcurrentChanges = _maxConcurrent.asStateFlow()
    var maxConcurrentDownloads: Int
        get() = _maxConcurrent.value
        set(value) {
            val count = value.coerceIn(1, 5)
            store.edit().putInt("max_concurrent_downloads", count).apply()
            _maxConcurrent.value = count
        }

    private val _wifiOnly = MutableStateFlow(store.getBoolean("wifi_only", false))
    val wifiOnlyChanges = _wifiOnly.asStateFlow()
    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == store.getString("theme_mode", null) } ?: ThemeMode.SYSTEM,
    )
    val themeModeChanges = _themeMode.asStateFlow()

    var themeMode: ThemeMode
        get() = _themeMode.value
        set(value) {
            store.edit().putString("theme_mode", value.name).apply()
            _themeMode.value = value
        }

    var wifiOnly: Boolean
        get() = _wifiOnly.value
        set(value) {
            store.edit().putBoolean("wifi_only", value).apply()
            _wifiOnly.value = value
        }

    /**
     * Continue interrupted transfers by themselves once the blocking condition clears.
     *
     * Enabled by default because a Wi-Fi interruption is not a user decision; turning it off keeps
     * every pause manual. A deliberate tap on pause is never auto-resumed either way.
     */
    private val _autoResume = MutableStateFlow(store.getBoolean("auto_resume_downloads", true))
    val autoResumeChanges = _autoResume.asStateFlow()

    var autoResume: Boolean
        get() = _autoResume.value
        set(value) {
            store.edit().putBoolean("auto_resume_downloads", value).apply()
            _autoResume.value = value
        }

    var notifyOnCompletion: Boolean
        get() = store.getBoolean("notify_on_completion", false)
        set(value) { store.edit().putBoolean("notify_on_completion", value).apply() }

    /**
     * Offer a link found on the clipboard when 拾影 returns to the foreground.
     *
     * On by default because copying a share link and switching back is the shortest path into the
     * app; it stays opt-out because reading the clipboard is a privacy-relevant action.
     */
    private val _clipboardDetect = MutableStateFlow(store.getBoolean("clipboard_detect", true))
    val clipboardDetectChanges = _clipboardDetect.asStateFlow()

    var clipboardDetect: Boolean
        get() = _clipboardDetect.value
        set(value) {
            store.edit().putBoolean("clipboard_detect", value).apply()
            _clipboardDetect.value = value
        }

    /** Link of the last clipboard offer the user dismissed, so it is not offered again. */
    var lastClipboardOffer: String?
        get() = store.getString("last_clipboard_offer", null)
        set(value) {
            val editor = store.edit()
            if (value.isNullOrBlank()) editor.remove("last_clipboard_offer") else editor.putString("last_clipboard_offer", value)
            editor.apply()
        }

    /**
     * Mirror each running task into its own notification so it can be paused on its own.
     *
     * Off by default: a busy queue would otherwise post one notification per task.
     */
    private val _individualTaskNotifications = MutableStateFlow(store.getBoolean("individual_task_notifications", false))
    val individualTaskNotificationsChanges = _individualTaskNotifications.asStateFlow()

    var individualTaskNotifications: Boolean
        get() = _individualTaskNotifications.value
        set(value) {
            store.edit().putBoolean("individual_task_notifications", value).apply()
            _individualTaskNotifications.value = value
        }

    /** Use the system wallpaper palette (Material You) instead of the fixed 拾影 warm brand colors. */
    private val _dynamicColor = MutableStateFlow(store.getBoolean("dynamic_color", false))
    val dynamicColorChanges = _dynamicColor.asStateFlow()

    var dynamicColor: Boolean
        get() = _dynamicColor.value
        set(value) {
            store.edit().putBoolean("dynamic_color", value).apply()
            _dynamicColor.value = value
        }

    /**
     * Endpoint of the JSON version manifest used by 「我的 → 检查更新」.
     *
     * Defaults to the URL baked in at build time (`-PupdateManifestUrl=…`) and can be overridden
     * per device from the update dialog. Stored verbatim; the checker rejects non-HTTP values.
     */
    var updateManifestUrl: String
        get() = store.getString("update_manifest_url", null)?.trim().orEmpty()
            .ifEmpty { com.videodown.douyin.BuildConfig.DEFAULT_UPDATE_MANIFEST_URL }
        set(value) { store.edit().putString("update_manifest_url", value.trim()).apply() }

    var preferredQuality: PreferredQuality
        get() = PreferredQuality.entries.getOrNull(store.getInt("preferred_quality", 0)) ?: PreferredQuality.HIGHEST
        set(value) { store.edit().putInt("preferred_quality", value.ordinal).apply() }

    var exportTreeUri: String?
        get() = store.getString("export_tree_uri", null)
        set(value) { store.edit().putString("export_tree_uri", value).apply() }

    var libraryLayout: LibraryLayout
        get() = LibraryLayout.entries.firstOrNull { it.name == store.getString("library_layout", null) }
            ?: LibraryLayout.WATERFALL
        set(value) { store.edit().putString("library_layout", value.name).apply() }

    fun selectVariant(video: DouyinVideo): VideoVariant? {
        val variants = video.sortedVariants
        return when (preferredQuality) {
            PreferredQuality.HIGHEST -> variants.firstOrNull()
            PreferredQuality.STANDARD -> variants.firstOrNull { (it.height ?: Int.MAX_VALUE) <= 720 }
                ?: variants.getOrNull(variants.size / 2)
            PreferredQuality.SAVER -> variants.lastOrNull()
        }
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class LibraryLayout(val label: String) {
    WATERFALL("瀑布流布局"),
    LIST("列表布局"),
}

enum class PreferredQuality(val label: String) {
    HIGHEST("最高画质"),
    STANDARD("标准画质"),
    SAVER("节省空间"),
}
