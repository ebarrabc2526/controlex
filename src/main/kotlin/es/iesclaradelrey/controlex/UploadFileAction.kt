package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Permite al alumno subir un fichero al profesor. Abre un FileChooser, lee
 * los bytes, los manda al servidor en base64 dentro de JSON. Tope efectivo
 * ~75 MB (el express.json del servidor está a 100 MB).
 *
 * También loguea la acción en el chat local para que la burbuja
 * "📎 Enviado: nombre.ext" aparezca en la tool window al instante.
 */
class UploadFileAction : AnAction() {

    private val log = Logger.getInstance(UploadFileAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        sendFile(project)
    }

    fun sendFile(project: Project) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Enviar fichero al profesor")
        val vfile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(vfile.path)
        if (!file.isFile) return

        val bytes = file.readBytes()
        val maxSize = 75 * 1024 * 1024
        if (bytes.size > maxSize) {
            Messages.showErrorDialog(project,
                "El fichero es demasiado grande (${bytes.size / 1024 / 1024} MB). Máximo: 75 MB.",
                "Controlex — fichero demasiado grande")
            return
        }

        val chat = project.service<ChatService>()
        chat.add("student", "📎 Enviando: ${file.name}…")

        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = upload(project, file.name, bytes)
            ApplicationManager.getApplication().invokeLater {
                if (ok) chat.add("student", "✅ Enviado al profesor: ${file.name} (${bytes.size / 1024} KB)")
                else    chat.add("student", "❌ Error enviando ${file.name}. Reintenta.")
            }
        }
    }

    private fun upload(project: Project, filename: String, bytes: ByteArray): Boolean {
        return try {
            val clientId = project.service<ServerTransmitter>().clientId
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val body = """{"clientId":${q(clientId)},"filename":${q(filename)},"contentB64":${q(b64)}}"""
            val req = HttpRequest.newBuilder()
                .uri(URI.create(ControlexConfig.SERVER_URL + "/api/upload-file"))
                .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .timeout(Duration.ofMinutes(5))
                .build()
            val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding())
            resp.statusCode() in 200..299
        } catch (e: Exception) {
            log.warn("Controlex: upload-file error: ${e.message}")
            false
        }
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
