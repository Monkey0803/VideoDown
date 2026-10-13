package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.compose.AsyncImage
import com.videodown.douyin.presentation.theme.VideoDownTheme
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coil 的取图链路（迁移到 Coil 3 之后）。
 *
 * 为什么要专门测 http：Coil 3 把网络取图拆成了独立产物（`coil-network-okhttp`），**不装它不会报错**，
 * 只是所有远程封面静默不显示——本工程的其它用例大多用本地文件或空地址，正好照不到这个坑。
 * 这里起一个只服务一次的本地 HTTP，让 Coil 真的走一次网络取图。
 *
 * 本地文件那条路径由 `PlayerCoverTransitionDeviceTest` / `LibraryReorderDeviceTest` 的封面截图覆盖。
 */
@RunWith(AndroidJUnit4::class)
class CoilImageLoadingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val server = ServerSocket(0)
    private val port get() = server.localPort

    @After fun close() {
        runCatching { server.close() }
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF00C853.toInt())
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** 只服务一次请求：把请求头读完，然后连着 body 一起回。 */
    private fun serveOnce(body: ByteArray) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/png\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
    }

    @Test fun aRemoteCoverLoadsOverHttp() {
        serveOnce(pngBytes())
        val loaded = AtomicBoolean(false)
        compose.setContent {
            VideoDownTheme {
                AsyncImage(
                    model = "http://127.0.0.1:$port/cover.png",
                    contentDescription = "remote-cover",
                    onSuccess = { loaded.set(true) },
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        compose.waitUntil(10_000) { loaded.get() }
        assertTrue("远程封面必须真的能被取回来（否则就是没装网络取图器）", loaded.get())
    }
}
