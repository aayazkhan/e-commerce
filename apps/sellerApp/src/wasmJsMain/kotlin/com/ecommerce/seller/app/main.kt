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

                when (screen) {
                    is Screen.Login -> LoginScreen(dependencies.authApi) { accessToken ->
                        dependencies.session.set(accessToken)
                        screen = Screen.Dashboard
                    }
                    is Screen.Dashboard -> DashboardScreen(dependencies.sellerApi)
                }
            }
        }
    }
}
