package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ScreenStreamService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ScreenStreamService::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-VideoStream").apply { isDaemon = true }
    }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var frameTask: ScheduledFuture<*>? = null
    @Volatile private var stopped = false

    fun start() { connect() }

    private fun connect() {
        if (stopped) return
        val clientId = project.service<ServerTransmitter>().clientId
        val uri = URI.create(
            ControlexConfig.SERVER_WS_URL + "/api/client/video?clientId=" +
            URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        )
        http.newWebSocketBuilder()
            .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
            .buildAsync(uri, WsListener())
            .exceptionally { e ->
                log.warn("Controlex: error conectando WebSocket de video: ${e.message}")
                if (!stopped) scheduler.schedule({ connect() }, 5L, TimeUnit.SECONDS)
                null
            }
    }

    private fun startFrameLoop() {
        frameTask?.cancel(false)
        val intervalMs = (1000L / ControlexConfig.STREAM_FPS).coerceAtLeast(50L)
        frameTask = scheduler.scheduleAtFixedRate({
            if (stopped) { frameTask?.cancel(false); return@scheduleAtFixedRate }
            val localWs = ws ?: return@scheduleAtFixedRate
            if (localWs.isOutputClosed) { frameTask?.cancel(false); return@scheduleAtFixedRate }
            try {
                val jpeg = ScreenshotCapturer.captureAllScreensAsJpeg(ControlexConfig.STREAM_JPEG_QUALITY)
                localWs.sendBinary(ByteBuffer.wrap(jpeg), true)
            } catch (_: Throwable) {}
        }, 0L, intervalMs, TimeUnit.MILLISECONDS)
    }

    private inner class WsListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket
            webSocket.request(Long.MAX_VALUE)
            startFrameLoop()
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            ws = null
            frameTask?.cancel(false)
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn("Controlex: video WS error: ${error.message}")
            ws = null
            frameTask?.cancel(false)
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
        }
    }

    override fun dispose() {
        stopped = true
        frameTask?.cancel(false)
        try { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "") } catch (_: Throwable) {}
        scheduler.shutdownNow()
    }
}
