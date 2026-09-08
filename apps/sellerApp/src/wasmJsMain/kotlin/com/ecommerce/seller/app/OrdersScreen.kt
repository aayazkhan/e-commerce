package com.ecommerce.seller.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.network.SellerApi
import com.ecommerce.core.network.SellerOrderItem

private sealed interface OrdersState {
    data object Loading : OrdersState
    data class Loaded(val orders: List<SellerOrderItem>) : OrdersState
    data class Failed(val message: String) : OrdersState
}

/** Minor-unit amount (e.g. cents) to a "1,234.56"-style display string, no currency symbol lookup. */
private fun formatMinor(amountMinor: Long): String {
    val negative = amountMinor < 0
    val abs = kotlin.math.abs(amountMinor)
    val whole = abs / 100
    val fraction = (abs % 100).toString().padStart(2, '0')
    return (if (negative) "-" else "") + "$whole.$fraction"
}

@Composable
fun OrdersScreen(sellerApi: SellerApi, onBack: () -> Unit) {
    var state by remember { mutableStateOf<OrdersState>(OrdersState.Loading) }

    LaunchedEffect(Unit) {
        state = when (val result = sellerApi.listOrders()) {
            is ApiResult.Success -> OrdersState.Loaded(result.value)
            is ApiResult.ApiError -> OrdersState.Failed(result.body.message)
            is ApiResult.NetworkError -> OrdersState.Failed("Couldn't reach the server.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Orders", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }

        when (val current = state) {
            is OrdersState.Loading -> CircularProgressIndicator()
            is OrdersState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
            is OrdersState.Loaded -> if (current.orders.isEmpty()) {
                Text("No orders yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(current.orders) { order -> OrderRow(order) }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: SellerOrderItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Order ${order.orderId}", style = MaterialTheme.typography.bodyLarge)
            Text("${formatMinor(order.lineTotalMinor)} ${order.currency}", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "Qty ${order.quantity} · ${order.status ?: "UNKNOWN"} · ${order.occurredAt}",
            style = MaterialTheme.typography.bodySmall,
        )
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}
