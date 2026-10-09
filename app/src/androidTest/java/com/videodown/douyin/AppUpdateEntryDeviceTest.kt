package com.videodown.douyin

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("当前版本：${ServiceLocator.appVersion.label}").assertIsDisplayed()
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

    @Test fun reportsUpToDateForTheInstalledVersion() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings("""{"versionCode":${ServiceLocator.appVersion.versionCode},"versionName":"1.0.1"}""")
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("已是最新版本。").assertIsDisplayed()
    }
}
