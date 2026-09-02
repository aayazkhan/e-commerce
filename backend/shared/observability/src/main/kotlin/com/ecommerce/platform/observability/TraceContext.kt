package com.ecommerce.platform.observability

import com.ecommerce.platform.common.RequestMetadata

data class TraceContext(
    val requestId: String,
    val traceId: String?,
    val spanId: String? = null,
)

fun TraceContext.toRequestMetadata(actorId: String? = null, tenantId: String? = null): RequestMetadata =
    RequestMetadata(
        requestId = requestId,
        traceId = traceId,
        actorId = actorId,
        tenantId = tenantId,
    )
