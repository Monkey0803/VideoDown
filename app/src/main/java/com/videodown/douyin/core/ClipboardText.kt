package com.videodown.douyin.core

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

/**
 * 读取剪贴板纯文本；显式粘贴与被动检测共用。
 *
 * [plainTextOnly] 为 true 时先看 MIME 描述（被动检测与快捷方式），避免读到图片/文件；
 * 显式按下粘贴则直接取文本。不记录内容，隐私约束与调用方原实现一致。
 */
fun readClipboardText(context: Context, plainTextOnly: Boolean = false): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    if (!clipboard.hasPrimaryClip()) return null
    if (plainTextOnly && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf(String::isNotBlank)
}
