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
 * 图标用的是 `ic_shiying_shortcut_*` 而不是界面里那套线条图标：启动器不会给快捷方式图标染色，
 * 也不给它背景，纯白描边的字形在浅色菜单里等于看不见（真机实测非白像素占比 0.0000）。
 *
 * Published at runtime rather than declared in `res/xml`: a manifest shortcut has to hardcode
 * `targetPackage`, and the debug build ships as `…douyin.debug`, so the static form would point at
 * a package that is not installed.
 */
object LauncherShortcuts {
    const val ACTION_QUICK_PARSE = "com.videodown.douyin.action.QUICK_PARSE"
    const val ACTION_BATCH_IMPORT = "com.videodown.douyin.action.BATCH_IMPORT"

    /**
     * 启动时调用。
     *
     * **三个快捷方式暂时下线（用户要求先注释）**，所以这里不再发布，改为清掉历史上已经发布过的
     * 动态快捷方式——否则升级安装后长按菜单里仍然留着它们，看起来像没生效。
     *
     * 恢复方法：把下面注释掉的那一行换回来（定义与图标都原样保留），
     * 并与 `LauncherShortcutsDeviceTest` 里那条「不再发布」的断言一起改回。
     */
    fun publish(context: Context) {
        // runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts(context)) }
        // A launcher may refuse new shortcuts (disabled by the user, or rate limited); that only
        // costs a convenience, so it must never affect app startup.
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    /**
     * 要发布的快捷方式本身。
     *
     * 交给测试用：平台侧取回的 `ShortcutInfo.getIcon()` 对**资源型图标**永远是 null
     * （平台只保留 iconRes，真机 dumpsys 可证），所以"图标能不能看清"必须对着这里量。
     */
    internal fun shortcuts(context: Context): List<ShortcutInfoCompat> = listOf(
            shortcut(context, "quick_parse", ACTION_QUICK_PARSE, R.drawable.ic_shiying_shortcut_paste, context.getString(R.string.shortcut_quick_parse), context.getString(R.string.shortcut_quick_parse_desc)),
            shortcut(context, "batch_import", ACTION_BATCH_IMPORT, R.drawable.ic_shiying_shortcut_batch_import, context.getString(R.string.shortcut_batch_import), context.getString(R.string.shortcut_batch_import_desc)),
            shortcut(context, "open_tasks", DownloadService.ACTION_OPEN_TASKS, R.drawable.ic_shiying_shortcut_tasks, context.getString(R.string.history_header_title), context.getString(R.string.shortcut_open_tasks_desc)),
        )


    private fun shortcut(context: Context, id: String, action: String, icon: Int, short: String, long: String) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .build()
}
