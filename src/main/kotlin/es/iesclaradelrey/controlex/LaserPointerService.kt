package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.JWindow

/**
 * Puntero láser que el profesor controla desde el panel: una pequeña ventana
 * sin decoración, transparente, always-on-top, con un punto rojo. Se mueve
 * por la pantalla del alumno siguiendo las coordenadas fraccionales (0..1)
 * que el panel envía vía el comando `laser-pointer`.
 *
 * Tamaño 40×40 px → si se solapa con la zona de interacción del alumno solo
 * intercepta clicks dentro de ese cuadro pequeño. Mover la ventana es muy
 * barato (no se redibuja la pantalla del alumno).
 */
@Service(Service.Level.PROJECT)
class LaserPointerService(@Suppress("UNUSED_PARAMETER") project: Project) : Disposable {

    private val log = Logger.getInstance(LaserPointerService::class.java)

    @Volatile private var window: JWindow? = null

    fun showAt(fracX: Double, fracY: Double) {
        ApplicationManager.getApplication().invokeLater {
            val (sx, sy) = fracToScreen(fracX, fracY)
            ensureWindow().apply {
                setLocation(sx - SIZE / 2, sy - SIZE / 2)
                if (!isVisible) isVisible = true
                toFront()
            }
        }
    }

    fun hide() {
        ApplicationManager.getApplication().invokeLater {
            window?.isVisible = false
        }
    }

    /** Convierte fraccional 0..1 (sobre la unión de todas las pantallas) a px de escritorio. */
    private fun fracToScreen(fx: Double, fy: Double): Pair<Int, Int> {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        var union = Rectangle()
        for (dev in ge.screenDevices) {
            union = union.union(dev.defaultConfiguration.bounds)
        }
        if (union.isEmpty) union = ge.defaultScreenDevice.defaultConfiguration.bounds
        val x = union.x + (fx.coerceIn(0.0, 1.0) * union.width).toInt()
        val y = union.y + (fy.coerceIn(0.0, 1.0) * union.height).toInt()
        return x to y
    }

    private fun ensureWindow(): JWindow {
        window?.let { return it }
        val w = JWindow()
        try {
            w.background = Color(0, 0, 0, 0)  // transparente
            w.isAlwaysOnTop = true
            w.size = Dimension(SIZE, SIZE)
            w.contentPane = LaserDot()
            w.focusableWindowState = false
        } catch (e: Exception) {
            log.warn("Controlex: no se pudo crear puntero láser: ${e.message}")
        }
        window = w
        return w
    }

    override fun dispose() {
        ApplicationManager.getApplication().invokeLater {
            window?.dispose()
            window = null
        }
    }

    private class LaserDot : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(SIZE, SIZE)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Halo difuso
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f)
            g2.color = Color(255, 60, 60)
            g2.fillOval(2, 2, SIZE - 4, SIZE - 4)
            // Punto sólido
            g2.composite = AlphaComposite.SrcOver
            g2.color = Color(220, 0, 0)
            g2.fillOval(SIZE / 2 - 6, SIZE / 2 - 6, 12, 12)
            // Ribete blanco para contraste sobre fondos oscuros
            g2.color = Color(255, 255, 255, 230)
            g2.stroke = java.awt.BasicStroke(1.4f)
            g2.drawOval(SIZE / 2 - 7, SIZE / 2 - 7, 14, 14)
        }
    }

    companion object { private const val SIZE = 40 }
}
