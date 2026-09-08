package com.ecommerce.seller.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("ComposeTarget") {
        SellerAppTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val dependencies = remember { AppDependencies() }
                var screen by remember { mutableStateOf<Screen>(Screen.Login) }
                var dashboardRefreshKey by remember { mutableStateOf(0) }

                when (val current = screen) {
                    is Screen.Login -> LoginScreen(dependencies.authApi) { accessToken ->
                        dependencies.session.set(accessToken)
                        screen = Screen.Dashboard
                    }
                    is Screen.Dashboard -> DashboardScreen(
                        sellerApi = dependencies.sellerApi,
                        refreshKey = dashboardRefreshKey,
                        onNewProduct = { screen = Screen.ProductForm(productId = null) },
                        onEditProduct = { productId -> screen = Screen.ProductForm(productId) },
                        onViewOrders = { screen = Screen.Orders },
                    )
                    is Screen.ProductForm -> ProductFormScreen(
                        sellerApi = dependencies.sellerApi,
                        categoryApi = dependencies.categoryApi,
                        productId = current.productId,
                        onSaved = {
                            dashboardRefreshKey += 1
                            screen = Screen.Dashboard
                        },
                        onCancel = { screen = Screen.Dashboard },
                    )
                    is Screen.Orders -> OrdersScreen(
                        sellerApi = dependencies.sellerApi,
                        onBack = { screen = Screen.Dashboard },
                    )
                }
            }
        }
    }
}
