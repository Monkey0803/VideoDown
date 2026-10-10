package com.videodown.douyin.core

/**
 * Makes [AppText] resolvable in JVM tests.
 *
 * The JVM suite has no `Context`, so resource ids have no text. Instead of asserting a translation,
 * tests assert which resource was used — `quality_original`, `fallback_title_weibo_gif` and so on —
 * with the formatted arguments appended, e.g. `media_label_video(2)`.
 */
object TestText {
    private val names: Map<Int, String> = com.videodown.douyin.R.string::class.java.fields
        .associate { it.getInt(null) to it.name }

    fun install() {
        AppText.testResolver = { id, args ->
            val name = names[id] ?: "resource:$id"
            if (args.isEmpty()) name else name + "(" + args.joinToString(",") + ")"
        }
    }
}
