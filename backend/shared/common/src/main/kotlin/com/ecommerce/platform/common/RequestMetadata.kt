package com.ecommerce.platform.common

data class RequestMetadata(
    val requestId: String,
    val traceId: String?,
    val actorId: String? = null,
    val tenantId: String? = null,
)
