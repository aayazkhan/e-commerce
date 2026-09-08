package com.ecommerce.admin.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.network.AdminApi
import com.ecommerce.core.network.SellerProduct
import com.ecommerce.core.network.SellerProfile
import kotlinx.coroutines.launch

private sealed interface SellersState {
    data object Loading : SellersState
    data class Loaded(val sellers: List<SellerProfile>) : SellersState
    data class Failed(val message: String) : SellersState
}

private sealed interface ProductsState {
    data object Loading : ProductsState
    data class Loaded(val products: List<SellerProduct>) : ProductsState
    data class Failed(val message: String) : ProductsState
}

/** The next forward transition in the seller approval pipeline, or null if none applies here. */
private fun nextApprovalStatus(current: String): String? = when (current) {
    "PENDING" -> "UNDER_REVIEW"
    "UNDER_REVIEW" -> "VERIFIED"
    "VERIFIED" -> "ACTIVE"
    else -> null
}

private fun canReject(current: String): Boolean = current in setOf("PENDING", "UNDER_REVIEW")

@Composable
fun DashboardScreen(adminApi: AdminApi, refreshKey: Int) {
    var sellersState by remember { mutableStateOf<SellersState>(SellersState.Loading) }
    var productsState by remember { mutableStateOf<ProductsState>(ProductsState.Loading) }
    var actionInFlight by remember { mutableStateOf<String?>(null) }
    var localRefresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey, localRefresh) {
        sellersState = SellersState.Loading
        productsState = ProductsState.Loading
        sellersState = when (val result = adminApi.listSellers()) {
            is ApiResult.Success -> SellersState.Loaded(result.value)
            is ApiResult.ApiError -> SellersState.Failed(result.body.message)
            is ApiResult.NetworkError -> SellersState.Failed("Couldn't reach the server.")
        }
        productsState = when (val result = adminApi.listProducts()) {
            is ApiResult.Success -> ProductsState.Loaded(result.value.items)
            is ApiResult.ApiError -> ProductsState.Failed(result.body.message)
            is ApiResult.NetworkError -> ProductsState.Failed("Couldn't reach the server.")
        }
    }

    fun transitionSeller(id: String, status: String) {
        if (actionInFlight != null) return
        actionInFlight = id
        scope.launch {
            adminApi.transitionSeller(id, status)
            actionInFlight = null
            localRefresh += 1
        }
    }

    fun publishProduct(id: String) {
        if (actionInFlight != null) return
        actionInFlight = id
        scope.launch {
            adminApi.publishProduct(id)
            actionInFlight = null
            localRefresh += 1
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Admin Console", style = MaterialTheme.typography.headlineMedium)

        Text("Sellers", style = MaterialTheme.typography.titleLarge)
        when (val state = sellersState) {
            is SellersState.Loading -> CircularProgressIndicator()
            is SellersState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is SellersState.Loaded -> if (state.sellers.isEmpty()) {
                Text("No sellers yet.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.sellers) { seller ->
                        SellerRow(
                            seller = seller,
                            busy = actionInFlight == seller.id,
                            onApprove = nextApprovalStatus(seller.status)?.let { next -> { transitionSeller(seller.id, next) } },
                            onReject = if (canReject(seller.status)) ({ transitionSeller(seller.id, "REJECTED") }) else null,
                        )
                    }
                }
            }
        }

        Divider()
        Text("Products", style = MaterialTheme.typography.titleLarge)
        when (val state = productsState) {
            is ProductsState.Loading -> CircularProgressIndicator()
            is ProductsState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is ProductsState.Loaded -> if (state.products.isEmpty()) {
                Text("No products yet.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.products) { product ->
                        ProductRow(
                            product = product,
                            busy = actionInFlight == product.id,
                            onPublish = if (product.status in setOf("DRAFT", "REVIEW", "INACTIVE")) ({ publishProduct(product.id) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SellerRow(seller: SellerProfile, busy: Boolean, onApprove: (() -> Unit)?, onReject: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(seller.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(seller.status, style = MaterialTheme.typography.bodySmall)
        }
        Text("${seller.legalName} · ${seller.email}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onApprove?.let { TextButton(onClick = it, enabled = !busy) { Text(if (busy) "Working..." else "Approve") } }
            onReject?.let { TextButton(onClick = it, enabled = !busy) { Text("Reject", color = MaterialTheme.colorScheme.error) } }
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ProductRow(product: SellerProduct, busy: Boolean, onPublish: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(product.name, style = MaterialTheme.typography.bodyLarge)
            Text(product.status, style = MaterialTheme.typography.bodySmall)
        }
        onPublish?.let { TextButton(onClick = it, enabled = !busy) { Text(if (busy) "Working..." else "Publish") } }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}
