package com.ecommerce.seller.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.ecommerce.core.network.Category
import com.ecommerce.core.network.CategoryApi
import com.ecommerce.core.network.SellerApi
import com.ecommerce.core.network.SellerProductRequest
import kotlinx.coroutines.launch

private fun slugify(name: String): String =
    name.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

@Composable
fun ProductFormScreen(
    sellerApi: SellerApi,
    categoryApi: CategoryApi,
    productId: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val isEditing = productId != null
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(isEditing) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var slugEditedByUser by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var shortDescription by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf("") }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    val status = "DRAFT"

    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(productId) {
        when (val result = categoryApi.tree()) {
            is ApiResult.Success -> categories = result.value
            else -> Unit // category picker degrades to a free-text id -- not fatal for the form
        }
        if (productId != null) {
            when (val result = sellerApi.getProduct(productId)) {
                is ApiResult.Success -> {
                    val product = result.value
                    name = product.name
                    slug = product.slug
                    slugEditedByUser = true
                    description = product.description
                    shortDescription = product.shortDescription.orEmpty()
                    categoryId = product.categoryId
                    loading = false
                }
                is ApiResult.ApiError -> { errorMessage = result.body.message; loading = false }
                is ApiResult.NetworkError -> { errorMessage = "Couldn't reach the server."; loading = false }
            }
        }
    }

    fun save() {
        if (saving || deleting) return
        saving = true
        errorMessage = null
        val request = SellerProductRequest(
            categoryId = categoryId,
            name = name,
            slug = slug,
            description = description,
            shortDescription = shortDescription.ifBlank { null },
            status = status,
        )
        scope.launch {
            val result = if (isEditing) sellerApi.updateProduct(productId!!, request) else sellerApi.createProduct(request)
            when (result) {
                is ApiResult.Success -> onSaved()
                is ApiResult.ApiError -> errorMessage = result.body.message
                is ApiResult.NetworkError -> errorMessage = "Couldn't reach the server."
            }
            saving = false
        }
    }

    fun delete() {
        if (saving || deleting || productId == null) return
        deleting = true
        errorMessage = null
        scope.launch {
            when (val result = sellerApi.deleteProduct(productId)) {
                is ApiResult.Success -> onSaved()
                is ApiResult.ApiError -> errorMessage = result.body.message
                is ApiResult.NetworkError -> errorMessage = "Couldn't reach the server."
            }
            deleting = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (isEditing) "Edit product" else "New product", style = MaterialTheme.typography.headlineMedium)

        if (loading) {
            CircularProgressIndicator()
        } else {
            LabeledTextField(
                label = "Name",
                value = name,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { value ->
                    name = value
                    if (!slugEditedByUser) slug = slugify(value)
                },
            )
            LabeledTextField(
                label = "Slug",
                value = slug,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { slugEditedByUser = true; slug = it },
            )

            if (categories.isEmpty()) {
                LabeledTextField(
                    label = "Category id",
                    value = categoryId,
                    modifier = Modifier.fillMaxWidth(),
                    onValueChange = { categoryId = it },
                )
                Text("No categories available yet -- enter a category id directly above.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    // A real picker, not free text: clicking the field expands an inline list
                    // below it. Not using DropdownMenu/Popup here -- it doesn't render in this
                    // Compose Web (wasmJs canvas) target, so an in-tree expandable list is used
                    // instead, which draws on the same canvas surface like everything else. This
                    // is a plain clickable Box, not a real text field -- an OutlinedTextField
                    // consumes its own clicks for focus handling, so a click never reaches a
                    // clickable Box wrapped around one.
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .clickable { categoryMenuOpen = !categoryMenuOpen }
                            .padding(16.dp),
                    ) {
                        Text(
                            categories.find { it.id == categoryId }?.name ?: "Category",
                            color = if (categoryId.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (categoryMenuOpen) {
                        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                            categories.forEach { category ->
                                Text(
                                    category.name,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { categoryId = category.id; categoryMenuOpen = false }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }

            LabeledTextField(
                label = "Short description",
                value = shortDescription,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { shortDescription = it },
            )
            LabeledTextField(
                label = "Description",
                value = description,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { description = it },
            )

            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = if (saving) "Saving..." else "Save",
                    enabled = !saving && !deleting && name.isNotBlank() && slug.isNotBlank() && categoryId.isNotBlank() && description.isNotBlank(),
                ) { save() }
                TextButton(onClick = onCancel, enabled = !saving && !deleting) { Text("Cancel") }
                if (isEditing) {
                    TextButton(onClick = { delete() }, enabled = !saving && !deleting) {
                        Text(if (deleting) "Deleting..." else "Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
