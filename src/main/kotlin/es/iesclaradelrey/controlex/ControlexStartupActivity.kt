package es.iesclaradelrey.controlex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages

class ControlexStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val detected = AiPluginDetector.findInstalledAiPlugins()
        if (detected.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                val list = detected.joinToString("\n") { "  - ${it.name} (${it.id})" }
                Messages.showErrorDialog(
                    project,
                    """
                        Controlex ha detectado plugins de IA instalados y activos.
                        No se puede realizar el examen hasta que sean desinstalados o desactivados.

                        Plugins detectados:
                        $list

                        IntelliJ IDEA se cerrara.
                    """.trimIndent(),
                    "Controlex - Examen bloqueado"
                )
                ApplicationManager.getApplication().exit(true, true, false)
            }
            return
        }
        project.service<PluginAuditService>().start()
        project.service<ScreenshotService>().start()
        project.service<ServerTransmitter>().start()
    }
}
