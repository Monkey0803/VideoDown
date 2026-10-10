package com.videodown.douyin.data.remote

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
internal fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun mediaUrl(raw: String?): String? = raw?.replace("&amp;", "&")
    ?.let { if (it.startsWith("//")) "https:$it" else it }?.toHttpUrlOrNull()
    ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
internal fun mp4Url(raw: String?): String? = mediaUrl(raw)?.takeIf { url ->
    url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.endsWith(".mp4") == true
}
internal fun qualityLabel(width: Int?, height: Int?, fallback: String = "原画"): String =  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
    listOfNotNull(width, height).filter { it > 0 }.minOrNull()?.let { "${it}P" } ?: fallback

/** Extract JSON data, never execute scripts received from a website. */
internal fun embeddedJson(html: String, name: String): JsonElement? {
    val assignment = Regex("(?:window\\.)?${Regex.escape(name)}\\s*=\\s*").find(html) ?: return null
    val start = assignment.range.last + 1
    if (html.getOrNull(start) !in listOf('{', '[')) return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in start until html.length) {
        val char = html[index]
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return runCatching { Json.parseToJsonElement(html.substring(start, index + 1)) }.getOrNull()
                }
            }
        }
    }
    return null
}

internal fun objects(root: JsonElement): Sequence<JsonObject> = sequence {
    val queue = ArrayDeque<JsonElement>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited++ < 20_000) {
        when (val value = queue.removeFirst()) {
            is JsonObject -> { yield(value); queue.addAll(value.values) }
            is JsonArray -> queue.addAll(value)
            else -> Unit
        }
    }
}
