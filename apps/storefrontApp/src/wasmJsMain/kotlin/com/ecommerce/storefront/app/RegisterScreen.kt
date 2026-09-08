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
fun RegisterScreen(authApi: AuthApi, onRegistered: () -> Unit, onGoToLogin: () -> Unit) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (firstName.isBlank() || lastName.isBlank() || email.isBlank() || password.isBlank() || isSubmitting) return
        isSubmitting = true
        errorMessage = null
        scope.launch {
            when (val result = authApi.register(email.trim(), password, firstName.trim(), lastName.trim())) {
                is ApiResult.Success -> successMessage = "Account created. Check your email to verify it, then log in."
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
            Text("Create account", style = MaterialTheme.typography.headlineMedium)
            if (successMessage != null) {
                Text(successMessage!!, color = MaterialTheme.colorScheme.primary)
                PrimaryButton("Go to login", modifier = Modifier.fillMaxWidth()) { onRegistered() }
            } else {
                LabeledTextField("First name", firstName, modifier = Modifier.fillMaxWidth()) { firstName = it }
                LabeledTextField("Last name", lastName, modifier = Modifier.fillMaxWidth()) { lastName = it }
                LabeledTextField("Email", email, modifier = Modifier.fillMaxWidth()) { email = it }
                LabeledTextField("Password", password, isPassword = true, modifier = Modifier.fillMaxWidth()) { password = it }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (isSubmitting) {
                    CircularProgressIndicator()
                } else {
                    PrimaryButton("Create account", modifier = Modifier.fillMaxWidth()) { submit() }
                }
                TextButton(onClick = onGoToLogin) { Text("Already have an account? Log in") }
            }
        }
    }
}
