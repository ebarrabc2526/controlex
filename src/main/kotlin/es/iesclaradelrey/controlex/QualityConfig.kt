package es.iesclaradelrey.controlex

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Live-tunable streaming/capture quality with three independent contexts:
 *
 *  - **archive**: PNG saved into the encrypted local zip (forensic preservation).
 *    Only `maxWidth` applies (PNG is lossless, no quality knob).
 *  - **panel**: JPEG snapshots transmitted periodically to the server panel.
 *    Knobs: `jpegQuality` and `maxWidth`.
 *  - **live**: JPEG frames streamed via WebSocket while the panel is observing.
 *    Knobs: `jpegQuality`, `maxWidth`, `fps`.
 *
 * Persisted at ~/.controlex/quality.json. Mutated by the `quality-set` command
 * from the panel. The legacy flat schema (`{jpegQuality, fps, maxWidth}`) is
 * migrated on load (applied to all three contexts) so older configs survive.
 */
@Service(Service.Level.PROJECT)
class QualityConfig(@Suppress("UNUSED_PARAMETER") project: Project) {

    private val log = Logger.getInstance(QualityConfig::class.java)
    private val file: File = run {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".${ControlexConfig.DIR_NAME}/quality.json")
    }

    @Volatile var archiveMaxWidth: Int = 0          // 0 = original; PNG ignores quality

    @Volatile var panelJpegQuality: Int = 70
    @Volatile var panelMaxWidth:    Int = 1280
    @Volatile var panelFormat:      String = "jpeg" // "jpeg" | "png" (lossless)

    @Volatile var liveJpegQuality: Int = 55
    @Volatile var liveMaxWidth:    Int = 720
    @Volatile var liveFps:         Int = ControlexConfig.STREAM_FPS.coerceIn(1, 15)
    @Volatile var liveFormat:      String = "jpeg" // "jpeg" | "png" (lossless)

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }

    init { loadFromDisk() }

    fun panelJpegFloat(): Float = (panelJpegQuality / 100f).coerceIn(0.01f, 1.0f)
    fun liveJpegFloat():  Float = (liveJpegQuality  / 100f).coerceIn(0.01f, 1.0f)

    /**
     * Apply an incoming `quality-set` command (full payload, post-signature-verification).
     * Format:
     *   { archive: {maxWidth?}, panel: {jpegQuality?, maxWidth?},
     *     live:    {jpegQuality?, fps?, maxWidth?} }
     * Legacy flat shape (`{jpegQuality, fps, maxWidth}`) is also tolerated and applied
     * to all three contexts — for backwards compat with older servers/scripts.
     */
    @Synchronized
    fun applyJson(json: String) {
        var changed = false
        val arch = subObj("archive", json)
        val pan  = subObj("panel",   json)
        val liv  = subObj("live",    json)
        val anyContext = arch != null || pan != null || liv != null

        if (arch != null) {
            numField("maxWidth", arch)?.let { v ->
                val nw = v.toInt().coerceAtLeast(0).coerceAtMost(3840)
                if (nw != archiveMaxWidth) { archiveMaxWidth = nw; changed = true }
            }
        }
        if (pan != null) {
            numField("jpegQuality", pan)?.let { v ->
                val nv = v.toInt().coerceIn(1, 100)
                if (nv != panelJpegQuality) { panelJpegQuality = nv; changed = true }
            }
            numField("maxWidth", pan)?.let { v ->
                val nw = v.toInt().coerceAtLeast(0).coerceAtMost(3840)
                if (nw != panelMaxWidth) { panelMaxWidth = nw; changed = true }
            }
            strField("format", pan)?.let { v ->
                val nv = if (v == "png") "png" else "jpeg"
                if (nv != panelFormat) { panelFormat = nv; changed = true }
            }
        }
        if (liv != null) {
            numField("jpegQuality", liv)?.let { v ->
                val nv = v.toInt().coerceIn(1, 100)
                if (nv != liveJpegQuality) { liveJpegQuality = nv; changed = true }
            }
            numField("maxWidth", liv)?.let { v ->
                val nw = v.toInt().coerceAtLeast(0).coerceAtMost(3840)
                if (nw != liveMaxWidth) { liveMaxWidth = nw; changed = true }
            }
            numField("fps", liv)?.let { v ->
                val nv = v.toInt().coerceIn(1, 15)
                if (nv != liveFps) { liveFps = nv; changed = true }
            }
            strField("format", liv)?.let { v ->
                val nv = if (v == "png") "png" else "jpeg"
                if (nv != liveFormat) { liveFormat = nv; changed = true }
            }
        }

        if (!anyContext) {
            // Legacy flat payload — apply to all contexts.
            numField("jpegQuality", json)?.let { v ->
                val nv = v.toInt().coerceIn(1, 100)
                if (nv != panelJpegQuality) { panelJpegQuality = nv; changed = true }
                if (nv != liveJpegQuality)  { liveJpegQuality  = nv; changed = true }
            }
            numField("maxWidth", json)?.let { v ->
                val nw = v.toInt().coerceAtLeast(0).coerceAtMost(3840)
                if (nw != archiveMaxWidth) { archiveMaxWidth = nw; changed = true }
                if (nw != panelMaxWidth)   { panelMaxWidth   = nw; changed = true }
                if (nw != liveMaxWidth)    { liveMaxWidth    = nw; changed = true }
            }
            numField("fps", json)?.let { v ->
                val nv = v.toInt().coerceIn(1, 15)
                if (nv != liveFps) { liveFps = nv; changed = true }
            }
        }

        if (changed) {
            persist()
            val snap = synchronized(listeners) { listeners.toList() }
            for (l in snap) try { l() } catch (_: Throwable) {}
            log.info("Controlex: quality applied → archive(maxW=$archiveMaxWidth) panel(q=$panelJpegQuality,maxW=$panelMaxWidth) live(q=$liveJpegQuality,maxW=$liveMaxWidth,fps=$liveFps)")
        }
    }

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val txt = file.readText()
            val arch = subObj("archive", txt)
            val pan  = subObj("panel",   txt)
            val liv  = subObj("live",    txt)
            val isNew = arch != null || pan != null || liv != null

            if (isNew) {
                arch?.let {
                    archiveMaxWidth = numField("maxWidth", it)?.toInt()?.coerceAtLeast(0)?.coerceAtMost(3840) ?: archiveMaxWidth
                }
                pan?.let {
                    panelJpegQuality = numField("jpegQuality", it)?.toInt()?.coerceIn(1, 100) ?: panelJpegQuality
                    panelMaxWidth    = numField("maxWidth",    it)?.toInt()?.coerceAtLeast(0)?.coerceAtMost(3840) ?: panelMaxWidth
                    panelFormat      = strField("format",      it)?.let { v -> if (v == "png") "png" else "jpeg" } ?: panelFormat
                }
                liv?.let {
                    liveJpegQuality = numField("jpegQuality", it)?.toInt()?.coerceIn(1, 100) ?: liveJpegQuality
                    liveMaxWidth    = numField("maxWidth",    it)?.toInt()?.coerceAtLeast(0)?.coerceAtMost(3840) ?: liveMaxWidth
                    liveFps         = numField("fps",         it)?.toInt()?.coerceIn(1, 15)  ?: liveFps
                    liveFormat      = strField("format",      it)?.let { v -> if (v == "png") "png" else "jpeg" } ?: liveFormat
                }
            } else {
                // Legacy flat schema. Migrate then re-persist.
                val legacyJpeg = numField("jpegQuality", txt)?.toInt()?.coerceIn(1, 100)
                val legacyMaxW = numField("maxWidth",    txt)?.toInt()?.coerceAtLeast(0)?.coerceAtMost(3840)
                val legacyFps  = numField("fps",         txt)?.toInt()?.coerceIn(1, 15)
                if (legacyJpeg != null) { panelJpegQuality = legacyJpeg; liveJpegQuality = legacyJpeg }
                if (legacyMaxW != null) { archiveMaxWidth = legacyMaxW; panelMaxWidth = legacyMaxW; liveMaxWidth = legacyMaxW }
                if (legacyFps  != null) { liveFps = legacyFps }
                persist()
            }
        } catch (e: Exception) {
            log.warn("Controlex: error leyendo quality.json: ${e.message}")
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(
                """{"archive":{"maxWidth":$archiveMaxWidth},""" +
                """"panel":{"jpegQuality":$panelJpegQuality,"maxWidth":$panelMaxWidth,"format":"$panelFormat"},""" +
                """"live":{"jpegQuality":$liveJpegQuality,"maxWidth":$liveMaxWidth,"fps":$liveFps,"format":"$liveFormat"}}"""
            )
        } catch (e: Exception) {
            log.warn("Controlex: error guardando quality.json: ${e.message}")
        }
    }

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    /** Naive JSON sub-object extractor. Works because our payloads have only number fields inside contexts. */
    private fun subObj(name: String, json: String): String? {
        val pattern = Regex(""""$name"\s*:\s*\{""")
        val match = pattern.find(json) ?: return null
        var depth = 0
        val start = match.range.last
        for (i in start until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
        }
        return null
    }
}
