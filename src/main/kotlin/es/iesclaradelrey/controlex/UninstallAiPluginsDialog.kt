package es.iesclaradelrey.controlex

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class UninstallAiPluginsDialog(
    project: Project,
    private val detected: List<AiPluginDetector.Detection>
) : DialogWrapper(project) {

    private val checkBoxes: List<JBCheckBox> =
        detected.map { JBCheckBox("${it.name}  [${it.id}]", true) }

    init {
        title = "Controlex — Examen bloqueado"
        setOKButtonText("Deshabilitar seleccionados y reiniciar")
        setCancelButtonText("Cerrar IntelliJ")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))

        val msg = """
            <html>
            Se han detectado <b>${detected.size}</b> plugin(s) de IA activo(s).<br>
            Debes desinstalarlos para poder realizar el examen.<br><br>
            Selecciona los plugins a desinstalar:
            </html>
        """.trimIndent()
        panel.add(JBLabel(msg), BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        checkBoxes.forEach { listPanel.add(it) }

        val scroll = JBScrollPane(listPanel)
        scroll.preferredSize = Dimension(520, minOf(180, checkBoxes.size * 32 + 16))
        panel.add(scroll, BorderLayout.CENTER)

        panel.preferredSize = Dimension(560, 300)
        return panel
    }

    override fun doOKAction() {
        val selected = selectedDetections()
        if (selected.isEmpty()) {
            Messages.showWarningDialog(
                "Debes seleccionar al menos un plugin para desinstalar.",
                "Sin selección"
            )
            return
        }
        for (detection in selected) {
            uninstall(detection)
        }
        super.doOKAction()
        ApplicationManagerEx.getApplicationEx().restart(true)
    }

    override fun doCancelAction() {
        super.doCancelAction()
        ApplicationManager.getApplication().exit(true, true, false)
    }

    private fun selectedDetections(): List<AiPluginDetector.Detection> =
        checkBoxes.zip(detected).filter { (cb, _) -> cb.isSelected }.map { (_, d) -> d }

    private fun uninstall(detection: AiPluginDetector.Detection) {
        PluginManagerCore.disablePlugin(PluginId.getId(detection.id))
    }
}
