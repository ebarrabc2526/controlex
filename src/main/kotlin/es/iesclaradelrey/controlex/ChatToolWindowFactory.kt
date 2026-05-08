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
            // Respeta el tema de IntelliJ — JBColor.background() devuelve
            // claro/oscuro según el LAF activo.
            background = JBColor.background()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        val scroll = JBScrollPane(pane).apply {
            verticalScrollBar.unitIncrement = 16
            viewport.background = JBColor.background()
            background = JBColor.background()
        }

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
            // HTMLEditorKit de Swing soporta sólo HTML 3.2 / 4.0 simple.
            // Las propiedades CSS modernas (display:inline-block, max-width
            // en %, padding-left:30%) se ignoran y dejaban el render con
            // burbujas blancas o invisibles. Usamos <table> + bgcolor +
            // <font color> que sí se respetan en Swing.
            // Sin bgcolor en el body para que se respete el background del
            // JEditorPane (que viene de JBColor.background() — tema dinámico).
            val sb = StringBuilder("<html><body style=\"font-family:sans-serif;font-size:11pt;margin:0\">")
            sb.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"3\" border=\"0\">")
            if (msgs.isEmpty()) {
                sb.append("""<tr><td align="center"><font color="#888888">Sin mensajes todavía. Aquí aparecerán los mensajes del profesor.</font></td></tr>""")
            } else {
                val tf = SimpleDateFormat("HH:mm:ss")
                for (m in msgs) {
                    val time = tf.format(Date(m.at))
                    val text = htmlEscape(m.text).replace("\n", "<br>")
                    if (m.who == "teacher") {
                        sb.append("""<tr><td align="left">
                            |<table cellspacing="0" cellpadding="6" border="0" bgcolor="#E3F2FD"><tr><td>
                            |<font color="#0D47A1"><b>Profesor</b></font><br>
                            |<font color="#0D47A1">$text</font>
                            |<br><font color="#888888" size="2">$time</font>
                            |</td></tr></table></td><td width="20%">&nbsp;</td></tr>""".trimMargin())
                    } else {
                        sb.append("""<tr><td width="20%">&nbsp;</td><td align="right">
                            |<table cellspacing="0" cellpadding="6" border="0" bgcolor="#1565C0"><tr><td>
                            |<font color="#FFFFFF"><b>Yo</b></font><br>
                            |<font color="#FFFFFF">$text</font>
                            |<br><font color="#CFD8DC" size="2">$time</font>
                            |</td></tr></table></td></tr>""".trimMargin())
                    }
                }
            }
            sb.append("</table></body></html>")
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
            // Añadir local sincrónicamente en EDT — así la burbuja del alumno
            // aparece YA en su tool window. El POST HTTP va en pool detrás.
            chat.add("student", text)
            ApplicationManager.getApplication().executeOnPooledThread {
                chat.sendNetworkOnly(text)
            }
        }
        sendBtn.addActionListener { doSend() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isControlDown || e.isMetaDown) {
                        // Ctrl/⌘+Enter → salto de línea (insertarlo manualmente
                        // porque vamos a consumir el evento para no enviar).
                        val pos = input.caretPosition
                        input.document.insertString(pos, "\n", null)
                        e.consume()
                    } else if (!e.isShiftDown) {
                        // Enter solo → enviar.
                        e.consume(); doSend()
                    }
                    // Shift+Enter cae a default de JTextArea → newline. OK.
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
