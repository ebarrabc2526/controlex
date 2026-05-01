package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ServerTransmitter(private val project: Project) : Disposable {

    enum class Status { CONNECTING, CONNECTED, DISCONNECTED, NO_INTERNET }

    @Volatile var status: Status = Status.CONNECTING

    private val log = Logger.getInstance(ServerTransmitter::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-Transmit").apply { isDaemon = true }
    }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile private var future: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    val clientId: String by lazy { loadOrCreateClientId() }

    fun start() {
        if (stopped || future != null) return
        scheduleNext(5_000L)
    }

    private fun scheduleNext(delayMs: Long = -1L) {
        if (stopped) return
        val d = if (delayMs >= 0) delayMs else project.service<DynamicConfig>().transmitFreqMs
        future = scheduler.schedule({
            try { transmit() }
            catch (t: Throwable) { log.warn("Controlex: error en transmisión al servidor", t) }
            finally { if (!stopped) scheduleNext() }
        }, d, TimeUnit.MILLISECONDS)
    }

    private fun transmit() {
        val jpeg = try {
            ScreenshotCapturer.captureAllScreensAsJpeg(ControlexConfig.JPEG_QUALITY)
        } catch (t: Throwable) {
            log.warn("Controlex: error capturando JPEG para transmisión", t)
            return
        }
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val dynCfg = project.service<DynamicConfig>()
        val body = buildRequestJson(b64, dynCfg)

        var newStatus: Status = Status.DISCONNECTED
        var responseBody: String? = null

        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(ControlexConfig.SERVER_URL + "/api/screenshot"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(15))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() in 200..299) {
                newStatus = Status.CONNECTED
                responseBody = response.body()
            } else {
                newStatus = Status.DISCONNECTED
                responseBody = null
                log.warn("Controlex: servidor respondió ${response.statusCode()}")
            }
        } catch (e: IOException) {
            val cause: Throwable = e.cause ?: e
            newStatus = if (cause is UnknownHostException) Status.NO_INTERNET else Status.DISCONNECTED
            responseBody = null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (e: Exception) {
            newStatus = Status.DISCONNECTED
            responseBody = null
            log.warn("Controlex: error de transmisión", e)
        }

        val changed = status != newStatus
        status = newStatus
        if (changed) {
            ApplicationManager.getApplication().invokeLater {
                WindowManager.getInstance().getStatusBar(project)
                    ?.updateWidget(ControlexStatusWidget.ID)
            }
        }

        responseBody?.let { processResponse(it, dynCfg) }
    }

    private fun buildRequestJson(b64: String, cfg: DynamicConfig): String {
        val ip = localIp()
        val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "unknown" }
        return buildString {
            append("""{"clientId":"""); append(esc(clientId))
            append(""","ip":"""); append(esc(ip))
            append(""","hostname":"""); append(esc(hostname))
            append(""","projectName":"""); append(esc(project.name))
            append(""","intellijUser":"""); append(esc(gitUserName()))
            append(""","osUser":"""); append(esc(System.getProperty("user.name") ?: "unknown"))
            append(""","captureFreqMin":"""); append(cfg.captureMinMs / 1000)
            append(""","captureFreqMax":"""); append(cfg.captureMaxMs / 1000)
            append(""","transmitFreqSeconds":"""); append(cfg.transmitFreqMs / 1000)
            append(""","screenshot":"""); append(esc(b64))
            append("}")
        }
    }

    private fun processResponse(json: String, cfg: DynamicConfig) {
        try {
            // Each command is a flat JSON object; we allow one level of nesting for sig values
            val objRegex = Regex("""\{[^{}]*\}""")
            for (match in objRegex.findAll(json)) {
                val obj = match.value
                val type = strField("type", obj) ?: continue
                when (type) {
                    "message" -> strField("text", obj)?.let { showMessage(it) }
                    "config"  -> applyConfig(
                        cfg,
                        numField("captureFreqMin", obj),
                        numField("captureFreqMax", obj),
                        numField("transmitFreqSeconds", obj)
                    )
                    // Signed commands from /api/dashboard/command arrive via polling fallback
                    else -> ApplicationManager.getApplication().invokeLater {
                        RemoteCommandHandlers.execute(project, obj)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Controlex: error procesando respuesta del servidor", e)
        }
    }

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun showMessage(text: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showMessageDialog(project, text, "Controlex", Messages.getInformationIcon())
        }
    }

    private fun applyConfig(cfg: DynamicConfig, min: Long?, max: Long?, transmit: Long?) {
        if (min != null && min >= 1L) cfg.captureMinMs = min * 1000L
        if (max != null) cfg.captureMaxMs = maxOf(max, cfg.captureMinMs / 1000L) * 1000L
        if (transmit != null && transmit >= 5L) cfg.transmitFreqMs = transmit * 1000L
        log.info("Controlex: config remota aplicada captureMin=${cfg.captureMinMs/1000}s captureMax=${cfg.captureMaxMs/1000}s transmit=${cfg.transmitFreqMs/1000}s")
    }

    private fun loadOrCreateClientId(): String {
        // Per-machine ID (NOT per-project) so that opening different projects
        // does not register the same student as multiple clients.
        val home = System.getProperty("user.home") ?: return UUID.randomUUID().toString()
        val dir = File(home, ".${ControlexConfig.DIR_NAME}").also { it.mkdirs() }
        val f = File(dir, ControlexConfig.CLIENT_ID_FILE_NAME)
        if (f.exists()) {
            val v = f.readText().trim()
            if (v.isNotEmpty()) return v
        }
        // Migration: if a project-scoped id already exists from older versions, reuse it
        val basePath = project.basePath
        if (basePath != null) {
            val legacy = File(File(basePath, ControlexConfig.DIR_NAME), ControlexConfig.CLIENT_ID_FILE_NAME)
            if (legacy.exists()) {
                val v = legacy.readText().trim()
                if (v.isNotEmpty()) {
                    f.writeText(v)
                    return v
                }
            }
        }
        return UUID.randomUUID().toString().also { f.writeText(it) }
    }

    private fun localIp(): String {
        // Best approach: ask the OS which interface it would use to reach the
        // internet. No packet is actually sent (UDP connect() just sets up the
        // route), but it returns the IP of the right adapter.
        try {
            java.net.DatagramSocket().use { sock ->
                sock.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
                val ip = sock.localAddress?.hostAddress
                if (ip != null && !ip.startsWith("169.254.") && !ip.startsWith("0.")) return ip
            }
        } catch (_: Exception) { /* fall through */ }

        // Fallback: scan interfaces, prefer LAN ranges, exclude APIPA/loopback.
        return try {
            val all = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { nic -> nic.inetAddresses.asSequence() }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.map { it.hostAddress }
                ?.filter { !it.startsWith("169.254.") && !it.startsWith("127.") }
                ?.toList() ?: emptyList()

            fun isPrivate(ip: String) =
                ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)

            all.firstOrNull(::isPrivate)
                ?: all.firstOrNull()
                ?: InetAddress.getLocalHost().hostAddress
                ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    private fun gitUserName(): String = try {
        val f = File(System.getProperty("user.home"), ".gitconfig")
        if (f.exists()) f.readLines().firstOrNull { it.trim().startsWith("name =") }
            ?.substringAfter("name =")?.trim()
        else null
    } catch (e: Exception) { null } ?: System.getProperty("user.name") ?: "unknown"

    private fun esc(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    override fun dispose() {
        stopped = true
        future?.cancel(false)
        // Best-effort goodbye so the dashboard knows immediately
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(ControlexConfig.SERVER_URL + "/api/disconnect"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
                .POST(HttpRequest.BodyPublishers.ofString("""{"clientId":${esc(clientId)}}""", StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(2))
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (_: Throwable) { /* shutdown – best effort */ }
        scheduler.shutdownNow()
    }
}
