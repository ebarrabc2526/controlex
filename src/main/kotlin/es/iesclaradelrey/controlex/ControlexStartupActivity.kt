package es.iesclaradelrey.controlex

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ControlexStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // La política de IA la fija el profesor (comando `ai-policy`); por
        // defecto se permiten, así que ya NO se fuerza la desinstalación al
        // arrancar. Si el panel bloquea la IA, el comando dispara el diálogo.
        project.service<PluginAuditService>().start()
        project.service<ScreenshotService>().start()
        project.service<ServerTransmitter>().start()
        project.service<CommandStreamReceiver>().start()
        project.service<ScreenStreamService>().start()
        project.service<PairSessionManager>().start()
    }
}
