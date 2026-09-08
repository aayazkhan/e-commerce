package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.ecommerce.core.network.CatalogApi
import com.ecommerce.core.network.PriceQuote
import com.ecommerce.core.network.StorefrontProduct
import com.ecommerce.core.network.StorefrontVariant
import kotlinx.coroutines.launch

private sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val product: StorefrontProduct, val selectedVariant: StorefrontVariant?, val price: PriceQuote?) : DetailState
    data class Failed(val message: String) : DetailState
}

@Composable
fun ProductDetailScreen(
    productId: String,
    catalogApi: CatalogApi,
    onAddToCart: (variantId: String, quantity: Int) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<DetailState>(DetailState.Loading) }
    var quantity by remember { mutableStateOf(1) }
    var addStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadPrice(product: StorefrontProduct, variant: StorefrontVariant?) {
        val priceResult = catalogApi.price(product.id, variant?.id)
        val price = (priceResult as? ApiResult.Success)?.value
        state = DetailState.Loaded(product, variant, price)
    }

    LaunchedEffect(productId) {
        when (val result = catalogApi.get(productId)) {
            is ApiResult.Success -> {
                val product = result.value
                loadPrice(product, product.variants.firstOrNull())
            }
            is ApiResult.ApiError -> state = DetailState.Failed(result.body.message)
            is ApiResult.NetworkError -> state = DetailState.Failed("Couldn't reach the server. Is the backend running at localhost:8080?")
        }
    }

    when (val current = state) {
        is DetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is DetailState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, color = MaterialTheme.colorScheme.error)
        }
        is DetailState.Loaded -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton("Back", modifier = Modifier.width(120.dp)) { onBack() }
            Text(current.product.name, style = MaterialTheme.typography.headlineMedium)
            current.price?.let {
                Text("${formatMinor(it.baseMinor)} ${it.currency}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(current.product.description, style = MaterialTheme.typography.bodyMedium)

            if (current.product.variants.size > 1) {
                Text("Variant", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.product.variants.forEach { variant ->
                        FilterChip(
                            selected = current.selectedVariant?.id == variant.id,
                            onClick = { scope.launch { loadPrice(current.product, variant) } },
                            label = { Text(variant.attributes.values.firstOrNull() ?: variant.sku) },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("-", modifier = Modifier.width(48.dp)) { if (quantity > 1) quantity -= 1 }
                Text("$quantity", style = MaterialTheme.typography.titleMedium)
                SecondaryButton("+", modifier = Modifier.width(48.dp)) { quantity += 1 }
            }

            val variant = current.selectedVariant
            PrimaryButton("Add to cart", enabled = variant != null) {
                variant?.let {
                    onAddToCart(it.id, quantity)
                    addStatus = "Added to cart."
                }
            }
            addStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
