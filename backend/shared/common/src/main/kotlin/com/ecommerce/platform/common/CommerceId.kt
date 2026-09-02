package com.ecommerce.platform.common

import java.util.UUID

@JvmInline
value class CommerceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Commerce ID must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun new(prefix: String): CommerceId = CommerceId("${prefix}_${UUID.randomUUID()}")
    }
}
