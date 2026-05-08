package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chat persistente alumno ↔ profesor.
 *
 * - Las entradas se persisten en `~/.controlex/chat.json` (array JSON simple)
 *   para sobrevivir a reinicios de IntelliJ.
 * - Recibe mensajes del profesor vía el comando remoto `chat-message`
 *   (RemoteCommandHandlers) y los del alumno cuando se llama a [send].
 * - [addListener] permite a la tool window pintar nuevas entradas en vivo.
 */
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ChatService::class.java)
    private val file: File = run {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".${ControlexConfig.DIR_NAME}/chat.json")
    }

    data class Message(val id: String, val who: String, val text: String, val at: Long)

    private val messages = mutableListOf<Message>()
    private val listeners = CopyOnWriteArrayList<(Message) -> Unit>()

    init { loadFromDisk() }

    fun all(): List<Message> = synchronized(messages) { messages.toList() }

    fun add(who: String, text: String, at: Long = System.currentTimeMillis()): Message {
        val m = Message(UUID.randomUUID().toString(), who, text, at)
        synchronized(messages) {
            messages.add(m)
            if (messages.size > MAX) messages.subList(0, messages.size - MAX).clear()
        }
        persist()
        for (l in listeners) try { l(m) } catch (_: Throwable) {}
        return m
    }

    fun addListener(l: (Message) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Message) -> Unit) { listeners.remove(l) }

    /** Solo envía por HTTP — el caller ya hizo el [add] local en EDT. */
    fun sendNetworkOnly(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return false
        return try {
            val clientId = project.service<ServerTransmitter>().clientId
            val body = """{"clientId":${q(clientId)},"text":${q(cleaned)}}"""
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(ControlexConfig.SERVER_URL + "/api/chat"))
                .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .timeout(java.time.Duration.ofSeconds(10))
                .build()
            val resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
            resp.statusCode() in 200..299
        } catch (e: Exception) {
            log.warn("Controlex: chat send error: ${e.message}")
            false
        }
    }

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val txt = file.readText()
            // Parser naive de un array de objetos {id, who, text, at}.
            val objRegex = Regex("""\{[^{}]*\}""")
            for (m in objRegex.findAll(txt)) {
                val obj = m.value
                val id   = strField("id",   obj) ?: continue
                val who  = strField("who",  obj) ?: continue
                val text = strField("text", obj) ?: continue
                val at   = numField("at",   obj) ?: continue
                synchronized(messages) { messages.add(Message(id, who, text, at)) }
            }
        } catch (e: Exception) {
            log.warn("Controlex: chat.json load: ${e.message}")
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val snap = synchronized(messages) { messages.toList() }
            val body = snap.joinToString(",") {
                """{"id":${q(it.id)},"who":${q(it.who)},"text":${q(it.text)},"at":${it.at}}"""
            }
            file.writeText("[$body]")
        } catch (e: Exception) {
            log.warn("Controlex: chat.json persist: ${e.message}")
        }
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\r", "\r")?.replace("\\t", "\t")
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    override fun dispose() { listeners.clear() }

    companion object {
        /** Máximo de mensajes guardados localmente — si se supera, se purgan los más antiguos. */
        private const val MAX = 1000
    }
}
