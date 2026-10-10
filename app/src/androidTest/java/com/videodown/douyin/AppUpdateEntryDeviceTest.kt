package com.videodown.douyin

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.update.AppUpdateChecker
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.settings.SettingsScreen
import com.videodown.douyin.presentation.settings.UpdateViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level checks for 「我的 → 检查更新」: the entry is reachable, the dialog reports the
 * installed version, and a configured manifest drives the available / up-to-date states.
 *
 * The manifest is served by an OkHttp interceptor so the test never needs a real endpoint.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateEntryDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "app-update-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
    }
    private val preferences = AppPreferences(isolated)

    @After fun cleanup() {
        context.deleteSharedPreferences(preferenceName)
    }

    /** Shows the real settings screen wired to a checker that always answers with [manifest]. */
    private fun showSettings(manifest: String) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(manifest.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val model = UpdateViewModel(AppUpdateChecker(client, ServiceLocator.appVersion), preferences)
        compose.runOnUiThread {
            compose.activity.setContent {
                VideoDownTheme { SettingsScreen(preferences = preferences, updateViewModel = model) }
            }
        }
    }

    @Test fun entryShowsInstalledVersionAndReportsANewerBuild() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings(
            """{"versionCode":99,"versionName":"9.9.9","downloadUrl":"https://example.com/app.apk",
                "notes":"测试更新说明"}""",
        )
        compose.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed().performClick()
        // The label is built by the screen from resources now.
        val version = ServiceLocator.appVersion
        compose.onNodeWithText(
            context.getString(com.videodown.douyin.R.string.update_current_version,
                context.getString(com.videodown.douyin.R.string.version_label_both, version.versionName, version.versionCode)),
        ).assertIsDisplayed()
        compose.onNodeWithText("发现新版本：9.9.9").assertIsDisplayed()
        compose.onNodeWithText("测试更新说明").assertIsDisplayed()
        compose.onNodeWithText("下载新版").assertIsDisplayed()
    }

    @Test fun updateSourceCanBeConfiguredFromTheDialog() {
        showSettings("""{"versionCode":99,"versionName":"9.9.9"}""")
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("尚未配置更新源地址，请先设置版本清单地址").assertIsDisplayed()
        compose.onNodeWithText("更新源").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("https://updates.example.com/version.json")
        compose.onNodeWithText("保存并检查").performClick()
        compose.onNodeWithText("发现新版本：9.9.9").assertIsDisplayed()
        assertEquals("https://updates.example.com/version.json", preferences.updateManifestUrl)
    }

    /**
     * 动作行里的三个按钮同处一行：两个次要操作若被 Column 包住，会被固定成上下两行，确认按钮反倒在
     * 右侧独占一行。只比较相对坐标，不写死像素；宽度真的不够时动作行仍会换行。
     */
    @Test fun actionsShareOneRowWhenWidthAllows() {
        showSettings("""{"versionCode":99,"versionName":"9.9.9"}""")
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("尚未配置更新源地址，请先设置版本清单地址").assertIsDisplayed()
        val bounds = listOf("更新源", "重新检查", "完成").map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        val tops = bounds.map { it.top.value }
        assertTrue("三个操作按钮应同处一行，实际顶部坐标：$tops", tops.max() - tops.min() < 1f)
        val lefts = bounds.map { it.left.value }
        assertEquals("同一行的按钮应从左到右排列", lefts.sorted(), lefts)
    }

    @Test fun reportsUpToDateForTheInstalledVersion() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings("""{"versionCode":${ServiceLocator.appVersion.versionCode},"versionName":"1.0.1"}""")
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("已是最新版本。").assertIsDisplayed()
    }
}
