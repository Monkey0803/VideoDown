package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DownloadFailureKind
import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.videodown.douyin.data.download.DownloadNetworkPolicy
import com.videodown.douyin.data.download.WifiRequiredException
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failure the bundled engine reported.
 *
 * Carries only the kind: the wording lives in resources (see `core/ErrorResources.kt`), because the
 * same failure is shown on the parse page, on a task card and in a notification.
 */
class VideoEngineException(val kind: String) : Exception(kind) {
    val failureKind: DownloadFailureKind get() = when (kind) {
        "NO_AUDIO" -> DownloadFailureKind.NO_AUDIO
        "ACCESS" -> DownloadFailureKind.ACCESS_DENIED
        "EXPIRED" -> DownloadFailureKind.LINK_EXPIRED
        "RATE" -> DownloadFailureKind.RATE_LIMITED
        "AUTH" -> DownloadFailureKind.LOGIN_REQUIRED
        "SPACE" -> DownloadFailureKind.STORAGE_FULL
        "STORAGE_ACCESS" -> DownloadFailureKind.STORAGE_ACCESS
        "NETWORK", "TIMEOUT" -> DownloadFailureKind.NETWORK
        "CHANGED" -> DownloadFailureKind.FORMAT_CHANGED
        "RUNTIME" -> DownloadFailureKind.RUNTIME
        else -> DownloadFailureKind.UNSUPPORTED
    }
}

/** Uses the pinned AAR's binaries, in a process group so cancelling also stops FFmpeg. */
class VideoEngineRuntime(private val context: Context) {
    private val _version = MutableStateFlow(context.getString(com.videodown.douyin.R.string.engine_not_initialised, ENGINE_COMPONENT_VERSION))
    val version = _version.asStateFlow()
    private var ready = false
    private val nativeDir get() = File(context.applicationInfo.nativeLibraryDir)
    private val base get() = File(context.noBackupFilesDir, "youtubedl-android")
    private val bridge get() = File(base, "video_engine.py")

