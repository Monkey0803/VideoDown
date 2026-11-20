package com.videodown.douyin.core

import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/**
 * 全应用共用的 Coil 单例。
 *
 * 注册动图解码器后，微博 GIF / 动态 WebP 在已存封面、看图器和缩略图里才会真的播放；
 * 之前只显示首帧，而解析结果里明明提供了动图格式。API 28 以上用 ImageDecoder，
 * 26/27 退回 Movie 解码，minSdk 26 因此也能动。
 */
internal fun installImagePipeline() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
}
