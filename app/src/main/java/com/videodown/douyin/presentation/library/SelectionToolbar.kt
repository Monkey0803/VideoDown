package com.videodown.douyin.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun SelectionToolbar(count: Int, busy: Boolean, allSelected: Boolean, onSelectAll: () -> Unit,
    onExit: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    LazyRow(modifier = Modifier.testTag("selection-toolbar"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        item { Text(stringResource(R.string.selection_count, count)) }
        item { TextButton(onClick = onSelectAll, enabled = !busy) { Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.selection_select_all_results)) } }
        actions.forEach { (label, action) -> item { TextButton(onClick = action, enabled = !busy && count > 0) { Text(label) } } }
        item { TextButton(onClick = onExit, enabled = !busy) { Text(stringResource(R.string.home_done_selecting)) } }
    }
}

@Composable
internal fun BatchFailureDetails(result: com.videodown.douyin.presentation.history.BatchResult?) {
    if (result == null || result.failures.isEmpty()) return
    var showDetails by androidx.compose.runtime.remember(result) { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.selection_failures, result.failures.size)) }
    if (showDetails) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(stringResource(R.string.batch_failures_title, stringResource(com.videodown.douyin.presentation.history.contextOperationLabel(result.operation)))) },
        text = {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
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
                        modifier = androidx.compose.ui.Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.action_got_it)) } },
    )
}
