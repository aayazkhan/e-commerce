package com.ecommerce.media

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.KafkaOutboxPublisher
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.ServiceKafkaConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.MessageDigest
import java.util.UUID

fun Application.module() {
    val config = environment.config; val database = ServiceDatabase(ServiceDatabaseConfig(config.required("media.database.url"), config.required("media.database.username"), config.required("media.database.password")), "classpath:db/migration"); val storage = MediaStorage(config.required("media.storage.bucket"), config.required("media.storage.region"), config.optional("media.storage.endpoint"), config.optional("media.storage.publicBaseUrl"), config.required("media.storage.presignMinutes").toLong()); val repository = MediaRepository(database.dataSource()); val verifier = HmacJwtAccessVerifier(config.required("media.jwt.issuer"), config.required("media.jwt.audience"), parseKeys(config.required("media.jwt.keys"))); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("media.kafka.bootstrapServers"), config.required("media.kafka.topic"), config.required("media.kafka.tenantId")), "media-service"); publisher.start(scope); val processor = ImageProcessor(storage, repository, config.required("media.storage.maxBytes").toLong()); monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); storage.close(); database.close() }
    install(DefaultHeaders); install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${UUID.randomUUID()}" } }; install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }; install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }; install(StatusPages) { exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }; exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) } }; install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "media-service")) }; get("/health/ready") { if (runCatching { database.ping() }.getOrDefault(false)) call.respond(Health("UP", "media-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "media-service")) }; get("/metrics") { call.respondText("# TYPE media_upload_total counter\nmedia_upload_total 1\n", ContentType.Text.Plain) }
    }
    configureMediaRoutes(repository, storage, verifier, config.required("media.storage.maxBytes").toLong(), config.required("media.storage.bucket"), processor, scope)
}

fun Application.configureMediaRoutes(repository: MediaStore, storage: MediaBlobStore, verifier: HmacJwtAccessVerifier, maxBytes: Long, bucket: String, processor: MediaProcessor, scope: CoroutineScope) {
    fun requestId(call: ApplicationCall) = call.callId.orEmpty()
    routing {
        post("/api/v1/media/presign") { val principal = call.requirePermission(verifier, "MEDIA_UPLOAD"); val request = call.receive<PresignRequest>(); if (!request.checksumSha256.matches(Regex("[0-9a-fA-F]{64}"))) throw ApiException(ErrorCode.VALIDATION_ERROR, "A SHA-256 checksum is required.", 400); val validation = validateMetadata(request.contentType, request.sizeBytes, maxBytes); val id = "med_${UUID.randomUUID()}"; val key = "media/${principal.subject.lowercase()}/$id.$validation"; val asset = repository.create(MediaInput(principal.subject, key, bucket, request.filename, request.contentType.lowercase(), request.sizeBytes, request.checksumSha256.lowercase()), requestId(call)); call.respond(PresignResponse(asset, storage.presignPut(key, request.contentType, request.sizeBytes))) }
        post("/api/v1/media/upload") { val principal = call.requirePermission(verifier, "MEDIA_UPLOAD"); val bytes = call.receive<ByteArray>(); val filename = call.request.header("X-File-Name")?.take(255); val contentType = call.request.contentType().toString(); val validation = validateUpload(contentType, bytes, maxBytes); val id = "med_${UUID.randomUUID()}"; val key = "media/${principal.subject.lowercase()}/$id.${validation.extension}"; val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }; val asset = repository.create(MediaInput(principal.subject, key, bucket, filename, validation.contentType, bytes.size.toLong(), checksum), requestId(call)); storage.put(key, validation.contentType, bytes); repository.markProcessing(asset.id); scope.launch { processor.process(repository.find(asset.id)!!, bytes, requestId(call)) }; call.respond(HttpStatusCode.Accepted, repository.find(asset.id)!!) }
        post("/api/v1/media/{mediaId}/complete") { val principal = call.requirePermission(verifier, "MEDIA_UPLOAD"); val id = call.parameters.requireValue("mediaId"); val asset = repository.find(id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Media asset not found.", 404); if (asset.ownerId != principal.subject) throw ApiException(ErrorCode.FORBIDDEN, "You do not own this media asset.", 403); val bytes = storage.get(asset.objectKey); validateUpload(asset.contentType, bytes, maxBytes); val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }; if (asset.checksumSha256.trim() != checksum) throw ApiException(ErrorCode.VALIDATION_ERROR, "Uploaded object checksum does not match the declared checksum.", 400); repository.markProcessing(id); scope.launch { processor.process(repository.find(id)!!, bytes, requestId(call)) }; call.respond(HttpStatusCode.Accepted, repository.find(id)!!) }
        get("/api/v1/media/{mediaId}") { val asset = repository.find(call.parameters.requireValue("mediaId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Media asset not found.", 404); call.respond(asset.copy(variants = asset.variants.map { it.copy(url = storage.url(it.objectKey)) })) }
        delete("/api/v1/media/{mediaId}") { val principal = call.requirePermission(verifier, "MEDIA_DELETE"); val asset = repository.delete(call.parameters.requireValue("mediaId"), principal.subject, requestId(call)); runCatching { storage.delete(asset.objectKey); asset.variants.forEach { storage.delete(it.objectKey) } }; call.respond(Message("Media asset deleted.")) }
    }
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable private data class Message(val message: String)
@Serializable internal data class PresignRequest(val filename: String? = null, val contentType: String, val sizeBytes: Long, val checksumSha256: String)
@Serializable private data class PresignResponse(val asset: MediaAsset, val uploadUrl: String)
private fun validateMetadata(contentType: String, size: Long, max: Long): String { if (size <= 0 || size > max || contentType.lowercase().substringBefore(';') !in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) throw ApiException(ErrorCode.VALIDATION_ERROR, "Media metadata is invalid.", 400); return contentType.lowercase().substringAfter('/').replace("jpeg", "jpg") }
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun ApplicationConfig.optional(path: String): String? = runCatching { property(path).getString().takeIf { it.isNotBlank() } }.getOrNull()
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val raw = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); val principal = verifier.verify(raw); if ("ADMIN" !in principal.roles && "SUPER_ADMIN" !in principal.roles && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun Parameters.requireValue(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
