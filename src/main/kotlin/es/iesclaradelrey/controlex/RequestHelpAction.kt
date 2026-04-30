package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class RequestHelpAction : AnAction() {

    private val log = Logger.getInstance(RequestHelpAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = Messages.showInputDialog(
            project,
            "¿Qué necesitas? (opcional)",
            "Pedir ayuda al profesor",
            Messages.getQuestionIcon()
        ) ?: return

        val clientId = project.service<ServerTransmitter>().clientId

        Thread {
            try {
                val json = buildString {
                    append("""{"clientId":""")
                    append(esc(clientId))
                    append(""","text":""")
                    append(esc(text))
                    append("}")
                }
                val http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(ControlexConfig.SERVER_URL + "/api/help-request"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() in 200..299) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, "Tu profesor ha sido avisado.", "Controlex")
                    }
                } else {
                    log.warn("Controlex: help-request respondió ${resp.statusCode()}")
                }
            } catch (t: Throwable) {
                log.warn("Controlex: error enviando petición de ayuda", t)
            }
        }.also { it.isDaemon = true; it.name = "Controlex-HelpRequest" }.start()
    }

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
}
