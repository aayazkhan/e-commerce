package com.ecommerce.storefront.app

import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.ApiClient
import com.ecommerce.core.network.AuthApi
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.CatalogApi
import com.ecommerce.core.network.CategoryApi
import com.ecommerce.core.network.CheckoutApi
import com.ecommerce.core.network.SessionHolder
import com.ecommerce.core.network.createHttpClient
import io.ktor.client.engine.js.Js

/** Gateway base URL -- the api-gateway's own port, never a service's port directly. */
private const val GATEWAY_BASE_URL = "http://localhost:8080"

class AppDependencies {
    val session = SessionHolder()

    /** In-memory only, matching SessionHolder -- lost on refresh (see its doc comment). Guest
     * browsing/cart works without login; this is the anonymous cart's identity until the user
     * logs in and the guest cart is merged. */
    var guestToken: String? = null

    private val httpClient = createHttpClient(Js.create())
    private val client = ApiClient(httpClient, GATEWAY_BASE_URL, session)
    val authApi = AuthApi(client)
    val catalogApi = CatalogApi(client)
    val categoryApi = CategoryApi(client)
    val cartApi = CartApi(client)
    val addressApi = AddressApi(client)
    val checkoutApi = CheckoutApi(client)
}

sealed interface Screen {
    data object Browse : Screen
    data class ProductDetail(val productId: String) : Screen
    data object Cart : Screen
    data object Login : Screen
    data object Register : Screen
    data object Checkout : Screen
    data class OrderConfirmation(val checkoutId: String) : Screen
}
