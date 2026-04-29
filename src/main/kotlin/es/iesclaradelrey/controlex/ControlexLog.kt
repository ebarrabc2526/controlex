package es.iesclaradelrey.controlex

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ControlexLog {

    private val log = Logger.getInstance(ControlexLog::class.java)
    private val tsFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    @Synchronized
    fun appendPlugins(project: Project, message: String) {
        write(project, ControlexConfig.PLUGINS_LOG_NAME, message)
    }

    @Synchronized
    fun appendCapturas(project: Project, message: String) {
        write(project, ControlexConfig.CAPTURAS_LOG_NAME, message)
    }

    private fun write(project: Project, fileName: String, message: String) {
        val basePath = project.basePath ?: return
        val dir = File(basePath, ControlexConfig.DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Controlex: no se pudo crear el directorio ${dir.absolutePath}")
            return
        }
        val file = File(dir, fileName)
        val line = "${tsFormat.get().format(Date())} $message${System.lineSeparator()}"
        try {
            Files.write(
                file.toPath(),
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (t: Throwable) {
            log.warn("Controlex: error escribiendo log $fileName", t)
        }
    }
}
