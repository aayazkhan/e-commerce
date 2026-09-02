package com.ecommerce.media

import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaStorageTest {
    @Test
    fun `storage builds with each endpoint configuration and resolves public or s3 urls`() {
        val withoutEndpoint = MediaStorage("media", "us-east-1", null, null, 5)
        val blankEndpoint = MediaStorage("media", "us-east-1", "   ", "https://cdn.example/", 5)
        val configuredEndpoint = MediaStorage("media", "us-east-1", "http://localhost:9000", "https://cdn.example///", 5)
        try {
            assertEquals("s3://media/original/a.jpg", withoutEndpoint.url("original/a.jpg"))
            assertEquals("https://cdn.example/thumb/a.jpg", blankEndpoint.url("thumb/a.jpg"))
            assertEquals("https://cdn.example/thumb/a.jpg", configuredEndpoint.url("thumb/a.jpg"))
        } finally {
            withoutEndpoint.close()
            blankEndpoint.close()
            configuredEndpoint.close()
        }
    }

    @Test
    fun `storage delegates blob operations and presigning to the injected provider boundary`() {
        val client = fakeClient("stored-bytes".toByteArray())
        val presigner = fakePresigner()
        val storage = MediaStorage("media", "https://cdn.example", 5, client, presigner)

        storage.put("original/a.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        assertEquals("stored-bytes", storage.get("original/a.jpg").decodeToString())
        storage.delete("original/a.jpg")
        assertEquals("https://upload.example/media/original/a.jpg", storage.presignPut("original/a.jpg", "image/jpeg", 3))

        storage.close()
    }

    private fun fakeClient(bytes: ByteArray): S3Client = Proxy.newProxyInstance(
        S3Client::class.java.classLoader,
        arrayOf(S3Client::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "putObject" -> PutObjectResponse.builder().build()
            "deleteObject" -> DeleteObjectResponse.builder().build()
            "getObjectAsBytes" -> ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes)
            "close" -> Unit
            else -> defaultValue(method.returnType)
        }
    } as S3Client

    private fun fakePresigner(): S3Presigner = Proxy.newProxyInstance(
        S3Presigner::class.java.classLoader,
        arrayOf(S3Presigner::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "presignPutObject" -> PresignedPutObjectRequest.builder()
                .expiration(Instant.parse("2026-08-20T00:05:00Z"))
                .isBrowserExecutable(false)
                .signedHeaders(mapOf("host" to listOf("upload.example")))
                .signedPayload(SdkBytes.fromByteArray(ByteArray(0)))
                .httpRequest(
                    SdkHttpRequest.builder()
                        .method(SdkHttpMethod.PUT)
                        .protocol("https")
                        .host("upload.example")
                        .encodedPath("/media/original/a.jpg")
                        .build(),
                )
                .build()
            "close" -> Unit
            else -> defaultValue(method.returnType)
        }
    } as S3Presigner

    private fun defaultValue(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
