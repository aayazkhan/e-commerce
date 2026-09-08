package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.AddressRequest
import com.ecommerce.core.network.AddressResponse
import com.ecommerce.core.network.CheckoutApi
import com.ecommerce.core.network.CheckoutRequest
import kotlinx.coroutines.launch

private sealed interface CheckoutState {
    data object LoadingAddresses : CheckoutState
    data class PickingAddress(val addresses: List<AddressResponse>, val addingNew: Boolean) : CheckoutState
    data object Placing : CheckoutState
    data class Placed(val checkoutId: String, val status: String, val orderId: String?, val error: String?) : CheckoutState
    data class Failed(val message: String) : CheckoutState
}

@Composable
fun CheckoutScreen(
    addressApi: AddressApi,
    checkoutApi: CheckoutApi,
    onOrderPlaced: (checkoutId: String) -> Unit,
) {
    var state by remember { mutableStateOf<CheckoutState>(CheckoutState.LoadingAddresses) }
    var selectedAddressId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadAddresses() {
        when (val result = addressApi.list()) {
            is ApiResult.Success -> {
                selectedAddressId = result.value.firstOrNull { it.isDefault }?.id ?: result.value.firstOrNull()?.id
                state = CheckoutState.PickingAddress(result.value, addingNew = result.value.isEmpty())
            }
            is ApiResult.ApiError -> state = CheckoutState.Failed(result.body.message)
            is ApiResult.NetworkError -> state = CheckoutState.Failed("Couldn't reach the server. Is the backend running at localhost:8080?")
        }
    }

    LaunchedEffect(Unit) { loadAddresses() }

    fun placeOrder(addressId: String) {
        state = CheckoutState.Placing
        scope.launch {
            val request = CheckoutRequest(
                shippingAddressId = addressId,
                paymentMethodToken = "cod",
                paymentProvider = "COD",
            )
            when (val result = checkoutApi.start(request, "web-checkout-${kotlin.random.Random.nextInt()}")) {
                is ApiResult.Success -> {
                    val response = result.value
                    state = CheckoutState.Placed(response.checkoutId, response.status, response.orderId, response.error)
                    if (response.status == "COMPLETED") onOrderPlaced(response.checkoutId)
                }
                is ApiResult.ApiError -> state = CheckoutState.Failed(result.body.message)
                is ApiResult.NetworkError -> state = CheckoutState.Failed("Couldn't reach the server. Is the backend running at localhost:8080?")
            }
        }
    }

    when (val current = state) {
        is CheckoutState.LoadingAddresses, is CheckoutState.Placing ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is CheckoutState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, color = MaterialTheme.colorScheme.error)
        }
        is CheckoutState.Placed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.status == "COMPLETED") {
                    Text("Order placed!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Order ${current.orderId} -- payment on delivery.")
                } else {
                    Text("Checkout could not complete", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                    Text(current.error ?: "Please try again.")
                }
            }
        }
        is CheckoutState.PickingAddress -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Shipping address", style = MaterialTheme.typography.headlineMedium)
            if (current.addingNew || current.addresses.isEmpty()) {
                NewAddressForm(
                    onSave = { request ->
                        scope.launch {
                            when (val result = addressApi.create(request)) {
                                is ApiResult.Success -> {
                                    selectedAddressId = result.value.id
                                    loadAddresses()
                                }
                                is ApiResult.ApiError -> state = CheckoutState.Failed(result.body.message)
                                is ApiResult.NetworkError -> state = CheckoutState.Failed("Couldn't reach the server.")
                            }
                        }
                    },
                )
            } else {
                current.addresses.forEach { address ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedAddressId == address.id, onClick = { selectedAddressId = address.id })
                            Column {
                                Text("${address.recipientName} -- ${address.label}", style = MaterialTheme.typography.bodyLarge)
                                Text("${address.line1}, ${address.city}, ${address.state} ${address.postalCode}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                SecondaryButton("Add a new address", modifier = Modifier.fillMaxWidth()) {
                    state = current.copy(addingNew = true)
                }
                PrimaryButton("Place order (Cash on delivery)", enabled = selectedAddressId != null, modifier = Modifier.fillMaxWidth()) {
                    selectedAddressId?.let { placeOrder(it) }
                }
            }
        }
    }
}

@Composable
private fun NewAddressForm(onSave: (AddressRequest) -> Unit) {
    var recipientName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var line1 by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var postalCode by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("IN") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledTextField("Recipient name", recipientName, modifier = Modifier.fillMaxWidth()) { recipientName = it }
        LabeledTextField("Phone", phone, modifier = Modifier.fillMaxWidth()) { phone = it }
        LabeledTextField("Address line 1", line1, modifier = Modifier.fillMaxWidth()) { line1 = it }
        LabeledTextField("City", city, modifier = Modifier.fillMaxWidth()) { city = it }
        LabeledTextField("State", state, modifier = Modifier.fillMaxWidth()) { state = it }
        LabeledTextField("Postal code", postalCode, modifier = Modifier.fillMaxWidth()) { postalCode = it }
        LabeledTextField("Country", country, modifier = Modifier.fillMaxWidth()) { country = it }
        PrimaryButton(
            "Save address",
            enabled = recipientName.isNotBlank() && phone.isNotBlank() && line1.isNotBlank() && city.isNotBlank() && state.isNotBlank() && postalCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onSave(
                AddressRequest(
                    label = "Home",
                    recipientName = recipientName,
                    phone = phone,
                    line1 = line1,
                    city = city,
                    state = state,
                    postalCode = postalCode,
                    country = country,
                    isDefault = true,
                ),
            )
        }
    }
}
