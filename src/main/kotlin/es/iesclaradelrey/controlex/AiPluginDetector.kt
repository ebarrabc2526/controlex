package es.iesclaradelrey.controlex

import com.intellij.ide.plugins.PluginManagerCore

object AiPluginDetector {

    data class Detection(val id: String, val name: String)

    private val AI_PLUGIN_IDS: Set<String> = setOf(
        "com.github.copilot",
        "com.intellij.ml.llm",
        "com.codeium.intellij",
        "com.codeium.windsurf",
        "com.tabnine.TabNine",
        "com.codota.csp.intellij",
        "Continue",
        "com.bito.android.bitoaicompletion",
        "com.aixcoder.intellij",
        "com.cursor.intellij",
        "com.sourcegraph.jetbrains",
        "com.github.copilot.intellij",
        "com.amazon.codewhisperer",
        "amazon.q",
        "com.fittentech.codeplugin",
        "com.supermaven.intellij",
        "com.blackbox.ai",
        "com.askcodi.intellij",
        "com.zerobug.copilot",
        "com.codegpt.intellij",
        "ee.carlrobert.chatgpt"
    )

    private val AI_KEYWORDS: List<String> = listOf(
        "copilot",
        "codeium",
        "tabnine",
        "codegpt",
        "codewhisperer",
        "amazon q",
        "cody",
        "sourcegraph",
        "aixcoder",
        "codota",
        "bito ai",
        "cursor ai",
        "fitten code",
        "supermaven",
        "windsurf",
        "blackbox ai",
        "askcodi",
        "ai assistant",
        "ml.llm",
        "chatgpt",
        "openai",
        "gemini code",
        "claude code",
        "intellicode",
        "ml completion"
    )

    fun findInstalledAiPlugins(): List<Detection> {
        val results = mutableListOf<Detection>()
        for (descriptor in PluginManagerCore.loadedPlugins) {
            if (!descriptor.isEnabled) continue
            val id = descriptor.pluginId?.idString ?: continue
            val name = descriptor.name ?: ""
            val haystack = "$id $name".lowercase()

            val matchById = AI_PLUGIN_IDS.any { it.equals(id, ignoreCase = true) }
            val matchByKeyword = AI_KEYWORDS.any { haystack.contains(it) }

            if (matchById || matchByKeyword) {
                results.add(Detection(id, name))
            }
        }
        return results
    }
}
