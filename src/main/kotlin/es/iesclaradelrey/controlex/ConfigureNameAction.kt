package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import java.io.File

class ConfigureNameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val home = System.getProperty("user.home") ?: return
        val f = File(home, ".${ControlexConfig.DIR_NAME}/client-name.txt").also { it.parentFile.mkdirs() }
        val current = if (f.exists()) f.readText().trim() else ""
        val input = Messages.showInputDialog(
            e.project,
            "Formato sugerido: CATEGORIA#APODO  (ej. DM1D1A#01-BLAMA)",
            "Controlex — Configurar nombre",
            null,
            current,
            null
        ) ?: return
        f.writeText(input.trim())
        // Refresh the status bar widget so the new name shows immediately.
        e.project?.let { p ->
            WindowManager.getInstance().getStatusBar(p)?.updateWidget(ControlexStatusWidget.ID)
        }
        Messages.showInfoMessage(
            e.project,
            "Nombre guardado: \"${input.trim()}\".\nTu identidad en el panel se actualizará en el próximo envío.",
            "Controlex"
        )
    }
}
