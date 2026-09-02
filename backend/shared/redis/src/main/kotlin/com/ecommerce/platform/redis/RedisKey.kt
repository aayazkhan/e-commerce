package com.ecommerce.platform.redis

@JvmInline
value class RedisKey private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun scoped(tenantId: String, namespace: String, id: String): RedisKey {
            require(tenantId.isNotBlank()) { "Tenant ID must not be blank" }
            require(namespace.matches(Regex("[a-z0-9-]+"))) { "Namespace must be lowercase and URL-safe" }
            require(id.isNotBlank()) { "Redis key ID must not be blank" }
            return RedisKey("$namespace:$tenantId:$id")
        }
    }
}
