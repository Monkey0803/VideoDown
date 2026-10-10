package com.videodown.douyin.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes a backup file the user picked through the system document picker.
 *
 * Kept as plain functions over a `Uri` so the import/export logic can be driven in a test with a
 * document provider, without going through the picker UI.
 */
object BackupDocuments {
    /** Suggested names for the two "create document" pickers. */
    const val LINK_FILE_NAME = "拾影链接清单.txt"  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
    const val SETTINGS_FILE_NAME = "拾影设置备份.json"  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字

    /** MIME types offered when opening a backup; a plain text list must also be selectable. */
    val READABLE_TYPES = arrayOf("text/plain", "application/json", "*/*")

    /** The file's text, or `null` when it cannot be read at all. */
    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /** Writes [text], replacing any previous content; `false` when the target is not writable. */
    suspend fun write(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            stream.bufferedWriter().use { it.write(text) }
            true
        }.getOrDefault(false)
    }
}
