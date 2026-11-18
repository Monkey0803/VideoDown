package com.videodown.douyin.presentation.history

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcons

/** 批量操作失败的明细弹窗；与批量动作本身同源，留在 history 包内。 */
@Composable
internal fun BatchFailureDetails(result: BatchResult?) {
    if (result == null || result.failures.isEmpty()) return
    var showDetails by remember(result) { mutableStateOf(false) }
    TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.selection_failures, result.failures.size)) }
    if (showDetails) AppDialog(
        onDismissRequest = { showDetails = false },
        icon = AppIcons.Warning,
        title = stringResource(
            R.string.batch_failures_title,
            stringResource(contextOperationLabel(result.operation)),
        ),
        actions = {
            AppDialogButtonRow {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.action_got_it)) }
            }
        },
    ) {
        result.failures.forEach { failure ->
            Text(
                stringResource(
                    R.string.batch_failure_line,
                    failure.title ?: stringResource(R.string.batch_untitled),
                    // The batch result carries a key; the wording is resolved here.
                    com.videodown.douyin.data.gallery.galleryFailureOf(failure.galleryFailureKey)
                        ?.let { stringResource(com.videodown.douyin.core.galleryFailureRes(it)) }
                        ?: stringResource(R.string.batch_failure_generic),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
