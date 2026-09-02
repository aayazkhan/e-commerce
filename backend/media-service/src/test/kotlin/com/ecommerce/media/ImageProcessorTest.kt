package com.ecommerce.media

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageProcessorTest {
    @Test
    fun `valid image creates all derivatives and marks asset ready`() = runBlocking {
        val storage = RecordingBlobStore()
        val repository = RecordingProcessingStore()
        val processor = ImageProcessor(storage, repository, 1_000_000)

        processor.process(asset(), imageBytes(200, 100), "corr-ready")

        assertEquals(listOf("THUMBNAIL", "SMALL", "MEDIUM", "LARGE"), storage.puts.map { it.key.substringAfterLast('/').substringBefore('.') })
        assertEquals(4, repository.variants.size)
        assertEquals(listOf(160, 200, 200, 200), repository.variants.map { it.width })
        assertEquals(1, repository.ready.size)
        assertEquals("corr-ready", repository.ready.single().correlationId)
        assertTrue(repository.failed.isEmpty())
    }

    @Test
    fun `invalid image is converted into failed processing state`() = runBlocking {
        val storage = RecordingBlobStore()
        val repository = RecordingProcessingStore()

        ImageProcessor(storage, repository, 1_000_000).process(asset(), byteArrayOf(1, 2, 3), "corr-invalid")

        assertTrue(storage.puts.isEmpty())
        assertTrue(repository.ready.isEmpty())
        assertEquals("corr-invalid", repository.failed.single().correlationId)
        assertTrue(repository.failed.single().error.isNotBlank())
    }

    @Test
    fun `storage failure stops derivative processing and marks asset failed`() = runBlocking {
        val storage = RecordingBlobStore().also { it.failure = IllegalStateException("object store unavailable") }
        val repository = RecordingProcessingStore()

        ImageProcessor(storage, repository, 1_000_000).process(asset(), imageBytes(40, 20), "corr-storage")

        assertEquals(1, storage.puts.size)
        assertTrue(repository.ready.isEmpty())
        assertEquals("object store unavailable", repository.failed.single().error)
    }

    private fun asset() = MediaAsset(
        "MEDIA-1", "owner-1", "media/original.png", "bucket", "original.png", "image/png", 10, "checksum",
        null, null, MediaStatus.PROCESSING, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z",
    )

    private fun imageBytes(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }

    private class RecordingBlobStore : MediaBlobStore {
        data class Put(val key: String, val contentType: String, val bytes: ByteArray)
        val puts = mutableListOf<Put>()
        var failure: Throwable? = null
        override fun put(key: String, contentType: String, bytes: ByteArray) { puts += Put(key, contentType, bytes); failure?.let { throw it } }
        override fun delete(key: String) = Unit
        override fun get(key: String) = error("not used")
        override fun presignPut(key: String, contentType: String, size: Long) = error("not used")
        override fun url(key: String) = "s3://bucket/$key"
    }

    private class RecordingProcessingStore : MediaProcessingStore {
        data class Ready(val id: String, val width: Int?, val height: Int?, val correlationId: String)
        data class Failed(val id: String, val error: String, val correlationId: String)
        data class Variant(val mediaId: String, val type: String, val key: String, val width: Int?, val height: Int?)
        val ready = mutableListOf<Ready>()
        val failed = mutableListOf<Failed>()
        val variants = mutableListOf<Variant>()
        override fun markReady(id: String, width: Int?, height: Int?, correlationId: String) = asset().also { ready += Ready(id, width, height, correlationId) }
        override fun markFailed(id: String, error: String, correlationId: String) = asset().also { failed += Failed(id, error, correlationId) }
        override fun addVariant(mediaId: String, type: String, key: String, contentType: String, size: Long, width: Int?, height: Int?): Int { variants += Variant(mediaId, type, key, width, height); return 1 }
        private fun asset() = MediaAsset("MEDIA-1", "owner-1", "media/original.png", "bucket", "original.png", "image/png", 10, "checksum", null, null, MediaStatus.PROCESSING, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
    }
}
