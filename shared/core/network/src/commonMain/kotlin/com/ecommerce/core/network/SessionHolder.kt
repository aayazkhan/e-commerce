package com.ecommerce.core.network

/**
 * In-memory only for this first slice -- lost on refresh. Persistent/secure storage is
 * shared/core/security, explicitly deferred (see the frontend bootstrap plan).
 */
class SessionHolder {
    var accessToken: String? = null
        private set

    val isAuthenticated: Boolean get() = accessToken != null

    fun set(accessToken: String) {
        this.accessToken = accessToken
    }

    fun clear() {
        accessToken = null
    }
}
