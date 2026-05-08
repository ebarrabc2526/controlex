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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
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
                    // Compatibilidad: cualquier mensaje del profesor (incluso
                    // si el servidor emite show-dialog en lugar de chat-message)
                    // se enruta a la tool window de chat. NO mostramos popup
                    // modal — al usuario le molesta más que ayuda.
                    val text  = strField("text",  verified) ?: return
                    project.service<ChatService>().add("teacher", text)
                    notifyTeacherChat(project, text)
                }
                "chat-message" -> {
                    val text = strField("text", verified) ?: return
                    val at   = numField("at",   verified) ?: System.currentTimeMillis()
                    val who  = strField("who",  verified) ?: "teacher"
                    project.service<ChatService>().add(who, text, at)
                    notifyTeacherChat(project, text)
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
                    injectClick(project, normX, normY, button)
                }
                "lock-session" -> {
                    val msg = strField("message", verified)
                        ?: "Espera instrucciones del profesor."
                    project.service<SessionLockManager>().lock(msg)
                }
                "unlock-session" -> {
                    project.service<SessionLockManager>().unlock()
                }
                "create-dir" -> {
                    val rel = strField("path", verified) ?: return
                    createDir(project, rel)
                }
                "laser-pointer" -> {
                    val visible = strField("visible", verified) == "true"
                    if (visible) {
                        val x = dblField("x", verified) ?: return
                        val y = dblField("y", verified) ?: return
                        project.service<LaserPointerService>().showAt(x, y)
                        dispatchSyntheticHover(x, y)
                    } else {
                        project.service<LaserPointerService>().hide()
                        dispatchSyntheticHoverExit()
                    }
                }
                "send-file" -> {
                    val rel     = strField("path",    verified) ?: return
                    val b64     = strField("content", verified) ?: return
                    val content = try { Base64.getDecoder().decode(b64) }
                                  catch (_: Exception) { return }
                    writeFile(project, rel, content)
                    val name = rel.substringAfterLast('/').substringAfterLast('\\')
                    project.service<ChatService>().add("teacher",
                        "📎 Recibido: $name (${content.size / 1024} KB) → $rel")
                    // Despliega la tool window para que el alumno lo vea
                    try {
                        ApplicationManager.getApplication().invokeLater {
                            ToolWindowManager.getInstance(project).getToolWindow("Controlex Chat")?.show(null)
                        }
                    } catch (_: Throwable) {}
                }
                "open-url" -> {
                    val url = strField("url", verified) ?: return
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        BrowserUtil.browse(url)
                    }
                }
                "pair-open" -> {
                    val rel = strField("path", verified) ?: return
                    project.service<PairSessionManager>().openSession(rel)
                }
                "pair-close" -> {
                    val rel = strField("path", verified) ?: return
                    project.service<PairSessionManager>().closeSession(rel)
                }
                "stream-start" -> project.service<ScreenStreamService>().startStream()
                "stream-stop"  -> project.service<ScreenStreamService>().stopStream()
                "quality-set" -> {
                    // Per-context payload: {archive:{maxWidth?}, panel:{jpegQuality?,maxWidth?}, live:{jpegQuality?,maxWidth?,fps?}}.
                    // Legacy flat shape ({jpegQuality?,fps?,maxWidth?}) is also handled inside applyJson.
                    project.service<QualityConfig>().applyJson(verified)
                }
                else -> log.warn("Controlex: tipo de comando desconocido: $type")
            }
        } catch (e: Exception) {
            log.warn("Controlex: error ejecutando comando remoto", e)
        }
    }

    private fun notifyTeacherChat(project: Project, @Suppress("UNUSED_PARAMETER") text: String) {
        // Solo abrir la tool window — el balloon era percibido como "ventana
        // flotante" molesta. La burbuja del mensaje aparece en el hilo en
        // cuanto la tool window se renderiza.
        ApplicationManager.getApplication().invokeLater {
            try {
                ToolWindowManager.getInstance(project).getToolWindow("Controlex Chat")?.show(null)
            } catch (_: Throwable) {}
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

    private fun createDir(project: Project, relativePath: String) {
        val base = project.basePath ?: return
        val dir = File(base, relativePath)
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                dir.mkdirs()
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
            }
            log.info("Controlex: directorio creado: ${dir.path}")
        } catch (e: Exception) {
            log.warn("Controlex: error creando directorio ${dir.path}", e)
        }
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

    private fun injectClick(project: Project, normX: Double, normY: Double, button: Int) {
        try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            var bounds = java.awt.Rectangle()
            for (dev in ge.screenDevices) bounds = bounds.union(dev.defaultConfiguration.bounds)
            if (bounds.isEmpty) bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
            val screenX = (bounds.x + normX * bounds.width).toInt()
            val screenY = (bounds.y + normY * bounds.height).toInt()

            // Buscamos la ventana Swing más superficial que contenga el punto.
            // Esto incluye el JFrame de IntelliJ pero TAMBIÉN los popups
            // (menús contextuales, autocompletes, dialogs flotantes…) que
            // viven en sus propias Window instances. Si el click cae en
            // un popup, hay que despacharlo al popup, no al frame de detrás.
            val targetWindow = findTopmostWindowContaining(screenX, screenY)
            if (targetWindow != null) {
                dispatchSyntheticClick(targetWindow, screenX, screenY, button)
                return
            }

            // Fallback: el click cae fuera de la ventana de IntelliJ o no
            // tenemos frame. Recurrimos a Robot.mousePress (sí depende del
            // foreground del SO; en clase real, donde el alumno tiene el
            // IDE en pantalla, esto va bien).
            val mask = when (button) {
                2 -> InputEvent.BUTTON2_DOWN_MASK
                3 -> InputEvent.BUTTON3_DOWN_MASK
                else -> InputEvent.BUTTON1_DOWN_MASK
            }
            val robot = Robot()
            robot.mouseMove(screenX, screenY)
            robot.delay(60)
            robot.mousePress(mask)
            robot.delay(60)
            robot.mouseRelease(mask)
        } catch (e: Exception) {
            log.warn("Controlex: error en inject-click", e)
        }
    }

    /** Componente sobre el que el láser estaba "haciendo hover" la última vez. */
    @Volatile private var lastHoverComponent: java.awt.Component? = null

    /**
     * Despacha eventos MOUSE_MOVED + MOUSE_ENTERED/EXITED al componente Swing
     * que ocupe la posición fraccional (fx, fy) en pantalla del alumno. Esto
     * permite que el láser, al pasar sobre items de un menú abierto,
     * expanda submenús como si fuera un ratón real moviéndose.
     */
    private fun dispatchSyntheticHover(fx: Double, fy: Double) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            try {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                var bounds = java.awt.Rectangle()
                for (dev in ge.screenDevices) bounds = bounds.union(dev.defaultConfiguration.bounds)
                if (bounds.isEmpty) bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
                val sx = (bounds.x + fx * bounds.width).toInt()
                val sy = (bounds.y + fy * bounds.height).toInt()
                val window = findTopmostWindowContaining(sx, sy) ?: return@invokeLater
                val loc = window.locationOnScreen
                val target = javax.swing.SwingUtilities.getDeepestComponentAt(window, sx - loc.x, sy - loc.y) ?: return@invokeLater
                val pt = javax.swing.SwingUtilities.convertPoint(window, sx - loc.x, sy - loc.y, target)
                val now = System.currentTimeMillis()
                val prev = lastHoverComponent
                if (prev != null && prev !== target && prev.isShowing) {
                    try {
                        val ptInPrev = javax.swing.SwingUtilities.convertPoint(target, pt, prev)
                        prev.dispatchEvent(java.awt.event.MouseEvent(
                            prev, java.awt.event.MouseEvent.MOUSE_EXITED, now, 0,
                            ptInPrev.x, ptInPrev.y, 0, false))
                    } catch (_: Throwable) {}
                }
                if (prev !== target) {
                    target.dispatchEvent(java.awt.event.MouseEvent(
                        target, java.awt.event.MouseEvent.MOUSE_ENTERED, now, 0,
                        pt.x, pt.y, 0, false))
                }
                target.dispatchEvent(java.awt.event.MouseEvent(
                    target, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0,
                    pt.x, pt.y, 0, false))
                lastHoverComponent = target
            } catch (_: Throwable) {}
        }
    }

    private fun dispatchSyntheticHoverExit() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val prev = lastHoverComponent ?: return@invokeLater
            try {
                if (prev.isShowing) {
                    prev.dispatchEvent(java.awt.event.MouseEvent(
                        prev, java.awt.event.MouseEvent.MOUSE_EXITED,
                        System.currentTimeMillis(), 0, -1, -1, 0, false))
                }
            } catch (_: Throwable) {}
            lastHoverComponent = null
        }
    }

    /**
     * Busca la ventana Swing visible más superficial que contenga el punto
     * (screenX, screenY). Heurística: si hay popups (Window que no son JFrame)
     * que contienen el punto, prefiere el más pequeño (los popups suelen ser
     * menores que el frame principal y tapan su sección). Esto encuentra los
     * menús contextuales, autocompletes, etc.
     */
    private fun findTopmostWindowContaining(screenX: Int, screenY: Int): java.awt.Window? {
        val containing = java.awt.Window.getWindows().filter { w ->
            if (!w.isShowing || !w.isVisible) return@filter false
            try {
                val loc = w.locationOnScreen
                screenX in loc.x until loc.x + w.width &&
                        screenY in loc.y until loc.y + w.height
            } catch (_: Throwable) { false }
        }
        if (containing.isEmpty()) return null
        val popups = containing.filter { it !is java.awt.Frame }
        if (popups.isNotEmpty()) {
            return popups.minByOrNull { it.width.toLong() * it.height.toLong() }
        }
        return containing.minByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * Despacha un click sintético al componente Swing más profundo dentro
     * de [window] en la posición de pantalla (screenX, screenY). Genera la
     * secuencia estándar MOUSE_PRESSED → MOUSE_RELEASED → MOUSE_CLICKED.
     * Para botón derecho, además dispara isPopupTrigger.
     */
    private fun dispatchSyntheticClick(window: java.awt.Window, screenX: Int, screenY: Int, button: Int) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            try {
                val loc = window.locationOnScreen
                val xInWin = screenX - loc.x
                val yInWin = screenY - loc.y
                val target: java.awt.Component =
                    javax.swing.SwingUtilities.getDeepestComponentAt(window, xInWin, yInWin) ?: return@invokeLater
                val pt = javax.swing.SwingUtilities.convertPoint(window, xInWin, yInWin, target)
                val swingBtn = when (button) {
                    2 -> java.awt.event.MouseEvent.BUTTON2
                    3 -> java.awt.event.MouseEvent.BUTTON3
                    else -> java.awt.event.MouseEvent.BUTTON1
                }
                val mods = when (button) {
                    2 -> InputEvent.BUTTON2_DOWN_MASK
                    3 -> InputEvent.BUTTON3_DOWN_MASK
                    else -> InputEvent.BUTTON1_DOWN_MASK
                }
                val isPopup = button == 3
                val now = System.currentTimeMillis()
                target.dispatchEvent(java.awt.event.MouseEvent(
                    target, java.awt.event.MouseEvent.MOUSE_PRESSED, now,
                    mods, pt.x, pt.y, 1, isPopup, swingBtn))
                target.dispatchEvent(java.awt.event.MouseEvent(
                    target, java.awt.event.MouseEvent.MOUSE_RELEASED, now + 30,
                    mods, pt.x, pt.y, 1, isPopup, swingBtn))
                target.dispatchEvent(java.awt.event.MouseEvent(
                    target, java.awt.event.MouseEvent.MOUSE_CLICKED, now + 30,
                    mods, pt.x, pt.y, 1, false, swingBtn))
            } catch (e: Exception) {
                log.warn("Controlex: error en synthetic click dispatch", e)
            }
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
