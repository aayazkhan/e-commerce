package com.ecommerce.storefront.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import com.ecommerce.core.network.Category
import com.ecommerce.core.network.CatalogApi
import com.ecommerce.core.network.CategoryApi
import com.ecommerce.core.network.StorefrontProduct
import kotlinx.coroutines.launch

private sealed interface BrowseState {
    data object Loading : BrowseState
    data class Loaded(val categories: List<Category>, val products: List<StorefrontProduct>) : BrowseState
    data class Failed(val message: String) : BrowseState
}

@Composable
fun BrowseScreen(
    catalogApi: CatalogApi,
    categoryApi: CategoryApi,
    onOpenProduct: (String) -> Unit,
) {
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadProducts(categoryId: String?, categories: List<Category>) {
        when (val result = catalogApi.list(categoryId = categoryId)) {
            is ApiResult.Success -> state = BrowseState.Loaded(categories, result.value.items)
            is ApiResult.ApiError -> state = BrowseState.Failed(result.body.message)
            is ApiResult.NetworkError -> state = BrowseState.Failed("Couldn't reach the server. Is the backend running at localhost:8080?")
        }
    }

    LaunchedEffect(Unit) {
        val categories = when (val result = categoryApi.tree()) {
            is ApiResult.Success -> result.value
            else -> emptyList()
        }
        loadProducts(null, categories)
    }

    fun selectCategory(categoryId: String?) {
        selectedCategoryId = categoryId
        val current = state
        val categories = if (current is BrowseState.Loaded) current.categories else emptyList()
        scope.launch { loadProducts(categoryId, categories) }
    }

    when (val current = state) {
        is BrowseState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is BrowseState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, color = MaterialTheme.colorScheme.error)
        }
        is BrowseState.Loaded -> Column(Modifier.fillMaxSize()) {
            if (current.categories.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        FilterChip(selected = selectedCategoryId == null, onClick = { selectCategory(null) }, label = { Text("All") })
                    }
                    items(current.categories) { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectCategory(category.id) },
                            label = { Text(category.name) },
                        )
                    }
                }
            }
            if (current.products.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No products found.") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(current.products) { product ->
                        ProductCard(product, onClick = { onOpenProduct(product.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: StorefrontProduct, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            product.shortDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}
