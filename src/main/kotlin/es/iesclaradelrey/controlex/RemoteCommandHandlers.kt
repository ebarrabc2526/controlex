package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import java.io.File

object RemoteCommandHandlers {

    private val log = Logger.getInstance(RemoteCommandHandlers::class.java)

    private val ALLOWED_ACTIONS = setOf(
        "SaveAll", "Synchronize", "CompileDirty",
        "Run", "Stop", "Debug", "Rerun",
        "EditorCopy", "EditorPaste", "EditorSelectAll",
        "ToggleLineBreakpoint",
        "Find", "Replace",
        "ReformatCode"
    )

    fun execute(project: Project, jsonPayload: String) {
        try {
            // Verify Ed25519 signature — reject the command if invalid
            val verified = CommandSignatureVerifier.verify(jsonPayload)
            if (verified == null) {
                log.warn("Controlex: comando rechazado (firma inválida o ausente)")
                return
            }

            val type = strField("type", verified) ?: return
            ControlexLog.appendCommand(project, type, verified)

            when (type) {
                "show-dialog" -> {
                    val text  = strField("text",  verified) ?: return
                    val title = strField("title", verified) ?: "Mensaje del profesor"
                    Messages.showInfoMessage(project, text, title)
                }
                "open-file" -> {
                    val rel  = strField("path", verified) ?: return
                    val line = numField("line", verified)?.toInt()
                    openFile(project, rel, line)
                }
                "goto-line" -> {
                    val line = numField("line", verified)?.toInt() ?: return
                    val rel  = strField("path", verified)
                    if (rel != null) openFile(project, rel, line)
                    else gotoLineInActiveEditor(project, line)
                }
                "run-action" -> {
                    val id = strField("actionId", verified) ?: return
                    if (id !in ALLOWED_ACTIONS) { log.warn("Controlex: acción no permitida: $id"); return }
                    runAction(project, id)
                }
                "insert-text" -> {
                    val text = strField("text", verified) ?: return
                    val rel  = strField("path", verified)
                    val line = numField("line", verified)?.toInt()
                    insertText(project, text, rel, line)
                }
                "highlight-line" -> {
                    val line    = numField("line",    verified)?.toInt() ?: return
                    val rel     = strField("path",    verified)
                    val color   = strField("color",   verified) ?: "yellow"
                    val tooltip = strField("tooltip", verified)
                    highlightLine(project, line, rel, color, tooltip)
                }
                "show-inlay" -> {
                    val line = numField("line", verified)?.toInt() ?: return
                    val text = strField("text", verified) ?: return
                    val rel  = strField("path", verified)
                    showInlay(project, line, text, rel)
                }
                "clear-highlights" -> {
                    project.service<CommandHighlightManager>().clearAll()
                }
                else -> log.warn("Controlex: tipo de comando desconocido: $type")
            }
        } catch (e: Exception) {
            log.warn("Controlex: error ejecutando comando remoto", e)
        }
    }

    private fun openFile(project: Project, relativePath: String, line: Int?) {
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByIoFile(File(base, relativePath)) ?: return
        val descriptor = if (line != null) OpenFileDescriptor(project, vf, line - 1, 0)
                         else OpenFileDescriptor(project, vf)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun gotoLineInActiveEditor(project: Project, line: Int) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line - 1, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun runAction(project: Project, actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val dc = DataContext { dataId ->
            if (CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }
        val event = AnActionEvent.createFromDataContext("ControlexRemote", null, dc)
        action.actionPerformed(event)
    }

    private fun insertText(project: Project, text: String, relativePath: String?, line: Int?) {
        val editor = if (relativePath != null) {
            val base = project.basePath ?: return
            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(base, relativePath)) ?: return
            val desc = if (line != null) OpenFileDescriptor(project, vf, line - 1, 0)
                       else OpenFileDescriptor(project, vf)
            FileEditorManager.getInstance(project).openTextEditor(desc, true) ?: return
        } else {
            FileEditorManager.getInstance(project).selectedTextEditor ?: return
        }
        val offset = if (line != null && relativePath == null) {
            val doc = editor.document
            if (line >= 1 && line <= doc.lineCount) doc.getLineStartOffset(line - 1)
            else editor.caretModel.offset
        } else {
            editor.caretModel.offset
        }
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(offset, text)
        }
    }

    private fun highlightLine(project: Project, line: Int, relativePath: String?, color: String, tooltip: String?) {
        val editor = resolveEditor(project, relativePath, line) ?: return
        project.service<CommandHighlightManager>().highlightLine(editor, line, color, tooltip)
    }

    private fun showInlay(project: Project, line: Int, text: String, relativePath: String?) {
        val editor = resolveEditor(project, relativePath, line) ?: return
        project.service<CommandHighlightManager>().showInlay(editor, line, text)
    }

    private fun resolveEditor(project: Project, relativePath: String?, line: Int?): com.intellij.openapi.editor.Editor? {
        if (relativePath != null) {
            val base = project.basePath ?: return null
            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(base, relativePath)) ?: return null
            val desc = if (line != null) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)
            return FileEditorManager.getInstance(project).openTextEditor(desc, true)
        }
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
