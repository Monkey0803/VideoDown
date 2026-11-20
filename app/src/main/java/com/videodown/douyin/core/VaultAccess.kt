package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadRecord

/**
 * 保险箱在本次进程内是否已解锁。
 *
 * 保险箱不加密文件，它的承诺是「没有通过系统认证就看不到私密内容」。权限状态必须比单个页面活得久：
 * 已存页解锁后进入播放器、任务页、分享入口都应当沿用同一次认证，而不是各页各弹一次；锁定时则一起失效。
 *
 * 只存在内存里：进程被杀后回到锁定状态。测试可调用 [resetForTests] 清理。
 */
object VaultAccess {
    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    fun unlock() { unlocked = true }

    fun lock() { unlocked = false }

    /** 非私密记录永远可访问；私密记录只有在本次进程认证过之后才可访问。 */
    fun allows(record: DownloadRecord): Boolean = !record.isPrivate || unlocked

    internal fun resetForTests() { unlocked = false }
}
