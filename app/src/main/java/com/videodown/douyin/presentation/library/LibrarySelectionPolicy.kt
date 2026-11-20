package com.videodown.douyin.presentation.library

/**
 * 多选态的区间选择。
 *
 * 长按某张封面时，取上一次点选（锚点）到当前项之间的连续区间；没有锚点时只选当前项。
 * 纯逻辑便于 JVM 覆盖，也避免在页面里复制一遍下标计算。
 */
internal fun selectionRange(ids: List<String>, anchorId: String?, targetId: String): Set<String> {
    val target = ids.indexOf(targetId)
    if (target < 0) return emptySet()
    val anchor = anchorId?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: return setOf(targetId)
    return ids.subList(minOf(anchor, target), maxOf(anchor, target) + 1).toSet()
}
