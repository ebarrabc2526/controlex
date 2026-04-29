package es.iesclaradelrey.controlex

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Service(Service.Level.PROJECT)
class PluginAuditService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PluginAuditService::class.java)
    private var connection: MessageBusConnection? = null

    fun start() {
        reconcileWithSnapshot()
        ControlexLog.appendPlugins(
            project,
            "[CONTROLEX] start  id=${ControlexConfig.SELF_PLUGIN_ID} version=${selfVersion()}"
        )
        subscribeDynamicPluginEvents()
    }

    private fun subscribeDynamicPluginEvents() {
        val conn = project.messageBus.connect(this)
        connection = conn
        conn.subscribe(
            com.intellij.ide.plugins.DynamicPluginListener.TOPIC,
            object : com.intellij.ide.plugins.DynamicPluginListener {
                override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
                    ControlexLog.appendPlugins(project, "LOAD     ${formatDescriptor(pluginDescriptor)}")
                    saveSnapshot()
                }

                override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                    val tag = if (isUpdate) "UPDATE   " else "UNLOAD   "
                    ControlexLog.appendPlugins(project, "$tag${formatDescriptor(pluginDescriptor)}")
                }

                override fun pluginUnloaded(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                    if (!isUpdate) saveSnapshot()
                }
            }
        )
    }

    private fun reconcileWithSnapshot() {
        val current = currentEnabledPlugins()
        val previous = readSnapshot()
        if (previous == null) {
            ControlexLog.appendPlugins(project, "SNAPSHOT inicial: ${current.size} plugins habilitados")
            for ((id, version) in current) {
                ControlexLog.appendPlugins(project, "PRESENT  id=$id version=$version")
            }
        } else {
            val installed = current.keys - previous.keys
            val removed = previous.keys - current.keys
            val updated = current.keys.intersect(previous.keys)
                .filter { previous[it] != current[it] }

            for (id in installed) {
                ControlexLog.appendPlugins(project, "INSTALL  id=$id version=${current[id]}")
            }
            for (id in removed) {
                ControlexLog.appendPlugins(project, "REMOVE   id=$id version=${previous[id]}")
            }
            for (id in updated) {
                ControlexLog.appendPlugins(
                    project,
                    "UPGRADE  id=$id from=${previous[id]} to=${current[id]}"
                )
            }
        }
        saveSnapshot()
    }

    private fun currentEnabledPlugins(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (d in PluginManagerCore.loadedPlugins) {
            if (!d.isEnabled) continue
            val id = d.pluginId?.idString ?: continue
            map[id] = d.version ?: ""
        }
        return map
    }

    private fun snapshotFile(): File? {
        val basePath = project.basePath ?: return null
        val dir = File(basePath, ControlexConfig.DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, ControlexConfig.PLUGINS_SNAPSHOT_NAME)
    }

    private fun readSnapshot(): Map<String, String>? {
        val f = snapshotFile() ?: return null
        if (!f.exists()) return null
        return try {
            val map = LinkedHashMap<String, String>()
            for (line in Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                val sep = line.indexOf('\t')
                if (sep > 0) map[line.substring(0, sep)] = line.substring(sep + 1)
            }
            map
        } catch (t: Throwable) {
            log.warn("Controlex: error leyendo snapshot", t)
            null
        }
    }

    private fun saveSnapshot() {
        val f = snapshotFile() ?: return
        try {
            val sb = StringBuilder()
            for ((id, version) in currentEnabledPlugins()) {
                sb.append(id).append('\t').append(version).append('\n')
            }
            Files.write(f.toPath(), sb.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            log.warn("Controlex: error guardando snapshot", t)
        }
    }

    private fun formatDescriptor(d: IdeaPluginDescriptor): String =
        "id=${d.pluginId?.idString} name=\"${d.name ?: ""}\" version=${d.version ?: ""}"

    private fun selfVersion(): String {
        val id = com.intellij.openapi.extensions.PluginId.findId(ControlexConfig.SELF_PLUGIN_ID)
        val descriptor = if (id != null) PluginManagerCore.getPlugin(id) else null
        return descriptor?.version ?: ""
    }

    override fun dispose() {
        ControlexLog.appendPlugins(
            project,
            "[CONTROLEX] stop   id=${ControlexConfig.SELF_PLUGIN_ID} version=${selfVersion()}"
        )
        saveSnapshot()
        connection?.disconnect()
        connection = null
    }
}
