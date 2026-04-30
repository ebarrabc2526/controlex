package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CommandStreamReceiver(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CommandStreamReceiver::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-CmdStream").apply { isDaemon = true }
    }
    @Volatile private var stopped = false

    fun start() { connect() }

    private fun connect() {
        if (stopped) return
        val clientId = project.service<ServerTransmitter>().clientId
        val url = ControlexConfig.SERVER_URL + "/api/client/stream?clientId=" +
                  URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
            .GET()
            .timeout(Duration.ofMinutes(60))
            .build()

        val sub = SseLineSubscriber()
        http.sendAsync(req, BodyHandlers.fromLineSubscriber(sub))
            .whenComplete { _, _ ->
                if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
            }
    }

    private inner class SseLineSubscriber : Flow.Subscriber<String> {
        private var event: String? = null
        private val data = StringBuilder()

        override fun onSubscribe(s: Flow.Subscription) { s.request(Long.MAX_VALUE) }

        override fun onNext(line: String) {
            when {
                line.startsWith(":") -> {}
                line.startsWith("event:") -> event = line.substringAfter(":").trim()
                line.startsWith("data:")  -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter(":").trim())
                }
                line.isEmpty() -> {
                    val ev = event
                    val payload = data.toString()
                    event = null
                    data.setLength(0)
                    if (ev == "command" && payload.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            RemoteCommandHandlers.execute(project, payload)
                        }
                    }
                }
            }
        }

        override fun onError(t: Throwable) { log.warn("Controlex: SSE command stream error", t) }
        override fun onComplete() {}
    }

    override fun dispose() {
        stopped = true
        scheduler.shutdownNow()
    }
}
