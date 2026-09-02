package com.ecommerce.media

import kotlinx.serialization.Serializable

enum class MediaStatus { PENDING_UPLOAD, PROCESSING, READY, FAILED, DELETED }

@Serializable
data class MediaAsset(val id: String, val ownerId: String, val objectKey: String, val bucket: String, val originalFilename: String?, val contentType: String, val sizeBytes: Long, val checksumSha256: String, val width: Int?, val height: Int?, val status: MediaStatus, val createdAt: String, val updatedAt: String, val variants: List<MediaVariant> = emptyList())

@Serializable
data class MediaVariant(val id: String, val mediaId: String, val variantType: String, val objectKey: String, val contentType: String, val sizeBytes: Long, val width: Int?, val height: Int?, val url: String? = null)

@Serializable
data class MediaEventPayload(val mediaId: String, val status: String, val objectKey: String)

data class UploadValidation(val contentType: String, val extension: String)

fun validateUpload(contentType: String, bytes: ByteArray, maxBytes: Long): UploadValidation {
    if (bytes.isEmpty() || bytes.size > maxBytes) throw com.ecommerce.platform.error.ApiException(com.ecommerce.platform.error.ErrorCode.VALIDATION_ERROR, "File size is invalid.", 400)
    val normalized = contentType.lowercase().substringBefore(';')
    val extension = when {
        normalized == "image/jpeg" && bytes.copyOfRange(0, minOf(bytes.size, 3)).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "jpg"
        normalized == "image/png" && bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) -> "png"
        normalized == "image/webp" && bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        normalized == "image/gif" && bytes.size >= 6 && String(bytes, 0, 6) in setOf("GIF87a", "GIF89a") -> "gif"
        else -> throw com.ecommerce.platform.error.ApiException(com.ecommerce.platform.error.ErrorCode.VALIDATION_ERROR, "File content does not match an allowed image type.", 400)
    }
    return UploadValidation(normalized, extension)
}
