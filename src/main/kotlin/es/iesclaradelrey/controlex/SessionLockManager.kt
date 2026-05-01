package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class SessionLockManager(private val project: Project) : Disposable {

    @Volatile private var lockPane: LockGlassPane? = null

    fun lock(message: String) {
        ApplicationManager.getApplication().invokeLater {
            val frame = WindowManager.getInstance().getFrame(project) as? JFrame ?: return@invokeLater
            val rootPane = frame.rootPane
            unlock()
            val pane = LockGlassPane(message)
            rootPane.glassPane = pane
            pane.isVisible = true
            pane.requestFocusInWindow()
            lockPane = pane
        }
    }

    fun unlock() {
        ApplicationManager.getApplication().invokeLater {
            val pane = lockPane ?: return@invokeLater
            pane.isVisible = false
            lockPane = null
        }
    }

    override fun dispose() = unlock()

    private class LockGlassPane(private val message: String) : JPanel() {

        init {
            isOpaque = false
            isFocusable = true
            cursor = Cursor(Cursor.WAIT_CURSOR)
            val blocker = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent)   = e.consume()
                override fun mousePressed(e: MouseEvent)   = e.consume()
                override fun mouseReleased(e: MouseEvent)  = e.consume()
                override fun mouseEntered(e: MouseEvent)   = e.consume()
                override fun mouseExited(e: MouseEvent)    = e.consume()
                override fun mouseMoved(e: MouseEvent)     = e.consume()
                override fun mouseDragged(e: MouseEvent)   = e.consume()
            }
            addMouseListener(blocker)
            addMouseMotionListener(blocker)
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent)  = e.consume()
                override fun keyReleased(e: KeyEvent) = e.consume()
                override fun keyTyped(e: KeyEvent)    = e.consume()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(10, 10, 25, 242)
                g2.fillRect(0, 0, width, height)

                val cx = width / 2

                g2.color = Color(220, 50, 50)
                g2.font = Font("SansSerif", Font.BOLD, 34)
                val title = "SESION BLOQUEADA"
                g2.drawString(title, cx - g2.fontMetrics.stringWidth(title) / 2, height / 2 - 50)

                g2.color = Color(230, 230, 230)
                g2.font = Font("SansSerif", Font.PLAIN, 20)
                val lines = message.split("\n")
                var y = height / 2 + 5
                for (line in lines) {
                    g2.drawString(line, cx - g2.fontMetrics.stringWidth(line) / 2, y)
                    y += g2.fontMetrics.height + 4
                }

                g2.color = Color(100, 100, 120)
                g2.font = Font("SansSerif", Font.PLAIN, 13)
                val footer = "Controlex — IES Clara del Rey"
                g2.drawString(footer, cx - g2.fontMetrics.stringWidth(footer) / 2, height - 24)
            } finally {
                g2.dispose()
            }
        }
    }
}
