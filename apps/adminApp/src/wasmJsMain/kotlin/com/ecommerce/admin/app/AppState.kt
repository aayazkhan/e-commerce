package com.ecommerce.admin.app

import com.ecommerce.core.network.AdminApi
import com.ecommerce.core.network.ApiClient
import com.ecommerce.core.network.AuthApi
import com.ecommerce.core.network.SessionHolder
import com.ecommerce.core.network.createHttpClient
import io.ktor.client.engine.js.Js

/** Gateway base URL -- the api-gateway's own port, never a service's port directly. */
private const val GATEWAY_BASE_URL = "http://localhost:8080"

class AppDependencies {
    val session = SessionHolder()
    private val httpClient = createHttpClient(Js.create())
    private val client = ApiClient(httpClient, GATEWAY_BASE_URL, session)
    val authApi = AuthApi(client)
    val adminApi = AdminApi(client)
}

sealed interface Screen {
    data object Login : Screen
    data object Dashboard : Screen
}
