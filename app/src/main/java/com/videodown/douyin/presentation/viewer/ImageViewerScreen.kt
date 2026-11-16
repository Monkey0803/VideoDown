package com.videodown.douyin.presentation.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.videodown.douyin.R
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.galleryErrorMessage
import kotlinx.coroutines.launch
import java.io.File

/**
 * 已下载图片的查看页。
 *
 * 缩放、拖动、双击放大与超大图子采样都交给 ZoomImage（`CoilZoomAsyncImage`），这里只负责
 * 顶栏返回、保存到相册、分享与文件缺失态——自己写手势就违背了"不重复造轮子"。
 */
@Composable
fun ImageViewerScreen(record: DownloadRecord?, onBack: () -> Unit) {
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val file = record?.filePath?.let(::File)?.takeIf(File::isFile)
    val zoomState = rememberCoilZoomState()
    var saving by remember { mutableStateOf(false) }

    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun save(record: DownloadRecord) {
        if (saving) return
        saving = true
        scope.launch {
            val result = runCatching { ServiceLocator.galleryExporter.export(record) }
            saving = false
            result.fold(
                onSuccess = { show(resources.getString(R.string.library_saved_to_gallery)) },
                onFailure = { show(resources.getString(R.string.library_save_failed, galleryErrorMessage(context, it))) },
            )
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = record
        when {
            current == null -> Unit
            !granted -> show(resources.getString(R.string.library_need_storage_permission))
            else -> save(current)
        }
    }

    fun saveFromUser(record: DownloadRecord) {
        // Android 9 及以下写系统相册需要存储权限；更高版本走 MediaStore，无需授权。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(record)
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (file == null || record == null) {
            Text(
                stringResource(R.string.viewer_missing),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            CoilZoomAsyncImage(
                model = file,
                contentDescription = record.title,
                zoomState = zoomState,
                modifier = Modifier.fillMaxSize().testTag("image-viewer"),
            )
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .35f)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onBack,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .18f))) {
                    AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    record?.title ?: stringResource(R.string.viewer_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f)).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = record != null && file != null && !saving,
                    onClick = { record?.let(::saveFromUser) },
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (saving) R.string.library_saving else R.string.library_save_to_gallery), color = Color.White)
                }
                TextButton(
                    enabled = file != null,
                    onClick = {
                        val target = file ?: return@TextButton
                        if (!DouyinIntents.shareFile(context, target)) show(resources.getString(R.string.library_share_failed))
                    },
                ) {
                    Text(stringResource(R.string.action_share), color = Color.White)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp))
    }
}
