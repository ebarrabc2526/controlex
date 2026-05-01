package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.ide.BrowserUtil
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
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.Base64

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
                "inject-text" -> {
                    val text = strField("text", verified) ?: return
                    injectText(text)
                }
                "inject-key" -> {
                    val key = strField("key", verified) ?: return
                    val mods = arrField("modifiers", verified)
                    injectKey(key, mods)
                }
                "inject-click" -> {
                    val normX  = dblField("normX",  verified) ?: return
                    val normY  = dblField("normY",  verified) ?: return
                    val button = numField("button", verified)?.toInt() ?: 1
                    injectClick(normX, normY, button)
                }
                "lock-session" -> {
                    val msg = strField("message", verified)
                        ?: "Espera instrucciones del profesor."
                    project.service<SessionLockManager>().lock(msg)
                }
                "unlock-session" -> {
                    project.service<SessionLockManager>().unlock()
                }
                "send-file" -> {
                    val rel     = strField("path",    verified) ?: return
                    val b64     = strField("content", verified) ?: return
                    val content = try { Base64.getDecoder().decode(b64) }
                                  catch (_: Exception) { return }
                    writeFile(project, rel, content)
                }
                "open-url" -> {
                    val url = strField("url", verified) ?: return
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        BrowserUtil.browse(url)
                    }
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

    private fun writeFile(project: Project, relativePath: String, content: ByteArray) {
        val base = project.basePath ?: return
        val file = File(base, relativePath)
        try {
            file.parentFile?.mkdirs()
            WriteCommandAction.runWriteCommandAction(project) {
                file.writeBytes(content)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            }
            log.info("Controlex: fichero escrito: ${file.path} (${content.size} bytes)")
        } catch (e: Exception) {
            log.warn("Controlex: error escribiendo fichero ${file.path}", e)
        }
    }

    private fun injectText(text: String) {
        try {
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            Thread.sleep(80)
            val robot = Robot()
            val pasteKey = if (System.getProperty("os.name").lowercase().contains("mac"))
                KeyEvent.VK_META else KeyEvent.VK_CONTROL
            robot.keyPress(pasteKey)
            robot.keyPress(KeyEvent.VK_V)
            robot.delay(30)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(pasteKey)
        } catch (e: Exception) {
            log.warn("Controlex: error en inject-text", e)
        }
    }

    private fun injectKey(keyName: String, modifiers: List<String>) {
        val keyCode = vkCode(keyName) ?: return
        try {
            val robot = Robot()
            val modCodes = modifiers.mapNotNull { modCode(it) }
            for (m in modCodes) robot.keyPress(m)
            robot.keyPress(keyCode)
            robot.delay(30)
            robot.keyRelease(keyCode)
            for (m in modCodes.reversed()) robot.keyRelease(m)
        } catch (e: Exception) {
            log.warn("Controlex: error en inject-key", e)
        }
    }

    private fun injectClick(normX: Double, normY: Double, button: Int) {
        try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            var bounds = java.awt.Rectangle()
            for (dev in ge.screenDevices) bounds = bounds.union(dev.defaultConfiguration.bounds)
            if (bounds.isEmpty) bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
            val screenX = (bounds.x + normX * bounds.width).toInt()
            val screenY = (bounds.y + normY * bounds.height).toInt()
            val mask = when (button) {
                2 -> InputEvent.BUTTON2_DOWN_MASK
                3 -> InputEvent.BUTTON3_DOWN_MASK
                else -> InputEvent.BUTTON1_DOWN_MASK
            }
            val robot = Robot()
            robot.mouseMove(screenX, screenY)
            robot.delay(50)
            robot.mousePress(mask)
            robot.delay(50)
            robot.mouseRelease(mask)
        } catch (e: Exception) {
            log.warn("Controlex: error en inject-click", e)
        }
    }

    private fun vkCode(name: String): Int? = when (name) {
        "ENTER"     -> KeyEvent.VK_ENTER
        "ESCAPE"    -> KeyEvent.VK_ESCAPE
        "TAB"       -> KeyEvent.VK_TAB
        "BACK_SPACE"-> KeyEvent.VK_BACK_SPACE
        "DELETE"    -> KeyEvent.VK_DELETE
        "SPACE"     -> KeyEvent.VK_SPACE
        "UP"        -> KeyEvent.VK_UP
        "DOWN"      -> KeyEvent.VK_DOWN
        "LEFT"      -> KeyEvent.VK_LEFT
        "RIGHT"     -> KeyEvent.VK_RIGHT
        "HOME"      -> KeyEvent.VK_HOME
        "END"       -> KeyEvent.VK_END
        "PAGE_UP"   -> KeyEvent.VK_PAGE_UP
        "PAGE_DOWN" -> KeyEvent.VK_PAGE_DOWN
        "F1"  -> KeyEvent.VK_F1;  "F2"  -> KeyEvent.VK_F2;  "F3"  -> KeyEvent.VK_F3
        "F4"  -> KeyEvent.VK_F4;  "F5"  -> KeyEvent.VK_F5;  "F6"  -> KeyEvent.VK_F6
        "F7"  -> KeyEvent.VK_F7;  "F8"  -> KeyEvent.VK_F8;  "F9"  -> KeyEvent.VK_F9
        "F10" -> KeyEvent.VK_F10; "F11" -> KeyEvent.VK_F11; "F12" -> KeyEvent.VK_F12
        else -> null
    }

    private fun modCode(mod: String): Int? = when (mod) {
        "ctrl"  -> KeyEvent.VK_CONTROL
        "shift" -> KeyEvent.VK_SHIFT
        "alt"   -> KeyEvent.VK_ALT
        else    -> null
    }

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun dblField(name: String, json: String): Double? =
        Regex(""""$name"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun arrField(name: String, json: String): List<String> =
        Regex(""""$name"\s*:\s*\[([^\]]*)\]""").find(json)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
            ?: emptyList()
}
