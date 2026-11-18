package com.videodown.douyin.presentation.components

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.videodown.douyin.R

/**
 * 全应用统一的视频表面。
 *
 * XML 固定使用 TextureView，让 Compose 海报、视频和控制层参与同一套图层合成；否则 SurfaceView
 * 会在首帧前以独立黑色表面盖住仍然存在的海报，形成肉眼可见的闪黑。
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun AppPlayerView(
    player: Player,
    useController: Boolean,
    controllerAutoShow: Boolean = false,
    resizeMode: Int,
    modifier: Modifier = Modifier,
    onViewCreated: (PlayerView) -> Unit = {},
    configure: (PlayerView) -> Unit = {},
    onRelease: (PlayerView) -> Unit = {},
) {
    AndroidView(
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.view_app_player, null, false) as PlayerView
        },
        onReset = { view ->
            view.player = player
            onViewCreated(view)
        },
        update = { view ->
            view.player = player
            view.useController = useController
            view.controllerAutoShow = controllerAutoShow
            view.resizeMode = resizeMode
            if (!controllerAutoShow) view.hideController()
            configure(view)
            onViewCreated(view)
        },
        onRelease = { view ->
            onRelease(view)
            view.player = null
        },
        modifier = modifier,
    )
}
