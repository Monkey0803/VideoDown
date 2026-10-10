package com.videodown.douyin.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

@Composable
internal fun LibraryOrganizationDialog(record: DownloadRecord, model: LibraryOrganizationViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var title by rememberSaveable(record.id) { mutableStateOf(record.title) }
    var folder by rememberSaveable(record.id) { mutableStateOf(record.folder) }
    LaunchedEffect(record.id) { model.clearError() }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text(stringResource(R.string.library_organize)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !state.busy, label = { Text(stringResource(R.string.organize_file_name)) }, singleLine = true,
                leadingIcon = { AppIcon(AppIcons.Rename, null) })
            Text(stringResource(R.string.organize_file_name_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = folder, onValueChange = { folder = it }, enabled = !state.busy, label = { Text(stringResource(R.string.organize_folder)) }, singleLine = true,
                leadingIcon = { AppIcon(AppIcons.Category, null) })
            Text(stringResource(R.string.organize_folder_note), style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(organizeErrorText(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !state.busy && (title == record.title || (title.isNotBlank() && title.length <= 80)) && folder.length <= 40,
            onClick = { model.update(record.id, title = title.takeIf { it != record.title }, folder = folder, onSaved = onClose) }) { Text(stringResource(if (state.busy) R.string.library_saving else R.string.organize_save)) } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}