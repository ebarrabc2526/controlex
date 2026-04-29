package es.iesclaradelrey.controlex

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ScreenshotCapturer {

    fun captureAllScreensAsPng(): ByteArray {
        ByteArrayOutputStream().use { baos ->
            ImageIO.write(captureAndScale(), "png", baos)
            return baos.toByteArray()
        }
    }

    fun captureAllScreensAsJpeg(quality: Float): ByteArray {
        val image = captureAndScale()
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { dst ->
                val g = dst.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
            }
        }
        ByteArrayOutputStream().use { baos ->
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val params = writer.defaultWriteParam.also {
                it.compressionMode = ImageWriteParam.MODE_EXPLICIT
                it.compressionQuality = quality.coerceIn(0.1f, 1.0f)
            }
            ImageIO.createImageOutputStream(baos).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(rgb, null, null), params)
                writer.dispose()
            }
            return baos.toByteArray()
        }
    }

    private fun captureAndScale(): BufferedImage {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        var bounds = Rectangle()
        for (device in ge.screenDevices) {
            bounds = bounds.union(device.defaultConfiguration.bounds)
        }
        if (bounds.isEmpty) {
            bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
        }
        val raw: BufferedImage = Robot().createScreenCapture(bounds)
        return scaleIfNeeded(raw, ControlexConfig.ANCHO_MAX_PX, ControlexConfig.ALTO_MAX_PX)
    }

    private fun scaleIfNeeded(src: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        if (maxW <= 0 && maxH <= 0) return src
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return src

        val limitW = if (maxW > 0) maxW else Int.MAX_VALUE
        val limitH = if (maxH > 0) maxH else Int.MAX_VALUE
        val scale = minOf(limitW.toDouble() / srcW, limitH.toDouble() / srcH, 1.0)
        if (scale >= 1.0) return src

        val dstW = (srcW * scale).toInt().coerceAtLeast(1)
        val dstH = (srcH * scale).toInt().coerceAtLeast(1)
        val dst = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, dstW, dstH, null)
        } finally {
            g.dispose()
        }
        return dst
    }
}
