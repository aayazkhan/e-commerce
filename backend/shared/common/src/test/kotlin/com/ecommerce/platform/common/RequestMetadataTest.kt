package com.ecommerce.platform.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestMetadataTest {
    @Test
    fun `request metadata preserves required and optional context`() {
        val sparse = RequestMetadata(requestId = "request-1", traceId = null)
        assertEquals("request-1", sparse.requestId)
        assertNull(sparse.traceId)
        assertNull(sparse.actorId)
        assertNull(sparse.tenantId)

        val complete = RequestMetadata(
            requestId = "request-2",
            traceId = "trace-2",
            actorId = "actor-2",
            tenantId = "tenant-2",
        )
        assertEquals("request-2", complete.requestId)
        assertEquals("trace-2", complete.traceId)
        assertEquals("actor-2", complete.actorId)
        assertEquals("tenant-2", complete.tenantId)
    }
}
