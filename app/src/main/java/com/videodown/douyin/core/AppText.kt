package com.videodown.douyin.core

import android.content.Context
import android.content.res.Resources

/**
 * The one way a context-free class can still produce a message from resources.
 *
 * Most data-layer classes here are deliberately context-free — the JVM suite constructs them
 * directly and never needs a framework — but a few of them must still produce text: an invariant a
 * caller may surface, a diagnostic thrown by an HTTP client, a log line. Threading a `Context`
 * through every constructor would spread the framework through the data layer for the sake of a
 * handful of strings, so the application installs a single resolver at startup instead.
 *
 * This is for **messages**, never for something the user reads as part of the interface: the screens
 * read their own resources. Before [install] (unit tests) [get] returns `resource:<id>`, which is a
 * stable placeholder — no test in this repo asserts on a message.
 */
object AppText {
    private var resources: Resources? = null

    fun install(context: Context) {
        resources = context.applicationContext.resources
    }

    /**
     * Test seam: let the JVM suite resolve ids without a `Context`.
     *
     * The data layer's JVM tests exercise parsing that produces a title or a label, so they need to
     * see *which* wording was chosen rather than the translated text. `app/src/test` installs a
     * resolver backed by `R.string`'s field names.
     */
    internal var testResolver: ((Int, Array<out Any>) -> String)? = null

    fun get(resId: Int, vararg args: Any): String {
        testResolver?.let { return it(resId, args) }
        val res = resources ?: return "resource:$resId"
        return if (args.isEmpty()) {
            res.getString(resId)
        } else {
            // `%1$s` style placeholders; args are rendered by the platform formatter.
            res.getString(resId, *args)
        }
    }
}
