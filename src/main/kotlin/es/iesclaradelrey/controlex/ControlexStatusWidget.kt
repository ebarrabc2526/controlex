package es.iesclaradelrey.controlex

import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.io.File

class ControlexStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ControlexStatusWidget.ID
    override fun getDisplayName(): String = "Controlex"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ControlexStatusWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ControlexStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "ControlexStatus"
        // Resolved once at class load. Plugin descriptors are immutable at runtime.
        private val PLUGIN_VERSION: String =
            PluginManagerCore.getPlugin(PluginId.getId(ControlexConfig.SELF_PLUGIN_ID))?.version ?: "?"
    }

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}

    override fun getText(): String {
        val dot = when (currentStatus()) {
            ServerTransmitter.Status.CONNECTED    -> "🟢"
            ServerTransmitter.Status.CONNECTING   -> "🟠"
            ServerTransmitter.Status.DISCONNECTED -> "🔴"
            else                                  -> "⚫"
        }
        val name = readClientName()
        val suffix = if (name.isNotEmpty()) " · $name" else ""
        return "$dot Controlex v$PLUGIN_VERSION$suffix"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val state = when (currentStatus()) {
            ServerTransmitter.Status.CONNECTED    -> "conectado al servidor"
            ServerTransmitter.Status.CONNECTING   -> "conectando al servidor..."
            ServerTransmitter.Status.DISCONNECTED -> "sin conexión con el servidor"
            else                                  -> "sin acceso a internet"
        }
        val name = readClientName().ifEmpty { "(sin nombre — Tools → Controlex → Configurar nombre)" }
        return "Controlex v$PLUGIN_VERSION — $state\nIdentidad: $name"
    }

    /** Read the persisted client name written by ConfigureNameAction. */
    private fun readClientName(): String {
        val home = System.getProperty("user.home") ?: return ""
        val f = File(home, ".${ControlexConfig.DIR_NAME}/client-name.txt")
        return if (f.exists()) f.readText().trim() else ""
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { e ->
        val group = ActionManager.getInstance().getAction("Controlex.MainGroup") as? ActionGroup ?: return@Consumer
        val component = e.component ?: return@Consumer
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Controlex v$PLUGIN_VERSION",
            group,
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        // Show the popup above the widget so it doesn't overlap the status bar.
        val anchor = Point(0, -popup.content.preferredSize.height)
        popup.show(RelativePoint(component, anchor))
    }

    private fun currentStatus(): ServerTransmitter.Status? =
        try { project.getService(ServerTransmitter::class.java)?.status }
        catch (e: Exception) { null }
}
