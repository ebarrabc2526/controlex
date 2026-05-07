package es.iesclaradelrey.controlex

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Live-tunable streaming/capture quality. Persisted at ~/.controlex/quality.json
 * so values survive plugin restarts. Mutated by the `quality-set` command from
 * the panel.
 *
 *  - jpegQuality: JPEG compression quality, 1..100 (applied to both periodic
 *    snapshots and live frames).
 *  - streamFps:   live frames per second, 1..15.
 *  - maxWidthPx:  downscaled max width in pixels, 0 = original size.
 */
@Service(Service.Level.PROJECT)
class QualityConfig(@Suppress("UNUSED_PARAMETER") project: Project) {

    private val log = Logger.getInstance(QualityConfig::class.java)
    private val file: File = run {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".${ControlexConfig.DIR_NAME}/quality.json")
    }

    @Volatile var jpegQuality: Int = (ControlexConfig.JPEG_QUALITY * 100).toInt().coerceIn(1, 100)
    @Volatile var streamFps:   Int = ControlexConfig.STREAM_FPS.coerceIn(1, 15)
    @Volatile var maxWidthPx:  Int = ControlexConfig.ANCHO_MAX_PX.coerceAtLeast(0)

    /** Listeners notified after any field changes (e.g. ScreenStreamService restarts its loop). */
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }

    init { loadFromDisk() }

    /** Quality used by both live and periodic, expressed as 0.01..1.0. */
    fun jpegQualityFloat(): Float = (jpegQuality / 100f).coerceIn(0.01f, 1.0f)

    /** Apply settings received from the server. Any null field is left unchanged. */
    @Synchronized
    fun apply(jpeg: Int?, fps: Int?, maxW: Int?) {
        var changed = false
        if (jpeg != null) { val v = jpeg.coerceIn(1, 100);   if (v != jpegQuality) { jpegQuality = v; changed = true } }
        if (fps  != null) { val v = fps.coerceIn(1, 15);     if (v != streamFps)   { streamFps   = v; changed = true } }
        if (maxW != null) { val v = maxW.coerceAtLeast(0).coerceAtMost(3840); if (v != maxWidthPx) { maxWidthPx = v; changed = true } }
        if (changed) {
            persist()
            val snapshot = synchronized(listeners) { listeners.toList() }
            for (l in snapshot) try { l() } catch (_: Throwable) {}
            log.info("Controlex: quality applied → q=$jpegQuality fps=$streamFps maxW=$maxWidthPx")
        }
    }

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val txt = file.readText()
            jpegQuality = numField("jpegQuality", txt)?.toInt()?.coerceIn(1, 100) ?: jpegQuality
            streamFps   = numField("fps",         txt)?.toInt()?.coerceIn(1, 15)  ?: streamFps
            maxWidthPx  = numField("maxWidth",    txt)?.toInt()?.coerceAtLeast(0)?.coerceAtMost(3840) ?: maxWidthPx
        } catch (e: Exception) {
            log.warn("Controlex: error leyendo quality.json: ${e.message}")
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText("""{"jpegQuality":$jpegQuality,"fps":$streamFps,"maxWidth":$maxWidthPx}""")
        } catch (e: Exception) {
            log.warn("Controlex: error guardando quality.json: ${e.message}")
        }
    }

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
