package com.videodown.douyin

import android.content.Intent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Searching and re-ordering a real task list.
 *
 * Every seeded title carries a random token and the first search filters on it, so the assertions
 * cannot be broken by records another test or an earlier run left behind.
 */
@RunWith(AndroidJUnit4::class)
class TaskSearchDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val seeded = mutableListOf<String>()
    private val token = "tq${UUID.randomUUID().toString().take(6)}"

    @After fun cleanup() = runBlocking {
        seeded.forEach { ServiceLocator.historyRepository.delete(it) }
        Unit
    }

    private fun record(index: Int, title: String, author: String, updatedAt: Long) = DownloadRecord(
        id = UUID.randomUUID().toString(),
        awemeId = "task-search-$index",
        title = "$token $title",
        authorNickname = author,
        status = DownloadStatus.COMPLETED,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        totalBytes = 1024L * (index + 1),
        downloadedBytes = 1024L * (index + 1),
    )

    private fun search(text: String) {
        compose.onNodeWithTag("task-search").performTextClearance()
        compose.onNodeWithTag("task-search").performTextInput(text)
        compose.waitForIdle()
        // Proves the query really replaced the previous one rather than piling up.
        compose.onNodeWithTag("task-search").assert(hasText(text))
        // An open keyboard shrinks the list viewport, which would leave later cards uncomposed.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun assertAllSeededVisible() {
        listOf("甲 海边日落", "乙 做饭教程", "丙 海边日出").forEach { title ->
            assertTrue("$title must be composed for an order assertion", count(title) > 0)
        }
    }

    @Test fun theTaskListCanBeSearchedAndReordered() = runBlocking {
        val base = System.currentTimeMillis()
        val records = listOf(
            record(0, "甲 海边日落", "小王", base),
            record(1, "乙 做饭教程", "小李", base + 2_000),
            record(2, "丙 海边日出", "小王", base + 1_000),
        )
        records.forEach { ServiceLocator.historyRepository.upsert(it); seeded += it.id }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("任务").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(token, substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Filtering on the token isolates exactly these three tasks.
        assertEquals(3, compose.onAllNodesWithText(token, substring = true).fetchSemanticsNodes().size)

        // Title search: 乙 does not contain 海边.
        search("$token 海边")
        assertTrue("甲 must match 海边", count("甲 海边日落") > 0)
        assertTrue("丙 must match 海边", count("丙 海边日出") > 0)
        assertEquals("乙 must be filtered out", 0, count("乙 做饭教程"))

        // Author search: only 甲 and 丙 belong to 小王.
        search("$token 小王")
        assertTrue(count("甲 海边日落") > 0)
        assertTrue(count("丙 海边日出") > 0)
        assertEquals(0, count("乙 做饭教程"))

        // A word nobody has empties the list instead of showing everything.
        search("$token 雪山")
        assertEquals(0, count("甲 海边日落"))
        assertTrue("An empty result must say so",
            compose.onAllNodesWithText("没有符合条件的任务").fetchSemanticsNodes().isNotEmpty())

        // With only these three tasks in view, the oldest-first order is deterministic.
        search(token)
        assertAllSeededVisible()
        compose.onNodeWithText("最早任务").performClick()
        compose.waitForIdle()
        assertAllSeededVisible()
        val firstY = compose.onAllNodesWithTag("task-${records[0].id}")[0].fetchSemanticsNode().positionInRoot.y
        val thirdY = compose.onAllNodesWithTag("task-${records[2].id}")[0].fetchSemanticsNode().positionInRoot.y
        assertTrue("最早任务 must place 甲 above 丙 (y=$firstY vs $thirdY)", firstY < thirdY)

        compose.onNodeWithText("最新任务").performClick()
        compose.waitForIdle()
        assertAllSeededVisible()
        val newestY = compose.onAllNodesWithTag("task-${records[1].id}")[0].fetchSemanticsNode().positionInRoot.y
        val middleY = compose.onAllNodesWithTag("task-${records[2].id}")[0].fetchSemanticsNode().positionInRoot.y
        assertTrue("最新任务 must place 乙 above 丙 (y=$newestY vs $middleY)", newestY < middleY)
    }
}
