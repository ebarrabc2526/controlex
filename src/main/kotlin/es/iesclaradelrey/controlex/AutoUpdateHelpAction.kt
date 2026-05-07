package es.iesclaradelrey.controlex

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Shows a dialog explaining how to wire IntelliJ to the Controlex custom
 * plugin repository so the plugin auto-updates on subsequent releases.
 *
 * Lives under Tools → Controlex → "Auto-actualización (ayuda)".
 */
class AutoUpdateHelpAction : AnAction() {

    companion object {
        private const val REPO_URL = "https://controlex.ebarrab.com/updatePlugins.xml"
        private const val GITHUB_URL = "https://github.com/ebarrabc2526/controlex#auto-actualización-recomendado"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val msg = """
            Para que IntelliJ avise (y descargue) las nuevas versiones de Controlex
            automáticamente, hay que añadir el repositorio personalizado del plugin
            una sola vez por IDE:

            1. Settings → Plugins
            2. ⚙ (engranaje arriba a la derecha) → Manage Plugin Repositories…
            3. + → pega esta URL:

               $REPO_URL

            4. OK → Apply

            A partir de ahí, IntelliJ comprueba al arrancar y al pulsar "Check for
            updates". Las nuevas versiones aparecen en la pestaña Updates de Plugins.
        """.trimIndent()

        val choice = Messages.showDialog(
            e.project,
            msg,
            "Controlex — Auto-actualización",
            arrayOf("Copiar URL", "Abrir GitHub", "Cerrar"),
            2,
            Messages.getInformationIcon()
        )
        when (choice) {
            0 -> {
                val sel = StringSelection(REPO_URL)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                Messages.showInfoMessage(
                    e.project,
                    "URL copiada al portapapeles:\n$REPO_URL",
                    "Controlex"
                )
            }
            1 -> BrowserUtil.browse(GITHUB_URL)
            else -> { /* close */ }
        }
    }
}
