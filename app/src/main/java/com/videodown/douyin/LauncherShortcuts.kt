package com.videodown.douyin

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.videodown.douyin.data.download.DownloadService
import com.videodown.douyin.presentation.MainActivity

/**
 * Launcher shortcuts for the entry paths 同类应用 keep one long-press away.
 *
 * Published at runtime rather than declared in `res/xml`: a manifest shortcut has to hardcode
 * `targetPackage`, and the debug build ships as `…douyin.debug`, so the static form would point at
 * a package that is not installed.
 */
object LauncherShortcuts {
    const val ACTION_QUICK_PARSE = "com.videodown.douyin.action.QUICK_PARSE"
    const val ACTION_BATCH_IMPORT = "com.videodown.douyin.action.BATCH_IMPORT"

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(context, "quick_parse", ACTION_QUICK_PARSE, R.drawable.ic_shiying_paste, context.getString(R.string.shortcut_quick_parse), context.getString(R.string.shortcut_quick_parse_desc)),
            shortcut(context, "batch_import", ACTION_BATCH_IMPORT, R.drawable.ic_shiying_batch_import, context.getString(R.string.shortcut_batch_import), context.getString(R.string.shortcut_batch_import_desc)),
            shortcut(context, "open_tasks", DownloadService.ACTION_OPEN_TASKS, R.drawable.ic_shiying_tasks, context.getString(R.string.history_header_title), context.getString(R.string.shortcut_open_tasks_desc)),
        )
        // A launcher may refuse new shortcuts (disabled by the user, or rate limited); that only
        // costs a convenience, so it must never affect app startup.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun shortcut(context: Context, id: String, action: String, icon: Int, short: String, long: String) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .build()
}
