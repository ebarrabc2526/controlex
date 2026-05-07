package es.iesclaradelrey.controlex

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

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
        return "$dot Controlex v$PLUGIN_VERSION"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = when (currentStatus()) {
        ServerTransmitter.Status.CONNECTED    -> "Controlex v$PLUGIN_VERSION — conectado al servidor"
        ServerTransmitter.Status.CONNECTING   -> "Controlex v$PLUGIN_VERSION — conectando al servidor..."
        ServerTransmitter.Status.DISCONNECTED -> "Controlex v$PLUGIN_VERSION — sin conexión con el servidor"
        else                                  -> "Controlex v$PLUGIN_VERSION — sin acceso a internet"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    private fun currentStatus(): ServerTransmitter.Status? =
        try { project.getService(ServerTransmitter::class.java)?.status }
        catch (e: Exception) { null }
}
