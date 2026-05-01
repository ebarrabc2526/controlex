package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

@Service(Service.Level.PROJECT)
class CommandHighlightManager(private val project: Project) : Disposable {

    private val highlighters = mutableListOf<Pair<MarkupModel, RangeHighlighter>>()
    private val inlays       = mutableListOf<Inlay<*>>()

    fun highlightLine(editor: Editor, line: Int, colorName: String, tooltip: String?) {
        val doc = editor.document
        if (line < 1 || line > doc.lineCount) return
        val start = doc.getLineStartOffset(line - 1)
        val end   = doc.getLineEndOffset(line - 1)
        val attrs = TextAttributes().apply { backgroundColor = colorFor(colorName) }
        val mm = editor.markupModel
        val h = mm.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE)
        if (tooltip != null) h.errorStripeTooltip = tooltip
        synchronized(this) { highlighters.add(mm to h) }
    }

    fun showInlay(editor: Editor, line: Int, text: String) {
        val doc = editor.document
        if (line < 1 || line > doc.lineCount) return
        val offset = doc.getLineStartOffset(line - 1)
        val inlay = editor.inlayModel.addBlockElement(offset, false, true, 0, InlayRenderer(text))
        if (inlay != null) synchronized(this) { inlays.add(inlay) }
    }

    fun clearAll() {
        ApplicationManager.getApplication().invokeLater {
            synchronized(this) {
                for ((model, h) in highlighters) {
                    try { model.removeHighlighter(h) } catch (_: Throwable) {}
                }
                highlighters.clear()
                for (i in inlays) {
                    try { i.dispose() } catch (_: Throwable) {}
                }
                inlays.clear()
            }
        }
    }

    override fun dispose() = clearAll()

    private fun colorFor(name: String): Color = when (name) {
        "red"   -> Color(255, 200, 200)
        "green" -> Color(200, 255, 200)
        "blue"  -> Color(200, 220, 255)
        else    -> Color(255, 255, 180)
    }

    private class InlayRenderer(private val text: String) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val fm = inlay.editor.contentComponent.getFontMetrics(
                inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
            )
            return fm.stringWidth("[Prof] $text") + 20
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                val h = targetRegion.height - 3
                g2.color = Color(255, 242, 160)
                g2.fillRoundRect(targetRegion.x, targetRegion.y, targetRegion.width - 2, h, 6, 6)
                g2.color = Color(180, 145, 0)
                g2.drawRoundRect(targetRegion.x, targetRegion.y, targetRegion.width - 2, h, 6, 6)
                val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
                g2.font = font.deriveFont(font.size2D * 0.88f)
                g2.color = Color(60, 40, 0)
                val fm = g2.fontMetrics
                val y = targetRegion.y + (h + fm.ascent - fm.descent) / 2
                g2.drawString("[Prof] $text", targetRegion.x + 8, y)
            } finally {
                g2.dispose()
            }
        }
    }
}
