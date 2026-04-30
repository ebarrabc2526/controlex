package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
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
            val type = strField("type", jsonPayload) ?: return
            ControlexLog.appendCommand(project, type, jsonPayload)
            when (type) {
                "show-dialog" -> {
                    val text  = strField("text",  jsonPayload) ?: return
                    val title = strField("title", jsonPayload) ?: "Mensaje del profesor"
                    Messages.showInfoMessage(project, text, title)
                }
                "open-file" -> {
                    val rel  = strField("path", jsonPayload) ?: return
                    val line = numField("line", jsonPayload)?.toInt()
                    openFile(project, rel, line)
                }
                "goto-line" -> {
                    val line = numField("line", jsonPayload)?.toInt() ?: return
                    val rel  = strField("path", jsonPayload)
                    if (rel != null) openFile(project, rel, line)
                    else gotoLineInActiveEditor(project, line)
                }
                "run-action" -> {
                    val id = strField("actionId", jsonPayload) ?: return
                    if (id !in ALLOWED_ACTIONS) { log.warn("Controlex: acción no permitida: $id"); return }
                    runAction(project, id)
                }
                else -> log.warn("Controlex: tipo de comando desconocido: $type")
            }
        } catch (e: Exception) {
            log.warn("Controlex: error ejecutando comando remoto", e)
        }
    }

    private fun openFile(project: Project, relativePath: String, line: Int?) {
        val base = project.basePath ?: return
        val abs = File(base, relativePath)
        val vf = LocalFileSystem.getInstance().findFileByIoFile(abs) ?: return
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

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
