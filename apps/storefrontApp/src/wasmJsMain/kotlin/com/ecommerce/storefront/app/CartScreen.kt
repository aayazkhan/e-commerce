package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
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
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.CartItem
import com.ecommerce.core.network.CartResponse
import kotlinx.coroutines.launch

private sealed interface CartState {
    data object Loading : CartState
    data class Loaded(val cart: CartResponse) : CartState
    data class Failed(val message: String) : CartState
}

/** refreshKey bumps whenever a caller (e.g. ProductDetailScreen's "Add to cart") wants this
 * screen to reload the cart next time it's shown. */
@Composable
fun CartScreen(
    cartApi: CartApi,
    guestToken: String?,
    isAuthenticated: Boolean,
    refreshKey: Int,
    onGuestTokenIssued: (String) -> Unit,
    onCheckout: () -> Unit,
    onLoginRequired: () -> Unit,
) {
    var state by remember { mutableStateOf<CartState>(CartState.Loading) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        state = CartState.Loading
        when (val result = cartApi.get(guestToken)) {
            is ApiResult.Success -> {
                result.value.guestToken?.let { onGuestTokenIssued(it) }
                state = CartState.Loaded(result.value)
            }
            is ApiResult.ApiError -> state = CartState.Failed(result.body.message)
            is ApiResult.NetworkError -> state = CartState.Failed("Couldn't reach the server. Is the backend running at localhost:8080?")
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    fun changeQuantity(item: CartItem, newQuantity: Int) {
        scope.launch {
            val result = if (newQuantity <= 0) {
                cartApi.removeItem(item.id, guestToken, "web-${item.id}-remove-${kotlin.random.Random.nextInt()}")
            } else {
                cartApi.updateItem(item.id, newQuantity, guestToken, "web-${item.id}-update-${kotlin.random.Random.nextInt()}")
            }
            if (result is ApiResult.Success) state = CartState.Loaded(result.value)
        }
    }

    when (val current = state) {
        is CartState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is CartState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, color = MaterialTheme.colorScheme.error)
        }
        is CartState.Loaded -> {
            val cart = current.cart
            if (cart.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your cart is empty.") }
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Your cart", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.padding(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(cart.items) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.productId, style = MaterialTheme.typography.bodyLarge)
                                    Text("${formatMinor(item.unitPriceMinor)} ${item.currency} x ${item.quantity}", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton("-", modifier = Modifier.width(44.dp)) { changeQuantity(item, item.quantity - 1) }
                                    Text("${item.quantity}")
                                    SecondaryButton("+", modifier = Modifier.width(44.dp)) { changeQuantity(item, item.quantity + 1) }
                                }
                            }
                            Divider()
                        }
                    }
                    val subtotal = cart.items.sumOf { it.unitPriceMinor * it.quantity }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Subtotal", style = MaterialTheme.typography.titleMedium)
                        Text("${formatMinor(subtotal)} ${cart.currency}", style = MaterialTheme.typography.titleMedium)
                    }
                    PrimaryButton("Checkout", modifier = Modifier.fillMaxWidth()) {
                        if (isAuthenticated) onCheckout() else onLoginRequired()
                    }
                }
            }
        }
    }
}
