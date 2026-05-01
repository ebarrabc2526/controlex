package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Color
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class PairSessionManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PairSessionManager::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-Pair").apply { isDaemon = true }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false

    private val sessions = mutableMapOf<String, PairSession>()  // path -> session

    private data class PairSession(
        val path: String,
        val editor: Editor,
        var version: Int,
        var docListener: DocumentListener?,
        var caretListener: CaretListener?,
        var teacherCaret: RangeHighlighter?,
        val applyingRemote: AtomicBoolean
    )

    fun start() { connect() }

    private fun connect() {
        if (stopped) return
        val clientId = project.service<ServerTransmitter>().clientId
        val uri = URI.create(
            ControlexConfig.SERVER_WS_URL + "/api/client/pair?clientId=" +
            URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        )
        http.newWebSocketBuilder()
            .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
            .buildAsync(uri, WsListener())
            .exceptionally { e ->
                log.warn("Controlex: pair WS error: ${e.message}")
                if (!stopped) scheduler.schedule({ connect() }, 5L, TimeUnit.SECONDS)
                null
            }
    }

    fun openSession(relativePath: String) {
        ApplicationManager.getApplication().invokeLater {
            val base = project.basePath ?: return@invokeLater
            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(base, relativePath)) ?: return@invokeLater
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, vf), true) ?: return@invokeLater

            val applying = AtomicBoolean(false)
            val session = PairSession(relativePath, editor, 0, null, null, null, applying)
            sessions[relativePath] = session

            // Rechazar ficheros demasiado grandes
            val content = editor.document.text
            if (content.length > 200_000) {
                log.warn("Controlex: pair-open rechazado para '$relativePath': fichero demasiado grande (${content.length} bytes)")
                sessions.remove(relativePath)
                return@invokeLater
            }

            // Envío estado inicial al servidor
            sendJson("""{"type":"doc-state","path":${q(relativePath)},"version":0,"content":${q(content)}}""")

            // Listener de cambios locales (alumno)
            val docListener = object : DocumentListener {
                override fun documentChanged(e: DocumentEvent) {
                    if (applying.get()) return
                    val ops = mutableListOf<String>()
                    if (e.oldLength > 0) ops += """{"opType":"delete","offset":${e.offset},"len":${e.oldLength}}"""
                    if (e.newLength > 0) ops += """{"opType":"insert","offset":${e.offset},"text":${q(e.newFragment.toString())}}"""
                    sendJson("""{"type":"edit","path":${q(relativePath)},"baseVersion":${session.version},"ops":[${ops.joinToString(",")}]}""")
                }
            }
            editor.document.addDocumentListener(docListener)
            session.docListener = docListener

            // Listener de caret local
            val caretListener = object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val offset = editor.caretModel.offset
                    sendJson("""{"type":"cursor","path":${q(relativePath)},"offset":$offset}""")
                }
            }
            editor.caretModel.addCaretListener(caretListener)
            session.caretListener = caretListener
        }
    }

    fun closeSession(relativePath: String) {
        ApplicationManager.getApplication().invokeLater {
            val s = sessions.remove(relativePath) ?: return@invokeLater
            s.docListener?.let { s.editor.document.removeDocumentListener(it) }
            s.caretListener?.let { s.editor.caretModel.removeCaretListener(it) }
            s.teacherCaret?.let { s.editor.markupModel.removeHighlighter(it) }
        }
    }

    private fun handleIncoming(json: String) {
        val type = field("type", json) ?: return
        val path = field("path", json) ?: return
        val s = sessions[path] ?: return

        when (type) {
            "edit" -> ApplicationManager.getApplication().invokeLater {
                val opsBlock = Regex(""""ops"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .find(json)?.groupValues?.get(1) ?: return@invokeLater
                val opRegex = Regex("""\{[^}]*\}""")
                s.applyingRemote.set(true)
                try {
                    WriteCommandAction.runWriteCommandAction(project) {
                        for (m in opRegex.findAll(opsBlock)) {
                            val op = m.value
                            val opType = field("opType", op) ?: continue
                            val offset = numField("offset", op)?.toInt() ?: continue
                            when (opType) {
                                "insert" -> {
                                    val text = field("text", op) ?: continue
                                    s.editor.document.insertString(offset, text)
                                }
                                "delete" -> {
                                    val len = numField("len", op)?.toInt() ?: continue
                                    s.editor.document.deleteString(offset, offset + len)
                                }
                            }
                        }
                    }
                    s.version = numField("version", json)?.toInt() ?: s.version
                } finally {
                    s.applyingRemote.set(false)
                }
            }
            "cursor" -> ApplicationManager.getApplication().invokeLater {
                val offset = numField("offset", json)?.toInt() ?: return@invokeLater
                drawTeacherCaret(s, offset)
            }
        }
    }

    private fun drawTeacherCaret(s: PairSession, offset: Int) {
        s.teacherCaret?.let { s.editor.markupModel.removeHighlighter(it) }
        val safe = offset.coerceIn(0, s.editor.document.textLength)
        val attrs = TextAttributes()
        attrs.effectColor = Color(0xFF, 0x57, 0x22)  // naranja
        val rh = s.editor.markupModel.addRangeHighlighter(
            safe, (safe + 1).coerceAtMost(s.editor.document.textLength),
            HighlighterLayer.LAST + 1, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        s.teacherCaret = rh
    }

    private fun sendJson(s: String) {
        val w = ws ?: return
        if (w.isOutputClosed) return
        try { w.sendText(s, true) } catch (_: Throwable) {}
    }

    private fun field(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private inner class WsListener : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket
            webSocket.request(Long.MAX_VALUE)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            buf.append(data)
            if (last) {
                val msg = buf.toString(); buf.setLength(0)
                handleIncoming(msg)
            }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            ws = null
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn("Controlex: pair WS error: ${error.message}")
            ws = null
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
        }
    }

    override fun dispose() {
        stopped = true
        for ((p, _) in sessions.toMap()) closeSession(p)
        try { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "") } catch (_: Throwable) {}
        scheduler.shutdownNow()
    }
}
