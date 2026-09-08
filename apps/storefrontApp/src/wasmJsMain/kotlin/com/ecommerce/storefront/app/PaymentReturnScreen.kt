package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.network.CheckoutApi
import com.ecommerce.core.network.CheckoutRequest
import kotlinx.serialization.json.Json

private sealed interface ReturnState {
    data object Finalizing : ReturnState
    data class Done(val status: String, val orderId: String?, val error: String?) : ReturnState
}

/**
 * Lands here right after the browser round-trips through PayU's hosted checkout page. PayU's
 * callback already updated the payment status server-side (see payment-service's
 * PayuPaymentProvider callback route); this screen's job is just to call checkout's retry
 * endpoint with the SAME idempotency key the original checkout used, so the saga picks up where
 * it left off (order confirmation, inventory commit, shipment creation) now that the payment is
 * actually settled.
 */
@Composable
fun PaymentReturnScreen(checkoutId: String, outcome: String, checkoutApi: CheckoutApi, onContinueShopping: () -> Unit) {
    var state by remember { mutableStateOf<ReturnState>(ReturnState.Finalizing) }

    LaunchedEffect(checkoutId) {
        val pendingJson = localStorageGet(pendingPayuKey(checkoutId))
        localStorageRemove(pendingPayuKey(checkoutId))
        if (outcome != "success" || pendingJson.isBlank()) {
            state = ReturnState.Done("FAILED", null, "The payment was not completed.")
            return@LaunchedEffect
        }
        val pending = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<PendingPayuCheckout>(pendingJson) }.getOrNull()
        if (pending == null) {
            state = ReturnState.Done("FAILED", null, "Could not find this checkout's details locally.")
            return@LaunchedEffect
        }
        val request = CheckoutRequest(shippingAddressId = pending.addressId, paymentMethodToken = "payu", paymentProvider = "PAYU")
        when (val result = checkoutApi.retry(checkoutId, request, pending.idempotencyKey)) {
            is ApiResult.Success -> state = ReturnState.Done(result.value.status, result.value.orderId, result.value.error)
            is ApiResult.ApiError -> state = ReturnState.Done("FAILED", null, result.body.message)
            is ApiResult.NetworkError -> state = ReturnState.Done("FAILED", null, "Couldn't reach the server. Is the backend running at localhost:8080?")
        }
    }

    when (val current = state) {
        is ReturnState.Finalizing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Confirming your PayU payment...")
            }
        }
        is ReturnState.Done -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.status == "COMPLETED") {
                    Text("Payment successful!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Order ${current.orderId}")
                } else {
                    Text("Payment could not be confirmed", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                    Text(current.error ?: "Please try again or contact support.")
                }
                PrimaryButton("Continue shopping") { onContinueShopping() }
            }
        }
    }
}
