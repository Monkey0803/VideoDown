package com.videodown.douyin.presentation.sites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.videodown.douyin.presentation.theme.BrandColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.SiteSource
import com.videodown.douyin.domain.model.SiteWatchRecord
import com.videodown.douyin.domain.model.isGenericSiteMediaTitle
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.MediaCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteHubScreen(
    onBack: () -> Unit,
    onOpenSite: (String, String?) -> Unit,
    model: SiteHubViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.sites_title)) },
            navigationIcon = { IconButton(onClick = onBack) { AppIcon(AppIcons.Back, stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.sites_sources), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(state.sources, key = SiteSource::id) { source ->
                SiteSourceCard(source) { onOpenSite(source.id, null) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sites_recent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (state.history.isNotEmpty()) TextButton(onClick = model::clear) { Text(stringResource(R.string.sites_clear_history)) }
                }
            }
            if (!state.loading && state.history.isEmpty()) {
                item { Text(stringResource(R.string.sites_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.history, key = SiteWatchRecord::id) { record ->
                WatchRecordCard(record, { onOpenSite(record.siteId, record.pageUrl) }, { model.delete(record.id) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SiteSourceCard(source: SiteSource, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("site-source-${source.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(
                    Brush.linearGradient(BrandColors.siteBadgeGradient),
                    MaterialTheme.shapes.large,
                ),
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(if (source.id == "kubb") "KUBB.TV" else source.id, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.sites_kubb_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            AppIcon(AppIcons.Forward, null)
        }
    }
}

@Composable
private fun WatchRecordCard(record: SiteWatchRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val progress = if (record.durationMs > 0L) {
        (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f)
    } else 0f
    val progressPercent = (progress * 100).toInt()
    val displayTitle = record.title.takeUnless(::isGenericSiteMediaTitle)
        ?: stringResource(R.string.sites_unknown_video_title)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("site-watch-card-${record.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = if (progress > 0f) 14.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaCover(
                    model = record.coverUrl,
                    modifier = Modifier.size(width = 112.dp, height = 68.dp).testTag("site-watch-cover-${record.id}"),
                    shape = MaterialTheme.shapes.medium,
                    placeholderIcon = AppIcons.Play,
                )
                Column(Modifier.padding(horizontal = 12.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        displayTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("site-watch-title-${record.id}"),
                    )
                    Text(
                        stringResource(R.string.sites_watch_metadata, siteDisplayName(record.siteId), progressPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    AppIcon(AppIcons.Delete, stringResource(R.string.sites_delete_history))
                }
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                        .testTag("site-watch-progress-${record.id}"),
                )
            }
        }
    }
}

private fun siteDisplayName(siteId: String): String = if (siteId == "kubb") "KUBB.TV" else siteId.uppercase()
