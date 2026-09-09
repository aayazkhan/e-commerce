package com.ecommerce.media

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaRoutesTest {
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `media routes cover authentication validation upload lookup completion and delete`() = testApplication {
        val store = FakeMediaStore()
        val blobs = FakeBlobStore()
        val processor = FakeProcessor()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        application { installRoutes(store, blobs, processor, scope) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/media/presign").status)
        val ordinary = token()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/media/media-1") { auth(ordinary) }.status)
        val uploader = token(permissions = listOf("MEDIA_UPLOAD", "MEDIA_DELETE"))

        val invalidChecksum = client.post("/api/v1/media/presign") {
            auth(uploader); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"image/jpeg\",\"sizeBytes\":3,\"checksumSha256\":\"bad\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidChecksum.status)
        assertTrue(invalidChecksum.bodyAsText().contains("VALIDATION_ERROR"))

        val checksum = "a".repeat(64)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/media/presign") {
            auth(ordinary); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"image/jpeg\",\"sizeBytes\":3,\"checksumSha256\":\"$checksum\"}")
        }.status)
        val presign = client.post("/api/v1/media/presign") {
            auth(uploader); contentType(ContentType.Application.Json)
            setBody("{\"filename\":null,\"contentType\":\"image/JPEG; charset=binary\",\"sizeBytes\":3,\"checksumSha256\":\"$checksum\"}")
        }
        assertEquals(HttpStatusCode.OK, presign.status)
        assertEquals("bucket", store.createdInput?.bucket)
        assertTrue(blobs.presignedKey!!.contains("owner-1"))

        val upload = client.post("/api/v1/media/upload") {
            auth(uploader); contentType(ContentType.Image.JPEG); header("X-File-Name", "x".repeat(300)); setBody(jpegBytes)
        }
        assertEquals(HttpStatusCode.Accepted, upload.status)
        assertEquals(3L, store.createdInput?.sizeBytes)
        assertEquals("image/jpeg", store.createdInput?.contentType)
        assertEquals("media-upload", store.lastProcessedId)
        assertEquals(1, processor.calls.size)

        val found = client.get("/api/v1/media/media-1")
        assertEquals(HttpStatusCode.OK, found.status)
        assertTrue(found.bodyAsText().contains("/api/v1/media/media-1/variants/variant-1/view"), found.bodyAsText())

        val noRedirectClient = client.config { followRedirects = false }
        val view = noRedirectClient.get("/api/v1/media/media-1/variants/variant-1/view")
        assertEquals(HttpStatusCode.Found, view.status)
        assertEquals("https://download.test/media/variant.jpg", view.headers[HttpHeaders.Location])
        store.missingIds += "missing"
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/media/missing").status)

        store.assets["other"] = sampleAsset.copy(id = "other", ownerId = "other-owner")
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/media/other/complete") { auth(uploader) }.status)
        store.missingIds += "missing-complete"
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/media/missing-complete/complete") { auth(uploader) }.status)
        blobs.objects[sampleAsset.objectKey] = jpegBytes
        store.assets["media-1"] = sampleAsset.copy(checksumSha256 = "not-the-checksum")
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/media/media-1/complete") { auth(uploader) }.status)
        store.assets["media-1"] = sampleAsset.copy(checksumSha256 = jpegChecksum)
        val complete = client.post("/api/v1/media/media-1/complete") { auth(uploader) }
        assertEquals(HttpStatusCode.Accepted, complete.status)
        assertEquals("media-1", store.lastProcessedId)

        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/media/media-1") { auth(uploader) }.status)
        assertTrue(blobs.deletedKeys.contains("media/original.jpg"))
        assertTrue(blobs.deletedKeys.contains("media/variant.jpg"))
        scope.cancel()
    }

    @Test
    fun `media delete tolerates storage cleanup failure and validates metadata boundaries`() = testApplication {
        val store = FakeMediaStore()
        val blobs = FakeBlobStore(failDelete = true)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        application { installRoutes(store, blobs, FakeProcessor(), scope) }
        val admin = token(roles = listOf("ADMIN"))
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/media/media-1") { auth(admin) }.status)
        val superAdmin = token(roles = listOf("SUPER_ADMIN"))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/media/presign") {
            auth(superAdmin); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"image/png\",\"sizeBytes\":3,\"checksumSha256\":\"${"c".repeat(64)}\"}")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/media/presign") {
            auth(superAdmin); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"image/jpeg\",\"sizeBytes\":1000001,\"checksumSha256\":\"${"d".repeat(64)}\"}")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/media/presign") {
            auth(superAdmin); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"application/pdf\",\"sizeBytes\":3,\"checksumSha256\":\"${"e".repeat(64)}\"}")
        }.status)
        store.assets["empty"] = sampleAsset.copy(id = "empty", variants = emptyList())
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/media/empty") { auth(admin) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/media/presign") {
            auth(admin); contentType(ContentType.Application.Json)
            setBody("{\"contentType\":\"application/pdf\",\"sizeBytes\":0,\"checksumSha256\":\"${"b".repeat(64)}\"}")
        }.status)
        scope.cancel()
    }

    private fun io.ktor.server.application.Application.installRoutes(store: MediaStore, blobs: MediaBlobStore, processor: MediaProcessor, scope: CoroutineScope) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureMediaRoutes(store, blobs, verifier, 1_000_000, "bucket", processor, scope) }
    }

    private fun HttpRequestBuilder.auth(value: String) { header(HttpHeaders.Authorization, "Bearer $value") }

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"owner-1\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256")) }
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeMediaStore : MediaStore {
        val assets = mutableMapOf("media-1" to sampleAsset)
        val missingIds = mutableSetOf<String>()
        var createdInput: MediaInput? = null
        var lastProcessedId: String? = null
        override fun create(input: MediaInput, correlationId: String): MediaAsset { createdInput = input; return sampleAsset.copy(id = "media-upload", ownerId = input.ownerId, objectKey = input.objectKey, contentType = input.contentType, sizeBytes = input.sizeBytes, checksumSha256 = input.checksum) }
        override fun markProcessing(id: String): Int { lastProcessedId = id; return 1 }
        override fun find(id: String): MediaAsset? = assets[id]?.takeUnless { id in missingIds } ?: if (id == "media-upload") sampleAsset.copy(id = "media-upload", ownerId = "owner-1") else null
        override fun delete(id: String, actorId: String, correlationId: String): MediaAsset = assets[id] ?: sampleAsset
    }

    private class FakeBlobStore(private val failDelete: Boolean = false) : MediaBlobStore {
        val objects = mutableMapOf<String, ByteArray>()
        val deletedKeys = mutableListOf<String>()
        var presignedKey: String? = null
        override fun put(key: String, contentType: String, bytes: ByteArray) { objects[key] = bytes }
        override fun delete(key: String) { deletedKeys += key; if (failDelete) error("storage unavailable") }
        override fun get(key: String): ByteArray = objects[key] ?: jpegBytes
        override fun presignPut(key: String, contentType: String, size: Long): String { presignedKey = key; return "https://upload.test/$key" }
        override fun presignGet(key: String): String = "https://download.test/$key"
        override fun url(key: String): String = "https://cdn.test/$key"
    }

    private class FakeProcessor : MediaProcessor {
        val calls = mutableListOf<String>()
        override suspend fun process(asset: MediaAsset, bytes: ByteArray, correlationId: String): MediaAsset { calls += asset.id; return asset }
    }

    private companion object {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val jpegChecksum = MessageDigest.getInstance("SHA-256").digest(jpegBytes).joinToString("") { "%02x".format(it) }
        val sampleAsset = MediaAsset("media-1", "owner-1", "media/original.jpg", "bucket", "original.jpg", "image/jpeg", 3, jpegChecksum, 100, 100, MediaStatus.READY, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z", listOf(MediaVariant("variant-1", "media-1", "THUMBNAIL", "media/variant.jpg", "image/jpeg", 2, 10, 10)))
    }
}
