package com.ecommerce.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

interface MediaProcessor {
    suspend fun process(asset: MediaAsset, bytes: ByteArray, correlationId: String): MediaAsset
}

class ImageProcessor(private val storage: MediaBlobStore, private val repository: MediaProcessingStore, private val maxBytes: Long) : MediaProcessor {
    override suspend fun process(asset: MediaAsset, bytes: ByteArray, correlationId: String) = withContext(Dispatchers.IO) {
        try {
            validateUpload(asset.contentType, bytes, maxBytes)
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Image decoder rejected content")
            val sizes = listOf("THUMBNAIL" to 160, "SMALL" to 480, "MEDIUM" to 960, "LARGE" to 1600)
            sizes.forEach { (type, width) -> val scaled = scale(image, width); val output = ByteArrayOutputStream(); ImageIO.write(scaled, "jpg", output); val key = "media/${asset.id.lowercase()}/$type.jpg"; storage.put(key, "image/jpeg", output.toByteArray()); repository.addVariant(asset.id, type, key, "image/jpeg", output.size().toLong(), scaled.width, scaled.height) }
            repository.markReady(asset.id, image.width, image.height, correlationId)
        } catch (error: Throwable) { repository.markFailed(asset.id, error.message ?: "Image processing failed", correlationId) }
    }
    private fun scale(source: BufferedImage, maxWidth: Int): BufferedImage { val width = minOf(maxWidth, source.width); val height = (source.height.toDouble() * width / source.width).toInt().coerceAtLeast(1); return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target -> val graphics = target.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(source, 0, 0, width, height, null); graphics.dispose() } }
}
