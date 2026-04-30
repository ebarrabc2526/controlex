package es.iesclaradelrey.controlex

import com.intellij.openapi.diagnostic.Logger
import java.util.Properties

object ControlexConfig {

    private val log = Logger.getInstance(ControlexConfig::class.java)

    const val DIR_NAME: String = "controlex"
    const val ZIP_NAME: String = "capturas.zip"
    const val SHA_NAME: String = "capturas.zip.sha256"
    const val PLUGINS_LOG_NAME: String = "plugins.log"
    const val CAPTURAS_LOG_NAME: String = "capturas.log"
    const val PLUGINS_SNAPSHOT_NAME: String = ".plugins-snapshot"
    const val CLIENT_ID_FILE_NAME: String = "client-id.txt"
    const val COMMANDS_LOG_NAME: String = "comandos.log"

    const val SELF_PLUGIN_ID: String = "es.iesclaradelrey.controlex"

    private const val RESOURCE_PATH: String = "/controlex.properties"

    private const val DEFAULT_PASSWORD: String = "ControlEx-IES-ClaraDelRey-2026"
    private const val DEFAULT_FREQ_MIN_SECONDS: Long = 30L
    private const val DEFAULT_FREQ_MAX_SECONDS: Long = 120L
    private const val DEFAULT_ANCHO_MAX_PX: Int = 0
    private const val DEFAULT_ALTO_MAX_PX: Int = 0
    private const val DEFAULT_SERVER_URL: String = "https://controlex.ebarrab.com"
    private const val DEFAULT_SERVER_API_KEY: String = "ControlEx-IES-ClaraDelRey-2026"
    private const val DEFAULT_TRANSMIT_FREQ_SECONDS: Long = 30L
    private const val DEFAULT_JPEG_QUALITY: Float = 0.80f

    val ZIP_PASSWORD: String
    val FRECUENCIA_MIN_SEGUNDOS: Long
    val FRECUENCIA_MAX_SEGUNDOS: Long
    val MIN_INTERVAL_MS: Long
    val MAX_INTERVAL_MS: Long
    val ANCHO_MAX_PX: Int
    val ALTO_MAX_PX: Int
    val SERVER_URL: String
    val SERVER_API_KEY: String
    val TRANSMIT_FREQ_SECONDS: Long
    val JPEG_QUALITY: Float

    init {
        val props = Properties()
        try {
            ControlexConfig::class.java.getResourceAsStream(RESOURCE_PATH)?.use { input ->
                props.load(input)
            }
        } catch (t: Throwable) {
            log.warn("Controlex: error leyendo $RESOURCE_PATH; se usaran valores por defecto", t)
        }

        ZIP_PASSWORD = props.getProperty("zip.password")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PASSWORD

        val minSec = props.getProperty("capture.frecuenciaMinSegundos")?.toLongOrNull()
            ?: DEFAULT_FREQ_MIN_SECONDS
        val maxSec = props.getProperty("capture.frecuenciaMaxSegundos")?.toLongOrNull()
            ?: DEFAULT_FREQ_MAX_SECONDS

        val (minSan, maxSan) = sanitize(minSec, maxSec)
        FRECUENCIA_MIN_SEGUNDOS = minSan
        FRECUENCIA_MAX_SEGUNDOS = maxSan
        MIN_INTERVAL_MS = minSan * 1000L
        MAX_INTERVAL_MS = maxSan * 1000L

        ANCHO_MAX_PX = (props.getProperty("capture.anchoMaxPx")?.toIntOrNull()
            ?: DEFAULT_ANCHO_MAX_PX).coerceAtLeast(0)
        ALTO_MAX_PX = (props.getProperty("capture.altoMaxPx")?.toIntOrNull()
            ?: DEFAULT_ALTO_MAX_PX).coerceAtLeast(0)

        SERVER_URL = props.getProperty("server.url")?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SERVER_URL
        SERVER_API_KEY = props.getProperty("server.apiKey")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SERVER_API_KEY
        TRANSMIT_FREQ_SECONDS = (props.getProperty("server.transmitFreqSeconds")?.toLongOrNull()
            ?: DEFAULT_TRANSMIT_FREQ_SECONDS).coerceAtLeast(5L)
        JPEG_QUALITY = (props.getProperty("server.jpegQuality")?.toFloatOrNull()
            ?: DEFAULT_JPEG_QUALITY).coerceIn(0.1f, 1.0f)
    }

    private fun sanitize(minSec: Long, maxSec: Long): Pair<Long, Long> {
        val safeMin = if (minSec < 1L) 1L else minSec
        val safeMax = if (maxSec < safeMin) safeMin else maxSec
        return safeMin to safeMax
    }
}
