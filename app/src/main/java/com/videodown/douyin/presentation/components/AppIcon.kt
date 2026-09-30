package com.videodown.douyin.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.videodown.douyin.R

/** Theme-tinted icons from the Shiying kit; selected shapes use transparent cutouts. */
internal enum class AppIcons(@get:DrawableRes val resourceId: Int) {
    Parse(R.drawable.ic_shiying_parse),
    Tasks(R.drawable.ic_shiying_tasks),
    Library(R.drawable.ic_shiying_library),
    Profile(R.drawable.ic_shiying_profile),
    ParseSelected(R.drawable.ic_shiying_parse_selected),
    TasksSelected(R.drawable.ic_shiying_tasks_selected),
    LibrarySelected(R.drawable.ic_shiying_library_selected),
    ProfileSelected(R.drawable.ic_shiying_profile_selected),
    Paste(R.drawable.ic_shiying_paste),
    BatchImport(R.drawable.ic_shiying_batch_import),
    Link(R.drawable.ic_shiying_link),
    Search(R.drawable.ic_shiying_search),
    Quality(R.drawable.ic_shiying_quality),
    Download(R.drawable.ic_shiying_download),
    Pause(R.drawable.ic_shiying_pause),
    ResumeDownload(R.drawable.ic_shiying_resume_download),
    Retry(R.drawable.ic_shiying_retry),
    Queue(R.drawable.ic_shiying_queue),
    Priority(R.drawable.ic_shiying_priority),
    Share(R.drawable.ic_shiying_share),
    Delete(R.drawable.ic_shiying_delete),
    Favorite(R.drawable.ic_shiying_favorite),
    FavoriteFilled(R.drawable.ic_shiying_favorite_filled),
    Rename(R.drawable.ic_shiying_rename),
    Category(R.drawable.ic_shiying_category),
    ExportFolder(R.drawable.ic_shiying_export_folder),
    SaveAlbum(R.drawable.ic_shiying_save_album),
    Audio(R.drawable.ic_shiying_audio),
    ExtractAudio(R.drawable.ic_shiying_extract_audio),
    Sort(R.drawable.ic_shiying_sort),
    Grid(R.drawable.ic_shiying_grid),
    List(R.drawable.ic_shiying_list),
    SelectAll(R.drawable.ic_shiying_select_all),
    Browser(R.drawable.ic_shiying_browser),
    ExternalLink(R.drawable.ic_shiying_external_link),
    Play(R.drawable.ic_shiying_play),
    Volume(R.drawable.ic_shiying_volume),
    Repeat(R.drawable.ic_shiying_repeat),
    Speed(R.drawable.ic_shiying_speed),
    Pip(R.drawable.ic_shiying_pip),
    Appearance(R.drawable.ic_shiying_appearance),
    Wifi(R.drawable.ic_shiying_wifi),
    Notification(R.drawable.ic_shiying_notification),
    Storage(R.drawable.ic_shiying_storage),
    Settings(R.drawable.ic_shiying_settings),
    Info(R.drawable.ic_shiying_info),
    ClearSession(R.drawable.ic_shiying_clear_session),
    Back(R.drawable.ic_shiying_back),
    Forward(R.drawable.ic_shiying_forward),
    ChevronDown(R.drawable.ic_shiying_chevron_down),
    ChevronUp(R.drawable.ic_shiying_chevron_up),
    Close(R.drawable.ic_shiying_close),
    Success(R.drawable.ic_shiying_success),
    Warning(R.drawable.ic_shiying_warning),
    Error(R.drawable.ic_shiying_error),
    NotificationDownload(R.drawable.ic_shiying_notification_download),
    NotificationComplete(R.drawable.ic_shiying_notification_complete),
    NotificationAudio(R.drawable.ic_shiying_notification_audio),
    Brand(R.drawable.ic_shiying_brand),
    Fullscreen(R.drawable.ic_shiying_fullscreen),
    FullscreenExit(R.drawable.ic_shiying_fullscreen_exit);
}

@Composable
internal fun AppIcon(
    icon: AppIcons,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(icon.resourceId), contentDescription = contentDescription,
        modifier = modifier, tint = tint)
}
