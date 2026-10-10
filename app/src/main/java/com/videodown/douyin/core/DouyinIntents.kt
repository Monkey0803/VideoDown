package com.videodown.douyin.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.videodown.douyin.domain.model.DouyinAuthor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VideoPlatform
import java.io.File

/**
 * Intents that hand work off to other apps: opening the Douyin account behind a video,
 * playing a downloaded file, or sharing it.
 */
object DouyinIntents {

    /** Douyin's Android package name. */
    private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"

    /**
     * Opens the author's Douyin profile, preferring the native app and falling back to the
     * mobile web page. Returns false when neither could be opened.
     */
    fun openAuthorProfile(context: Context, author: DouyinAuthor): Boolean {
        if (author.platform != VideoPlatform.DOUYIN) return author.profileUrl?.let { tryOpenWeb(context, it) } ?: false
        val deepLink = author.profileDeepLink ?: return false
        if (tryOpen(context, Uri.parse(deepLink))) return true

        val webUrl = author.profileUrl ?: return false
        return tryOpen(context, Uri.parse(webUrl))
    }

    /** Opens a video inside the Douyin app, falling back to the web page. */
    fun openVideo(context: Context, awemeId: String): Boolean {
        if (tryOpen(context, Uri.parse("snssdk1128://aweme/detail/$awemeId"))) return true
        return tryOpen(context, Uri.parse("https://www.douyin.com/video/$awemeId"))
    }

    fun openVideo(context: Context, video: DouyinVideo): Boolean =
        if (video.platform == VideoPlatform.DOUYIN) openVideo(context, video.awemeId)
        else tryOpenWeb(context, video.videoWebUrl)

    fun openVideo(context: Context, record: DownloadRecord): Boolean = openVideo(context,
        DouyinVideo(record.awemeId, record.title, shareUrl = record.sourcePageUrl, platform = record.platform))

    private fun tryOpenWeb(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
        val preferred = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        val target = preferred?.takeIf { it in handlers } ?: handlers.firstOrNull() ?: return false
        // Exclude ourselves so previewing an imported link cannot reopen our parser.
        return runCatching { context.startActivity(intent.setPackage(target)) }.isSuccess
    }

    fun isDouyinInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(DOUYIN_PACKAGE, 0) }.isSuccess

    /** Plays a downloaded file with the system's default video app. */
    fun playExternally(context: Context, file: File, mimeType: String = com.videodown.douyin.domain.model.videoMimeType(file.extension)): Boolean {
        if (!file.exists()) return false
        val uri = uriFor(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Shares a downloaded file through any app that accepts a video. */
    fun shareFile(context: Context, file: File, mimeType: String = com.videodown.douyin.domain.model.videoMimeType(file.extension)): Boolean {
        if (!file.exists()) return false
        val uri = uriFor(context, file) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(if (mimeType.startsWith("audio/")) com.videodown.douyin.R.string.share_audio else com.videodown.douyin.R.string.share_video))
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private fun uriFor(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun tryOpen(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
