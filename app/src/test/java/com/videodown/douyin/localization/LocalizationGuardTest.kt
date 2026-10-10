package com.videodown.douyin.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the localization migration honest.
 *
 * User-facing copy belongs in `res/values/strings.xml` (Chinese) plus `res/values-en/strings.xml`,
 * not in Kotlin string literals. This test does two things: it fails if the two resource files stop
 * matching key for key, and it fails if the number of Chinese literals in `src/main/java` grows
 * above [BUDGET].
 *
 * The budget is a ratchet: lower it as the migration proceeds and never raise it. When it reaches
 * zero the code carries no Chinese copy at all.
 */
class LocalizationGuardTest {

    private val chinese = Regex("[\\u4e00-\\u9fff]")

    private fun moduleRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory.parentFile != null && !File(directory, "src/main/res/values/strings.xml").isFile) {
            directory = directory.parentFile
        }
        return directory
    }

    private fun keys(path: String): Set<String> =
        Regex("""<string name="([^"]+)"""")
            .findAll(File(moduleRoot(), path).readText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * String literals only: comments and KDoc may of course be Chinese.
     *
     * Literals inside [DATA_FUNCTIONS] are skipped: they match text that is *already stored* on
     * user devices, so they are data rather than copy and must keep matching exactly.
     */
    private fun chineseLiterals(): List<Pair<String, String>> {
        val sources = File(moduleRoot(), "src/main/java").walkTopDown().filter { it.extension == "kt" }
        return sources.flatMap { file ->
            var text = file.readText()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            // A line marked `l10n-ignore` is exempt: it is a developer log or an on-device match,
            // never copy the user reads. The marker keeps each exemption visible in review.
            text = text.lines().joinToString("\n") { line ->
                if (line.contains("l10n-ignore")) "" else line.substringBefore("//")
            }
            DATA_FUNCTIONS.forEach { text = blankOut(text, it) }
            Regex("""\"((?:[^"\\]|\\.)*)\"""").findAll(text)
                .map { it.groupValues[1] }
                .filter { chinese.containsMatchIn(it) }
                .map { file.name to it }
                .toList()
        }.toList()
    }

    /** Replaces the body of `fun <name>(` with spaces so its literals are not counted. */
    private fun blankOut(source: String, function: String): String {
        val marker = "fun $function("
        val start = source.indexOf(marker)
        if (start < 0) return source
        val open = source.indexOf('{', source.indexOf('=', start).let { if (it in 0..<start + 400) it else start })
        val bodyStart = source.indexOf('{', start).takeIf { it > 0 } ?: return source
        var depth = 0
        var index = bodyStart
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return source.substring(0, bodyStart) + " ".repeat(index - bodyStart + 1) + source.substring(index + 1) }
            }
            index++
        }
        return source
    }

    @Test
    fun `both languages carry exactly the same keys`() {
        val chinese = keys("src/main/res/values/strings.xml")
        val english = keys("src/main/res/values-en/strings.xml")
        assertTrue("The default resources must not be empty", chinese.isNotEmpty())
        assertEquals("Keys missing from values-en", emptySet<String>(), chinese - english)
        assertEquals("Keys present only in values-en", emptySet<String>(), english - chinese)
    }

    @Test
    fun `the supported languages are declared for per-app language settings`() {
        val config = File(moduleRoot(), "src/main/res/xml/locales_config.xml").readText()
        assertTrue(config, config.contains("<locale android:name=\"zh-CN\""))
        assertTrue(config, config.contains("<locale android:name=\"en\""))
    }

    @Test
    fun `no chinese copy is added while the migration is in flight`() {
        val found = chineseLiterals()
        assertTrue(
            "Chinese literals under src/main/java rose to ${found.size}, above the recorded budget $BUDGET. " +
                "Move the new copy into res/values/strings.xml and res/values-en/strings.xml.",
            found.size <= BUDGET,
        )
    }

    @Test
    fun `the budget only ratchets down`() {
        assertTrue(
            "BUDGET ($BUDGET) may not be raised above the recorded high-water mark ($RECORDED). " +
                "Lower it as copy moves into resources.",
            BUDGET <= RECORDED,
        )
    }

    companion object {
        /**
         * Functions whose Chinese literals are data, not copy.
         *
         * Lines ending in `// l10n-ignore: reason` are exempt for the same kind of reason (logs).
         *
         * `legacyFailureKind` matches error messages already written into users' history files by
         * earlier builds; rewriting or translating those literals would silently stop recognising
         * old failures. Exempting them by name keeps the exemption reviewable.
         */
        val DATA_FUNCTIONS = listOf("legacyFailureKind")

        /** Measured when the migration started: 842 Chinese literals across 77 files. */
        const val RECORDED = 842

        /**
         * The remaining budget; lower it in the same commit that moves strings out.
         * 842 → 824 (update dialog) → 766 (storage/export dialogs, library actions) → 672 (settings screen) → 597 (home screen) → 472 (history + library screens, ViewModel carries keys only) → 386 (player, batch import, browser assist) → 345 (failure guidance moved out of domain) → 316 (shortcuts, selection toolbar, extract/organise dialogs) → 285 (enum labels; AppPreferences reports keys) → 280 (structured HTTP errors) → 248 (engine kinds, background-service keys) → 234 (typed gallery failures) → 223 (typed X parse failures) → 214 (export summary as counts + reason kinds) → 209 (VideoPlatform carries no label) → 206 (platform-prefixed + input validation) → 196 (times/badge report parts) → 171 (nav tabs, PiP, subtitle names, completion card) → 151 (batch import, Douyin, Weibo) → 142 (vault, share, X, generic) → 130 (update check, parse outcomes) → 98 (vault, sleep, wifi pause) → **0**.

Every remaining Chinese literal now carries an inline `l10n-ignore` marker with a reason (internal
invariant, developer log, developer diagnostic, or persisted content), or lives inside an exempt
function (`DATA_FUNCTIONS`). So this budget is 0 and the test's real job is to reject *new*
unexplained copy.
         */
        const val BUDGET = 0
    }
}
