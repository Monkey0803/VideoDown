package com.videodown.douyin

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 登录态跨进程存活：两次 instrumentation（中间真实 force-stop），默认跳过（会杀掉种子进程）。
 *
 * 这是「有效期跟随站点、App 不自行缩短」的硬证据：只要 WebView 落了盘，重启后登录态还在。
 * 运行方式见 docs/validation/x-login-session-2026-11-16.md。
 */
@RunWith(AndroidJUnit4::class)
class XLoginPersistenceDeviceTest {
    @Test fun storedSessionSurvivesForceStop() {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        assumeTrue("Opt-in two-phase force-stop run", phase == "seed" || phase == "verify")
        val store = ServiceLocator.browserSessions
        if (phase == "seed") {
            runBlocking {
                withContext(Dispatchers.Main) {
                    CookieManager.getInstance().setCookie("https://x.com/", "auth_token=fixture; Path=/; Secure; HttpOnly")
                    CookieManager.getInstance().setCookie("https://x.com/", "ct0=csrf; Path=/; Secure; HttpOnly")
                    CookieManager.getInstance().flush()
                }
                assertTrue(store.xSignedIn())
            }
        } else {
            assertTrue("the WebView session must survive a process restart",
                runBlocking { store.xSignedIn() })
            runBlocking { store.clear() }
        }
    }
}
