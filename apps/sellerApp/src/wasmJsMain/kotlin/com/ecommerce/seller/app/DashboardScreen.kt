package com.ecommerce.seller.app

import androidx.compose.foundation.clickable
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
import com.ecommerce.core.network.SellerProduct
import com.ecommerce.core.network.SellerProfile

private sealed interface ProfileState {
    data object Loading : ProfileState
    data class Loaded(val profile: SellerProfile) : ProfileState
    data object NoProfile : ProfileState
    data class Failed(val message: String) : ProfileState
}

private sealed interface ProductsState {
    data object Loading : ProductsState
    data class Loaded(val products: List<SellerProduct>) : ProductsState
    data class Failed(val message: String) : ProductsState
}

@Composable
fun DashboardScreen(
    sellerApi: SellerApi,
    refreshKey: Int,
    onNewProduct: () -> Unit,
    onEditProduct: (String) -> Unit,
) {
    var profileState by remember { mutableStateOf<ProfileState>(ProfileState.Loading) }
    var productsState by remember { mutableStateOf<ProductsState>(ProductsState.Loading) }

    LaunchedEffect(refreshKey) {
        productsState = ProductsState.Loading
        profileState = when (val result = sellerApi.getProfile()) {
            is ApiResult.Success -> ProfileState.Loaded(result.value)
            is ApiResult.ApiError -> if (result.status == 404) ProfileState.NoProfile else ProfileState.Failed(result.body.message)
            is ApiResult.NetworkError -> ProfileState.Failed("Couldn't reach the server.")
        }
        productsState = when (val result = sellerApi.listProducts()) {
            is ApiResult.Success -> ProductsState.Loaded(result.value.items)
            is ApiResult.ApiError -> ProductsState.Failed(result.body.message)
            is ApiResult.NetworkError -> ProductsState.Failed("Couldn't reach the server.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Dashboard", style = MaterialTheme.typography.headlineMedium)

        when (val state = profileState) {
            is ProfileState.Loading -> CircularProgressIndicator()
            is ProfileState.Loaded -> ProfileCard(state.profile)
            is ProfileState.NoProfile -> Text("You don't have a seller profile yet.")
            is ProfileState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        }

        Divider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Products", style = MaterialTheme.typography.titleLarge)
            PrimaryButton("New product", onClick = onNewProduct)
        }

        when (val state = productsState) {
            is ProductsState.Loading -> CircularProgressIndicator()
            is ProductsState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is ProductsState.Loaded -> if (state.products.isEmpty()) {
                Text("No products yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.products) { product -> ProductRow(product, onClick = { onEditProduct(product.id) }) }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: SellerProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
        Text(profile.legalName, style = MaterialTheme.typography.bodyMedium)
        Text("${profile.email} · ${profile.status}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProductRow(product: SellerProduct, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Column {
            Text(product.name, style = MaterialTheme.typography.bodyLarge)
            Text(product.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
