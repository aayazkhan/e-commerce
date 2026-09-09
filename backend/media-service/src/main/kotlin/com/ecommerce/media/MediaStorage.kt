package com.ecommerce.media

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

interface MediaBlobStore {
    fun put(key: String, contentType: String, bytes: ByteArray)
    fun delete(key: String)
    fun get(key: String): ByteArray
    fun presignPut(key: String, contentType: String, size: Long): String
    /** A freshly-signed, time-limited GET URL -- used for a Private bucket (the default; no
     * public bucket config or provider billing verification needed). Callers should treat this
     * as short-lived and never persist it; see Application.kt's /view redirect route, which is
     * the stable, non-expiring reference actually worth storing elsewhere (e.g. in a product's
     * media list). */
    fun presignGet(key: String): String
    fun url(key: String): String
}

class MediaStorage internal constructor(
    private val bucket: String,
    private val publicBaseUrl: String?,
    private val presignMinutes: Long,
    private val client: S3Client,
    private val presigner: S3Presigner,
) : AutoCloseable, MediaBlobStore {
    constructor(bucket: String, region: String, endpoint: String?, publicBaseUrl: String?, presignMinutes: Long) : this(
        bucket,
        publicBaseUrl,
        presignMinutes,
        s3Client(region, endpoint),
        s3Presigner(region, endpoint),
    )

    override fun put(key: String, contentType: String, bytes: ByteArray) { client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(bytes.size.toLong()).build(), RequestBody.fromBytes(bytes)) }
    override fun delete(key: String) { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) }
    override fun get(key: String): ByteArray = client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
    override fun presignPut(key: String, contentType: String, size: Long): String = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(presignMinutes)).putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(size).build()).build()).url().toString()
    // 15 minutes is plenty for a browser to load an image right after the redirect; short-lived
    // on purpose since this is never meant to be cached/stored (see the interface doc comment).
    override fun presignGet(key: String): String = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(15)).getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build()).url().toString()
    override fun url(key: String): String = publicBaseUrl?.trimEnd('/')?.plus("/")?.plus(key) ?: "s3://$bucket/$key"
    override fun close() { presigner.close(); client.close() }

    private companion object {
        // Path-style (not virtual-hosted-style) addressing: a bucket name with dots or uppercase
        // letters (fine by S3-compatible providers like Backblaze B2, unlike AWS S3 proper) breaks
        // virtual-hosted-style's <bucket>.<host> DNS/TLS-cert matching, so this is the safe default
        // for any non-AWS S3-compatible endpoint.
        fun s3Client(region: String, endpoint: String?): S3Client = S3Client.builder().region(Region.of(region)).apply { if (!endpoint.isNullOrBlank()) { endpointOverride(URI.create(endpoint)); forcePathStyle(true) } }.build()
        fun s3Presigner(region: String, endpoint: String?): S3Presigner = S3Presigner.builder().region(Region.of(region)).apply { if (!endpoint.isNullOrBlank()) { endpointOverride(URI.create(endpoint)); serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()) } }.build()
    }
}
