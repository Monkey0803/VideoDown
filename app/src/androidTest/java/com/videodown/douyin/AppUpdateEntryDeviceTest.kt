package com.videodown.douyin

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import java.util.Locale
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

    private companion object {
        /** 一份同时带中英说明的清单，用来断言弹窗按界面语言取哪一版。 */
        const val BOTH_LANGUAGE_NOTES =
            """{"versionCode":99,"versionName":"9.9.9","notes":"中文更新说明：新增合集解析","notesEn":"English note: adds collection parsing"}"""
    }

    /**
     * Shows the real settings screen wired to a checker that always answers with [manifest].
     *
     * [english] 把界面语言固定成英文来渲染：不切系统/应用语言（那会重建 Activity、冲掉这里的替身
     * 界面），而是同时提供英文的 `LocalConfiguration` 与 `LocalContext`，`stringResource` 与
     * 弹窗里读 `LocalConfiguration` 的语言判断都会跟着变成英文。
     */
    private fun showSettings(manifest: String, english: Boolean = false) {
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
                if (!english) {
                    VideoDownTheme { SettingsScreen(preferences = preferences, updateViewModel = model) }
                } else {
                    val configuration = Configuration(compose.activity.resources.configuration).apply {
                        setLocale(Locale.ENGLISH)
                    }
                    val englishContext = compose.activity.createConfigurationContext(configuration)
                    // 换成非 Activity 的 Context 后，SAF/权限的 ActivityResultRegistry 得显式补回来。
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalContext provides englishContext,
                        LocalActivityResultRegistryOwner provides compose.activity,
                    ) {
                        VideoDownTheme { SettingsScreen(preferences = preferences, updateViewModel = model) }
                    }
                }
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

    /**
     * 清单同时带 `notes` 与 `notesEn` 时，弹窗显示与界面语言匹配的那一份。
     *
     * 语言用渲染出来的配置固定（见 [showSettings]），中文/英文两条分支在任何设备上都能断言。
     * 注意：弹窗是独立窗口，里面的 `stringResource` 仍按 Activity 的资源渲染（CompositionLocal 覆盖
     * 不跨窗口），所以英文这条只断言**来自清单**的说明文本，不断言弹窗自身的资源文案。
     */
    @Test fun notesFollowTheChineseInterface() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings(BOTH_LANGUAGE_NOTES, english = false)
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("发现新版本：9.9.9").assertIsDisplayed()
        compose.onNodeWithText("中文更新说明：新增合集解析").assertIsDisplayed()
        assertTrue("中文界面不该显示英文说明", compose.onAllNodesWithText("English note: adds collection parsing").fetchSemanticsNodes().isEmpty())
    }

    @Test fun notesFollowTheEnglishInterface() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings(BOTH_LANGUAGE_NOTES, english = true)
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        compose.onNodeWithText("English note: adds collection parsing").assertIsDisplayed()
        assertTrue("英文界面不该显示中文说明", compose.onAllNodesWithText("中文更新说明：新增合集解析").fetchSemanticsNodes().isEmpty())
    }

    @Test fun reportsUpToDateForTheInstalledVersion() {
        preferences.updateManifestUrl = "https://updates.example.com/version.json"
        showSettings("""{"versionCode":${ServiceLocator.appVersion.versionCode},"versionName":"1.0.1"}""")
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("已是最新版本。").assertIsDisplayed()
    }
}
