package com.videodown.douyin.data.backup

import com.videodown.douyin.domain.model.DownloadRecord

/**
 * 已存列表里「用户手动拖出来的顺序」，以及它在备份文件里的往返。
 *
 * 顺序的载体是 [DownloadRecord.sortIndex]：> 0 表示这一条被手动排过，值就是位置；0 表示从没排过，
 * 显示时按规则（更新时间）接在排过的后面。所以这里只谈**手动的那一段**：
 *
 * - 导出：只带走 `sortIndex > 0` 的记录，按位置升序。从没排过时返回 `null`——"没排过"这件事本身
 *   不该被备份成一份顺序，否则恢复一次就把规则顺序固化成手动顺序了。
 * - 恢复：备份里的顺序在前，本机多出来的（仍带着手动位置）按原有相对顺序接在后面，然后整段重新
 *   落位。这样既不会和残留的旧位置撞号，也不会把"备份之后才排过"的记录挤到别的记录前面。
 *
 * **已知语义**：id 是本机的（`DownloadRecord.id`），换一台设备恢复时一个都对不上，[restorableOrder]
 * 会返回 `null`，顺序保持原样——记录本身不随这份备份走，这里也不假装能跨设备还原顺序。
 */
object SavedOrder {

    /**
     * 备份里最多接受多少条。
     *
     * 库正常到不了这个量级；手改或外来的文件不能把一次恢复变成无界操作。
     */
    const val MAX_ENTRIES = 5000

    /** 从记录里取出用户手动排过的那段顺序；从没排过返回 `null`。 */
    fun of(records: List<DownloadRecord>): List<String>? =
        records.asSequence()
            .filter { it.sortIndex > 0 }
            .sortedBy { it.sortIndex }
            .map { it.id }
            .toList()
            .takeIf { it.isNotEmpty() }

    /** 清洗备份里带的顺序：丢空串、按首次出现去重、截到 [MAX_ENTRIES]；清洗后为空返回 `null`。 */
    fun sanitize(raw: List<String>?): List<String>? {
        val cleaned = raw.orEmpty()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ENTRIES)
            .toList()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    /**
     * 把备份里的顺序套到本机记录上，返回恢复后**完整的手动顺序**；不需要改动时返回 `null`。
     *
     * `null` 有两种情形，调用方都只需"什么都不做"：备份没带顺序 / 备份里的 id 在本机一个都不存在
     * （换机恢复），以及恢复后与当前顺序完全一致（避免把一次无变化的恢复报成改动）。
     */
    fun restorableOrder(records: List<DownloadRecord>, backupOrder: List<String>?): List<String>? {
        val wanted = sanitize(backupOrder) ?: return null
        val byId = records.associateBy { it.id }
        val restored = wanted.filter { byId.containsKey(it) }
        if (restored.isEmpty()) return null
        val restoredIds = restored.toSet()
        val leftover = records.asSequence()
            .filter { it.sortIndex > 0 && it.id !in restoredIds }
            .sortedBy { it.sortIndex }
            .map { it.id }
            .toList()
        val target = restored + leftover
        return target.takeIf { it != of(records) }
    }
}
