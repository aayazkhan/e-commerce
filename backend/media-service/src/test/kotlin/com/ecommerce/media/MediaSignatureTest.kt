package com.ecommerce.media

import com.ecommerce.platform.error.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaSignatureTest {
    @Test
    fun `supported image signatures map to normalized extensions`() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val webp = "RIFFxxxxWEBP".toByteArray()
        val gif87 = "GIF87a".toByteArray()
        val gif89 = "GIF89a".toByteArray()

        assertEquals(UploadValidation("image/jpeg", "jpg"), validateUpload("IMAGE/JPEG; charset=binary", jpg, jpg.size.toLong()))
        assertEquals(UploadValidation("image/png", "png"), validateUpload("image/png", png, png.size.toLong()))
        assertEquals(UploadValidation("image/webp", "webp"), validateUpload("image/webp", webp, webp.size.toLong()))
        assertEquals(UploadValidation("image/gif", "gif"), validateUpload("image/gif", gif87, gif87.size.toLong()))
        assertEquals(UploadValidation("image/gif", "gif"), validateUpload("image/gif", gif89, gif89.size.toLong()))
    }

    @Test
    fun `upload rejects empty oversized and mismatched content`() {
        val invalid = listOf(
            { validateUpload("image/png", byteArrayOf(), 10) },
            { validateUpload("image/png", byteArrayOf(1, 2), 1) },
            { validateUpload("image/png", byteArrayOf(1, 2, 3), 10) },
            { validateUpload("application/pdf", byteArrayOf(1, 2, 3), 10) },
        )

        invalid.forEach { action ->
            assertFailsWith<ApiException> { action() }
        }
    }
}
