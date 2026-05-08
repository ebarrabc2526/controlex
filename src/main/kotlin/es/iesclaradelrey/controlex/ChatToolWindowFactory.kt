package es.iesclaradelrey.controlex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Tool Window "Profesor" del alumno: chat con burbujas estilo iMessage,
 * campo de respuesta y atajo Ctrl+Enter para enviar. Suscrito a [ChatService]
 * para reflejar al instante los mensajes del profesor que llegan vía el
 * comando remoto chat-message.
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chat = project.service<ChatService>()

        val panel = JPanel(BorderLayout())
        panel.background = JBColor.background()

        val pane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        val scroll = JBScrollPane(pane).apply { verticalScrollBar.unitIncrement = 16 }

        val input = JTextArea(2, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
            )
        }
        val inputScroll = JBScrollPane(input).apply { preferredSize = Dimension(0, 64) }
        val sendBtn = JButton("Enviar").apply {
            toolTipText = "Enviar mensaje al profesor (Ctrl/⌘+Enter)"
        }
        val bottom = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(inputScroll, BorderLayout.CENTER)
            add(sendBtn, BorderLayout.EAST)
        }
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(bottom, BorderLayout.SOUTH)

        fun render() {
            val msgs = chat.all()
            val sb = StringBuilder("<html><body style='font-family:sans-serif;font-size:11pt;margin:0;padding:0'>")
            if (msgs.isEmpty()) {
                sb.append("<div style='color:#888;text-align:center;padding:20px'>Sin mensajes todavía. Aquí aparecerán los mensajes del profesor.</div>")
            } else {
                val tf = SimpleDateFormat("HH:mm:ss")
                for (m in msgs) {
                    val time = tf.format(Date(m.at))
                    val text = htmlEscape(m.text).replace("\n", "<br>")
                    if (m.who == "teacher") {
                        sb.append("""<div style='margin:4px 0;padding-right:30%;'>
                            |<div style='background:#eef4fb;border:1px solid #bbdefb;color:#0d47a1;padding:6px 10px;border-radius:8px;display:inline-block;max-width:100%;'>
                            |<b>Profesor</b><br>$text
                            |<div style='color:#888;font-size:8pt;margin-top:2px'>$time</div>
                            |</div></div>""".trimMargin())
                    } else {
                        sb.append("""<div style='margin:4px 0;padding-left:30%;text-align:right;'>
                            |<div style='background:#1565C0;color:#fff;padding:6px 10px;border-radius:8px;display:inline-block;max-width:100%;text-align:left;'>
                            |<b>Yo</b><br>$text
                            |<div style='color:#cfd8dc;font-size:8pt;margin-top:2px'>$time</div>
                            |</div></div>""".trimMargin())
                    }
                }
            }
            sb.append("</body></html>")
            pane.text = sb.toString()
            // Auto-scroll abajo después de que Swing pinte el HTML.
            SwingUtilities.invokeLater {
                pane.caretPosition = pane.document.length
                scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
            }
        }
        render()

        // Suscripción a nuevos mensajes — re-render al recibirlos. La UI de
        // Swing tiene que tocarse desde el EDT.
        val listener: (ChatService.Message) -> Unit = {
            ApplicationManager.getApplication().invokeLater { render() }
        }
        chat.addListener(listener)
        toolWindow.disposable.let {
            com.intellij.openapi.util.Disposer.register(it, com.intellij.openapi.Disposable {
                chat.removeListener(listener)
            })
        }

        fun doSend() {
            val text = input.text.trim()
            if (text.isEmpty()) return
            input.text = ""
            ApplicationManager.getApplication().executeOnPooledThread {
                chat.send(text)
            }
        }
        sendBtn.addActionListener { doSend() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if ((e.isControlDown || e.isMetaDown) && e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume(); doSend()
                }
            }
        })

        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
         .replace("\"", "&quot;").replace("'", "&#39;")
}
