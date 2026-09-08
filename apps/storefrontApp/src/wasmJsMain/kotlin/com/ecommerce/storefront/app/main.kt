package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.ecommerce.core.common.ApiResult
import kotlinx.coroutines.launch

private const val ACCESS_TOKEN_STORAGE_KEY = "storefront_access_token"

/** Parses the small, fixed set of query params this app ever receives (from PayU's callback
 * redirect -- see PaymentReturnScreen.kt). Not a general-purpose URL parser. */
private fun parseQueryParams(search: String): Map<String, String> {
    if (search.isBlank() || search == "?") return emptyMap()
    return search.removePrefix("?").split("&").mapNotNull { pair ->
        val parts = pair.split("=", limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) null else parts[0] to parts[1]
    }.toMap()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("ComposeTarget") {
        StorefrontTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val dependencies = remember { AppDependencies() }
                val queryParams = remember { parseQueryParams(currentLocationSearch()) }
                val restoredToken = remember { localStorageGet(ACCESS_TOKEN_STORAGE_KEY).takeIf { it.isNotBlank() } }
                val paymentReturn = remember { queryParams["checkoutId"]?.let { id -> Screen.PaymentReturn(id, queryParams["payment"] ?: "invalid") } }
                var screen by remember { mutableStateOf<Screen>(paymentReturn ?: Screen.Browse) }
                var isAuthenticated by remember { mutableStateOf(restoredToken != null) }
                var guestToken by remember { mutableStateOf<String?>(null) }
                var cartRefreshKey by remember { mutableStateOf(0) }
                var pendingAfterLogin by remember { mutableStateOf<Screen?>(null) }
                val scope = rememberCoroutineScope()

                remember {
                    restoredToken?.let { dependencies.session.set(it) }
                    if (paymentReturn != null) clearLocationSearch()
                    true
                }

                fun addToCart(variantId: String, productId: String, quantity: Int) {
                    scope.launch {
                        val result = dependencies.cartApi.addItem(
                            productId = productId,
                            variantId = variantId,
                            quantity = quantity,
                            guestToken = guestToken,
                            idempotencyKey = "web-add-${kotlin.random.Random.nextInt()}",
                        )
                        if (result is ApiResult.Success) {
                            result.value.guestToken?.let { guestToken = it }
                            cartRefreshKey += 1
                        }
                    }
                }

                fun completeLogin(accessToken: String) {
                    dependencies.session.set(accessToken)
                    localStorageSet(ACCESS_TOKEN_STORAGE_KEY, accessToken)
                    isAuthenticated = true
                    val token = guestToken
                    scope.launch {
                        if (token != null) {
                            dependencies.cartApi.merge(token)
                            guestToken = null
                        }
                        cartRefreshKey += 1
                    }
                    screen = pendingAfterLogin ?: Screen.Browse
                    pendingAfterLogin = null
                }

                fun requireLogin(target: Screen) {
                    pendingAfterLogin = target
                    screen = Screen.Login
                }

                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        isAuthenticated = isAuthenticated,
                        onBrowse = { screen = Screen.Browse },
                        onCart = { screen = Screen.Cart },
                        onLoginOrAccount = { if (isAuthenticated) Unit else screen = Screen.Login },
                        onLogout = {
                            dependencies.session.clear()
                            localStorageRemove(ACCESS_TOKEN_STORAGE_KEY)
                            isAuthenticated = false
                            screen = Screen.Browse
                        },
                    )
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        when (val current = screen) {
                            is Screen.Browse -> BrowseScreen(
                                catalogApi = dependencies.catalogApi,
                                categoryApi = dependencies.categoryApi,
                                onOpenProduct = { productId -> screen = Screen.ProductDetail(productId) },
                            )
                            is Screen.ProductDetail -> ProductDetailScreen(
                                productId = current.productId,
                                catalogApi = dependencies.catalogApi,
                                onAddToCart = { variantId, quantity -> addToCart(variantId, current.productId, quantity) },
                                onBack = { screen = Screen.Browse },
                            )
                            is Screen.Cart -> CartScreen(
                                cartApi = dependencies.cartApi,
                                guestToken = guestToken,
                                isAuthenticated = isAuthenticated,
                                refreshKey = cartRefreshKey,
                                onGuestTokenIssued = { guestToken = it },
                                onCheckout = { screen = Screen.Checkout },
                                onLoginRequired = { requireLogin(Screen.Checkout) },
                            )
                            is Screen.Login -> StorefrontLoginScreen(
                                authApi = dependencies.authApi,
                                onLoggedIn = { token -> completeLogin(token) },
                                onGoToRegister = { screen = Screen.Register },
                                onBack = { screen = Screen.Browse },
                            )
                            is Screen.Register -> RegisterScreen(
                                authApi = dependencies.authApi,
                                onRegistered = { screen = Screen.Login },
                                onGoToLogin = { screen = Screen.Login },
                            )
                            is Screen.Checkout -> CheckoutScreen(
                                addressApi = dependencies.addressApi,
                                checkoutApi = dependencies.checkoutApi,
                                onOrderPlaced = { checkoutId -> screen = Screen.OrderConfirmation(checkoutId) },
                            )
                            is Screen.OrderConfirmation -> OrderConfirmationScreen(
                                checkoutId = current.checkoutId,
                                onContinueShopping = {
                                    cartRefreshKey += 1
                                    screen = Screen.Browse
                                },
                            )
                            is Screen.PaymentReturn -> PaymentReturnScreen(
                                checkoutId = current.checkoutId,
                                outcome = current.outcome,
                                checkoutApi = dependencies.checkoutApi,
                                onContinueShopping = {
                                    cartRefreshKey += 1
                                    screen = Screen.Browse
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TopBar(
    isAuthenticated: Boolean,
    onBrowse: () -> Unit,
    onCart: () -> Unit,
    onLoginOrAccount: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBrowse) { Text("Storefront", style = MaterialTheme.typography.titleLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCart) { Text("Cart") }
            if (isAuthenticated) {
                TextButton(onClick = onLogout) { Text("Log out") }
            } else {
                TextButton(onClick = onLoginOrAccount) { Text("Log in") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun OrderConfirmationScreen(checkoutId: String, onContinueShopping: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Thanks for your order!", style = MaterialTheme.typography.headlineMedium)
        Text("Checkout $checkoutId")
        PrimaryButton("Continue shopping") { onContinueShopping() }
    }
}
