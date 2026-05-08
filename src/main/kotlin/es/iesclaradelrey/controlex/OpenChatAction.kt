package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Abre la tool window "Controlex Chat". Disponible desde:
 * - Tools → Controlex → Abrir chat
 * - Popup del status bar widget de Controlex (los items del MainGroup
 *   se renderizan ahí automáticamente).
 */
class OpenChatAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Controlex Chat") ?: return
        tw.show(null)
        tw.activate(null, true)
    }
}
