package com.ecommerce.media

import kotlin.test.Test
import com.ecommerce.platform.error.ApiException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaValidationTest {
    @Test fun `png signature is required`() { val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10); validateUpload("image/png", png, 1024); assertFailsWith<RuntimeException> { validateUpload("image/png", byteArrayOf(1,2,3), 1024) } }

    @Test
    fun `media validation accepts each supported signature and normalizes content type`() {
        assertEquals("jpg", validateUpload("IMAGE/JPEG; charset=binary", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 10).extension)
        assertEquals("webp", validateUpload("image/webp", "RIFFxxxxWEBP".toByteArray(), 20).extension)
        assertEquals("gif", validateUpload("image/gif", "GIF89a".toByteArray(), 10).extension)
    }

    @Test
    fun `media validation rejects empty oversized and unsupported content`() {
        assertFailsWith<ApiException> { validateUpload("image/png", byteArrayOf(), 10) }
        assertFailsWith<ApiException> { validateUpload("image/png", ByteArray(11), 10) }
        assertFailsWith<ApiException> { validateUpload("text/plain", "plain".toByteArray(), 10) }
        assertFailsWith<ApiException> { validateUpload("image/webp", "RIFF".toByteArray(), 10) }
        assertFailsWith<ApiException> { validateUpload("image/gif", "GIF00a".toByteArray(), 10) }
    }

    @Test
    fun `media validation rejects known content types with complete but invalid signatures`() {
        assertFailsWith<ApiException> { validateUpload("image/jpeg", byteArrayOf(0, 0, 0), 10) }
        assertFailsWith<ApiException> { validateUpload("image/png", ByteArray(8), 10) }
        assertFailsWith<ApiException> { validateUpload("image/webp", "RIFFxxxxJPEG".toByteArray(), 20) }
        assertFailsWith<ApiException> { validateUpload("image/webp", "XXXXxxxxWEBP".toByteArray(), 20) }
        assertFailsWith<ApiException> { validateUpload("image/gif", "H*F87a".toByteArray(), 10) }
        assertFailsWith<ApiException> { validateUpload("image/gif", "GIF".toByteArray(), 10) }
    }
}
