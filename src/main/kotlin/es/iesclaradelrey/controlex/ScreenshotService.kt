package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service(Service.Level.PROJECT)
class ScreenshotService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ScreenshotService::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-Screenshot").apply { isDaemon = true }
    }

    @Volatile private var future: ScheduledFuture<*>? = null
    @Volatile private var stopped = false

    fun start() {
        if (stopped || future != null) return
        scheduleNext(randomIntervalMs())
    }

    private fun randomIntervalMs(): Long {
        val cfg = project.service<DynamicConfig>()
        return Random.nextLong(cfg.captureMinMs, cfg.captureMaxMs + 1)
    }

    private fun scheduleNext(delayMs: Long) {
        if (stopped) return
        future = scheduler.schedule({
            try {
                captureAndStore()
            } catch (t: Throwable) {
                log.warn("Controlex: error capturando pantalla", t)
            } finally {
                if (!stopped) scheduleNext(randomIntervalMs())
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun captureAndStore() {
        val basePath = project.basePath ?: return
        val dir = File(basePath, ControlexConfig.DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Controlex: no se pudo crear el directorio ${dir.absolutePath}")
            return
        }

        val zipFile = File(dir, ControlexConfig.ZIP_NAME)
        val shaFile = File(dir, ControlexConfig.SHA_NAME)

        val pngBytes = ScreenshotCapturer.captureAllScreensAsPng()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val entryName = "screenshot_$ts.png"

        addToEncryptedZip(zipFile, entryName, pngBytes)
        writeSha256(zipFile, shaFile)
        ControlexLog.appendCapturas(
            project,
            "CAPTURE  entry=$entryName bytes=${pngBytes.size} zip=${zipFile.name}"
        )
    }

    private fun addToEncryptedZip(zipFile: File, entryName: String, content: ByteArray) {
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            compressionLevel = CompressionLevel.NORMAL
            fileNameInZip = entryName
        }
        ZipFile(zipFile, ControlexConfig.ZIP_PASSWORD.toCharArray()).use { zf ->
            ByteArrayInputStream(content).use { input ->
                zf.addStream(input, params)
            }
        }
    }

    private fun writeSha256(zipFile: File, shaFile: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(zipFile.toPath()).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        shaFile.writeText("$hex  ${zipFile.name}\n")
    }

    override fun dispose() {
        stopped = true
        future?.cancel(false)
        scheduler.shutdownNow()
    }
}
