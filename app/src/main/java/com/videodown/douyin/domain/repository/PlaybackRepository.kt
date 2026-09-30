package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions

interface PlaybackRepository {
    fun options(audio: Boolean): PlaybackOptions
    fun setOptions(audio: Boolean, options: PlaybackOptions)
    fun checkpoint(id: String, fileSize: Long, modifiedAt: Long): PlaybackCheckpoint
    fun saveCheckpoint(id: String, fileSize: Long, modifiedAt: Long, checkpoint: PlaybackCheckpoint)
}
