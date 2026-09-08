package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.network.AuthApi
import kotlinx.coroutines.launch

@Composable
fun StorefrontLoginScreen(authApi: AuthApi, onLoggedIn: (accessToken: String) -> Unit, onGoToRegister: () -> Unit, onBack: () -> Unit) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (identifier.isBlank() || password.isBlank() || isSubmitting) return
        isSubmitting = true
        errorMessage = null
        scope.launch {
            when (val result = authApi.login(identifier.trim(), password)) {
                is ApiResult.Success -> onLoggedIn(result.value.accessToken)
                is ApiResult.ApiError -> errorMessage = result.body.message
                is ApiResult.NetworkError -> errorMessage = "Couldn't reach the server. Is the backend running at localhost:8080?"
            }
            isSubmitting = false
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(360.dp).padding(24.dp),
        ) {
            Text("Log in", style = MaterialTheme.typography.headlineMedium)
            LabeledTextField("Email", identifier, modifier = Modifier.fillMaxWidth()) { identifier = it }
            LabeledTextField("Password", password, isPassword = true, modifier = Modifier.fillMaxWidth()) { password = it }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (isSubmitting) {
                CircularProgressIndicator()
            } else {
                PrimaryButton("Log in", modifier = Modifier.fillMaxWidth()) { submit() }
            }
            TextButton(onClick = onGoToRegister) { Text("Need an account? Register") }
            TextButton(onClick = onBack) { Text("Back to browsing") }
        }
    }
}
