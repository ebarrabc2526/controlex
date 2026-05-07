package es.iesclaradelrey.controlex

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ScreenshotCapturer {

    /**
     * Capture all screens as PNG (lossless, byte-faithful to the framebuffer).
     *
     * @param maxWidthOverride if > 0, downscale to this width (height proportional);
     *        if 0, no cap is applied — the native physical screen resolution is used.
     */
    fun captureAllScreensAsPng(maxWidthOverride: Int = 0): ByteArray {
        ByteArrayOutputStream().use { baos ->
            ImageIO.write(captureAndScale(maxWidthOverride), "png", baos)
            return baos.toByteArray()
        }
    }

    /**
     * Capture all screens as JPEG.
     *
     * @param quality 0.01..1.0
     * @param maxWidthOverride if > 0, downscale to this width (height proportional);
     *        if 0, no cap is applied — the native physical screen resolution is used.
     */
    fun captureAllScreensAsJpeg(quality: Float, maxWidthOverride: Int = 0): ByteArray {
        val image = captureAndScale(maxWidthOverride)
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

    private fun captureAndScale(maxWidthOverride: Int): BufferedImage {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        var bounds = Rectangle()
        for (device in ge.screenDevices) {
            bounds = bounds.union(device.defaultConfiguration.bounds)
        }
        if (bounds.isEmpty) {
            bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
        }
        val raw: BufferedImage = capturePhysical(bounds)
        // Quality is driven *exclusively* by per-context QualityConfig.
        // maxWidthOverride == 0 means "no cap" → native physical screen resolution.
        return scaleIfNeeded(raw, maxWidthOverride, 0)
    }

    /**
     * Capture the given user-space rectangle at the **physical** pixel resolution
     * of the underlying display. On HiDPI / Retina / Windows-with-DPI-scaling
     * systems, [Robot.createScreenCapture] returns logical (downscaled) pixels —
     * a 4K screen at 200% scaling yields 1920×1080, which gives blurry small text.
     *
     * Java 9+ exposes [Robot.createMultiResolutionScreenCapture] which returns a
     * [java.awt.image.MultiResolutionImage] containing every available variant.
     * We pick the highest-resolution variant — that is the actual framebuffer the
     * GPU is sending to the panel.
     *
     * Falls back to the legacy single-resolution capture if the multi-resolution
     * API is not available (older JBR/JDK or unusual environments).
     */
    private fun capturePhysical(bounds: Rectangle): BufferedImage {
        val robot = Robot()
        try {
            val multi = robot.createMultiResolutionScreenCapture(bounds)
            val variants: List<Image> = multi.resolutionVariants ?: emptyList()
            if (variants.isNotEmpty()) {
                var best: Image = variants[0]
                var bestPx = best.getWidth(null).toLong() * best.getHeight(null).toLong()
                for (i in 1 until variants.size) {
                    val v = variants[i]
                    val px = v.getWidth(null).toLong() * v.getHeight(null).toLong()
                    if (px > bestPx) { best = v; bestPx = px }
                }
                return toBufferedImage(best)
            }
        } catch (_: Throwable) {
            // fall through to legacy path
        }
        return robot.createScreenCapture(bounds)
    }

    private fun toBufferedImage(img: Image): BufferedImage {
        if (img is BufferedImage) return img
        val w = img.getWidth(null).coerceAtLeast(1)
        val h = img.getHeight(null).coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        try { g.drawImage(img, 0, 0, null) } finally { g.dispose() }
        return dst
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
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, dstW, dstH, null)
        } finally {
            g.dispose()
        }
        return dst
    }
}
