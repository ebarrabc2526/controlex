package es.iesclaradelrey.controlex

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

class ControlexStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ControlexStatusWidget.ID
    override fun getDisplayName(): String = "Controlex"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ControlexStatusWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ControlexStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {

    companion object {
        const val ID = "ControlexStatus"
        private val ICON_GREEN  = dotIcon(Color(0x00AA00))
        private val ICON_ORANGE = dotIcon(Color(255, 140,   0))
        private val ICON_RED    = dotIcon(Color(204,  34,   0))
        private val ICON_GRAY   = dotIcon(Color(0x888888))

        private fun dotIcon(color: Color): Icon = object : Icon {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = color
                    g2.fillOval(x + 2, y + 2, 10, 10)
                } finally {
                    g2.dispose()
                }
            }
            override fun getIconWidth() = 14
            override fun getIconHeight() = 14
        }
    }

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}

    override fun getIcon(): Icon = when (currentStatus()) {
        ServerTransmitter.Status.CONNECTED    -> ICON_GREEN
        ServerTransmitter.Status.CONNECTING   -> ICON_ORANGE
        ServerTransmitter.Status.DISCONNECTED -> ICON_RED
        else                                  -> ICON_GRAY
    }

    override fun getTooltipText(): String = when (currentStatus()) {
        ServerTransmitter.Status.CONNECTED    -> "Controlex: conectado al servidor"
        ServerTransmitter.Status.CONNECTING   -> "Controlex: conectando al servidor..."
        ServerTransmitter.Status.DISCONNECTED -> "Controlex: sin conexión con el servidor"
        else                                  -> "Controlex: sin acceso a internet"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    private fun currentStatus(): ServerTransmitter.Status? =
        try { project.getService(ServerTransmitter::class.java)?.status }
        catch (e: Exception) { null }
}
