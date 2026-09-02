package com.ecommerce.platform.observability

import com.ecommerce.platform.common.RequestMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceContextTest {
    @Test
    fun `trace context maps to request metadata without losing correlation`() {
        val metadata = TraceContext("req-1", "trace-1", "span-1").toRequestMetadata("user-1", "tenant-1")

        assertEquals("req-1", metadata.requestId)
        assertEquals("trace-1", metadata.traceId)
        assertEquals("user-1", metadata.actorId)
        assertEquals("tenant-1", metadata.tenantId)

        val minimal = TraceContext("req-2", null).toRequestMetadata()
        assertEquals(RequestMetadata("req-2", null), minimal)
        assertEquals("req-2", minimal.requestId)
        assertEquals(null, minimal.traceId)
        assertEquals(null, minimal.actorId)
        assertEquals(null, minimal.tenantId)
    }
}
