package es.iesclaradelrey.controlex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ControlexStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val detected = AiPluginDetector.findInstalledAiPlugins()
        if (detected.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                UninstallAiPluginsDialog(project, detected).show()
            }
            return
        }
        project.service<PluginAuditService>().start()
        project.service<ScreenshotService>().start()
        project.service<ServerTransmitter>().start()
        project.service<CommandStreamReceiver>().start()
        project.service<ScreenStreamService>().start()
    }
}