    @Synchronized private fun initialize() {
        if (ready) return
        YoutubeDL.init(context)
        FFmpeg.init(context)
        if (android.os.Process.is64Bit()) {
            val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.first()
            val patch = "engine/native/$abi"
            val libraryDirectory = File(base, "packages/ffmpeg/usr/lib")
            for (name in listOf("libwebp.so", "libwebpdecoder.so", "libwebpmux.so", "libwebpdemux.so", "libsharpyuv.so")) {
                val destination = File(libraryDirectory, name)
                val temporary = File(libraryDirectory, "$name.new")
                context.assets.open("$patch/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(destination)) { AppText.get(R.string.engine_install_16k_failed) }
            }
        }
        val bundledVersion = context.assets.open("engine/VERSION.txt").bufferedReader().use { it.readText() }
        val marker = File(base, "engine-version.txt")
        if (!marker.exists() || marker.readText() != bundledVersion) {
            val core = File(base, "yt-dlp/yt-dlp")
            val staging = File(core.parentFile, "yt-dlp.new")
            context.assets.open("engine/yt-dlp").use { input -> staging.outputStream().use { input.copyTo(it) } }
            check(staging.renameTo(core)) { AppText.get(R.string.engine_install_core_failed) }
            marker.writeText(bundledVersion)
        }
        context.assets.open("video_engine.py").use { input -> bridge.outputStream().use { input.copyTo(it) } }
        ready = true
    }

    suspend fun inspectVersion() {
        try {
            execute(buildJsonObject { put("mode", "version") }) { event ->
                if (event.text("event") == "version") _version.value = context.getString(
                    com.videodown.douyin.R.string.engine_version, event.text("version"), ENGINE_COMPONENT_VERSION,
                )
            }
        } catch (e: CancellationException) { throw e
        } catch (e: VideoEngineException) {
            _version.value = if (e.kind == "SPACE") {
                context.getString(com.videodown.douyin.core.engineKindRes(e.kind))
            } else {
                context.getString(com.videodown.douyin.R.string.engine_init_failed)
            }
        } catch (_: Exception) { _version.value = context.getString(com.videodown.douyin.R.string.engine_reinstall) }
    }

    suspend fun execute(
        request: JsonObject,
        policy: DownloadNetworkPolicy = DownloadNetworkPolicy.Unrestricted,
        onEvent: suspend (JsonObject) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        try { initialize() }
        catch (error: Exception) {
            val noSpace = generateSequence<Throwable>(error) { it.cause }.take(8).any {
                it.message.orEmpty().let { message -> message.contains("ENOSPC", true) || message.contains("no space left", true) }
            }
            throw VideoEngineException(if (noSpace) "SPACE" else "RUNTIME")
        } catch (_: LinkageError) { throw VideoEngineException("RUNTIME") }
        ensureActive()
        if (!policy.isAllowed()) throw WifiRequiredException()
        val options = JsonObject(request + mapOf(
            "ffmpeg" to JsonPrimitive(File(nativeDir, "libffmpeg.so").absolutePath),
            "quickjs" to JsonPrimitive(File(nativeDir, "libqjs.so").absolutePath),
        ))
        val python = File(base, "packages/python/usr")
        val process = try { ProcessBuilder(File(nativeDir, "libpython.so").absolutePath, "-u", bridge.absolutePath,
            File(base, "yt-dlp/yt-dlp").absolutePath).apply {
            environment().apply {
                put("LD_LIBRARY_PATH", "${python.absolutePath}/lib:${File(base, "packages/ffmpeg/usr/lib").absolutePath}")
                put("PYTHONHOME", python.absolutePath)
                put("SSL_CERT_FILE", File(python, "etc/tls/cert.pem").absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("HOME", context.noBackupFilesDir.absolutePath)
                put("PATH", "${System.getenv("PATH")}:${nativeDir.absolutePath}")
            }
            redirectErrorStream(true)
        }.start() } catch (_: java.io.IOException) { throw VideoEngineException("RUNTIME") }
        val group = AtomicInteger(0)
        fun terminate() {
            group.get().takeIf { it > 0 }?.let { runCatching { Os.kill(-it, OsConstants.SIGKILL) } }
            process.destroy()
        }
        val owner = currentCoroutineContext().job
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                policy.allowed.collect { if (!it) { terminate(); owner.cancel(WifiRequiredException()) } }
                awaitCancellation()
            } finally { terminate() }
        }
        try {
            var failure: VideoEngineException? = null
            process.inputStream.bufferedReader().use { reader ->
                // Python cannot start extraction until we have its process group ID.
                val hello = reader.readLine() ?: throw VideoEngineException("UNSUPPORTED")
                val handshake = runCatching { Json.parseToJsonElement(hello) as JsonObject }.getOrNull()
                if (handshake?.text("event") != "pid") throw VideoEngineException("UNSUPPORTED")
                group.set(handshake.number("pid")?.toInt() ?: throw VideoEngineException("UNSUPPORTED"))
                ensureActive()
                onEvent(handshake)
                process.outputStream.bufferedWriter().use { it.write(options.toString()); it.newLine() }
                while (true) {
                    val line = reader.readLine() ?: break
                    ensureActive()
                    if (line.length > 8 * 1024 * 1024) throw VideoEngineException("UNSUPPORTED")
                    val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                    when (event.text("event")) {
                        "pid" -> group.set(event.number("pid")?.toInt() ?: 0)
                        "error" -> failure = VideoEngineException(event.text("kind") ?: "UNSUPPORTED")
                        else -> onEvent(event)
                    }
                }
            }
            ensureActive()
            val code = process.waitFor()
            failure?.let { throw it }
            if (code != 0) throw VideoEngineException("UNSUPPORTED")
        } catch (error: java.io.IOException) {
            ensureActive()
            throw VideoEngineException("NETWORK")
        } finally {
            watcher.cancel()
            terminate()
            withContext(NonCancellable) { watcher.join() }
            runCatching { process.inputStream.close() }
        }
    }
}

/** The component version the app pins; shown next to the extracted engine version. */
private const val ENGINE_COMPONENT_VERSION = "0.18.1"
