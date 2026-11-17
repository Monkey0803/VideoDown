package com.videodown.douyin.data.gallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 回执里的目标现在还在不在。
 *
 * - MediaStore 条目：用 `_ID` 查询确认；`content://` 已失效（被用户从相册/下载目录删除）时查询
 *   为空或抛 `FileNotFoundException`，这里统一按「不存在」处理。
 * - Android 9 及以下的公共目录：回执里直接存文件路径，检查磁盘。
 *
 * 只读查询，不会因为目标消失而删除任何用户文件。
 */
fun exportTargetExists(resolver: ContentResolver, target: String): Boolean {
    if (target.startsWith("content://")) {
        return runCatching {
            resolver.query(Uri.parse(target), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }
    val path = if (target.startsWith("file:")) runCatching { Uri.parse(target).path }.getOrNull() else target
    return path != null && File(path).isFile
}

/** 把回执里的目标还原成 `Uri`，用于把「已经保存过」的条目交给调用方（例如测试里删除它）。 */
internal fun exportTargetUri(target: String): Uri =
    if (target.startsWith("content://") || target.startsWith("file:")) Uri.parse(target) else Uri.fromFile(File(target))
