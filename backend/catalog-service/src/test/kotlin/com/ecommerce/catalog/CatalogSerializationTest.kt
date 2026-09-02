package com.ecommerce.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `product variants media and event payload round trip`() {
        val variant = ProductVariant("variant-1", "product-1", "SKU-1", "BAR-1", mapOf("size" to "9"), "price-1", 500, mapOf("length" to "30cm"), VariantStatus.ACTIVE)
        val media = ProductMedia("media-1", "IMAGE", "https://cdn.test/product.jpg", 1, "Product image")
        ProductStatus.entries.forEach { status ->
            val product = Product("product-1", "seller-1", OwnerType.SELLER, "brand-1", "category-1", "Running Shoes", "running-shoes", "A comfortable shoe", "Comfortable", "SKU-REF", status, "GST18", mapOf("color" to "blue"), "Running shoes", "Buy shoes", "https://shop.test/running-shoes", 2, "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z", listOf(variant), listOf(media))
            assertEquals(product, json.decodeFromString<Product>(json.encodeToString(product)))
        }
        val payload = ProductEventPayload("product-1", "running-shoes", "category-1", "ACTIVE", 2, "Running Shoes", "A comfortable shoe", "Comfortable", "brand-1", mapOf("color" to "blue"), listOf(media))
        assertEquals(payload, json.decodeFromString<ProductEventPayload>(json.encodeToString(payload)))
    }

    @Test
    fun `compact catalog serialization preserves nullable media and default collections`() {
        val variant = ProductVariant("variant-1", "product-1", "SKU-1", null, emptyMap(), null, null, emptyMap(), VariantStatus.ACTIVE)
        val media = ProductMedia("media-1", "IMAGE", null, 0, null)
        val payload = ProductEventPayload("product-1", "product", "category-1", "DRAFT", 1)
        assertEquals(variant, compactJson.decodeFromString(ProductVariant.serializer(), compactJson.encodeToString(ProductVariant.serializer(), variant)))
        assertEquals(media, compactJson.decodeFromString(ProductMedia.serializer(), compactJson.encodeToString(ProductMedia.serializer(), media)))
        assertEquals(payload, compactJson.decodeFromString(ProductEventPayload.serializer(), compactJson.encodeToString(ProductEventPayload.serializer(), payload)))
        listOf(
            payload.copy(name = "Shoe"),
            payload.copy(description = "A shoe"),
            payload.copy(shortDescription = "Short"),
            payload.copy(brandId = "brand-1"),
            payload.copy(attributes = mapOf("color" to "blue")),
            payload.copy(media = listOf(media)),
            payload.copy(name = "Shoe", description = "A shoe", shortDescription = "Short", brandId = "brand-1", attributes = mapOf("color" to "blue"), media = listOf(media)),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(ProductEventPayload.serializer(), compactJson.encodeToString(ProductEventPayload.serializer(), value)))
        }
    }

    @Test
    fun `compact product payload preserves nullable fields and default collections`() {
        val sparse = compactJson.decodeFromString<Product>(
            """{"id":"product-1","sellerId":"seller-1","ownerType":"SELLER","brandId":null,"categoryId":"category-1","name":"Shoe","slug":"shoe","description":"A shoe","shortDescription":null,"skuReference":null,"status":"DRAFT","taxCategory":null,"attributes":{},"seoTitle":null,"seoDescription":null,"canonicalUrl":null,"version":1,"createdAt":"2026-08-20T10:00:00Z","updatedAt":"2026-08-20T10:00:00Z"}""",
        )

        assertEquals(emptyList(), sparse.variants)
        assertEquals(emptyList(), sparse.media)
        assertEquals(null, sparse.brandId)
        assertEquals(null, sparse.canonicalUrl)
        assertEquals(sparse, compactJson.decodeFromString(Product.serializer(), compactJson.encodeToString(Product.serializer(), sparse)))
        val withVariants = sparse.copy(variants = listOf(ProductVariant("variant-1", sparse.id, "SKU-1", null, emptyMap(), null, null, emptyMap(), VariantStatus.ACTIVE)))
        val withMedia = sparse.copy(media = listOf(ProductMedia("media-1", "IMAGE", null, 0, null)))
        assertEquals(withVariants, compactJson.decodeFromString(Product.serializer(), compactJson.encodeToString(Product.serializer(), withVariants)))
        assertEquals(withMedia, compactJson.decodeFromString(Product.serializer(), compactJson.encodeToString(Product.serializer(), withMedia)))
    }

    @Test
    fun `product request serialization covers nullable and default field combinations`() {
        val sparse = ProductRequest(categoryId = "cat-1", name = "Shoe", slug = "shoe", description = "description")
        val complete = ProductRequest(
            sellerId = "seller-1",
            ownerType = "ADMIN",
            brandId = "brand-1",
            categoryId = "cat-1",
            name = "Shoe",
            slug = "shoe",
            description = "description",
            shortDescription = "short",
            skuReference = "sku-ref",
            status = "ACTIVE",
            taxCategory = "GST18",
            attributes = mapOf("color" to "blue"),
            seoTitle = "Shoe",
            seoDescription = "A shoe",
            canonicalUrl = "https://shop.test/shoe",
            variants = listOf(VariantRequest("sku-1")),
            media = listOf(MediaRequest("media-1")),
        )

        assertEquals(sparse, json.decodeFromString<ProductRequest>(json.encodeToString(sparse)))
        assertEquals(complete, json.decodeFromString<ProductRequest>(json.encodeToString(complete)))
        assertEquals(sparse, compactJson.decodeFromString<ProductRequest>(compactJson.encodeToString(sparse)))
        assertEquals(complete, compactJson.decodeFromString<ProductRequest>(compactJson.encodeToString(complete)))
        listOf(
            VariantRequest("sku-1"),
            VariantRequest("sku-1", barcode = "barcode"),
            VariantRequest("sku-1", attributes = mapOf("size" to "9")),
            VariantRequest("sku-1", priceReference = "10.00"),
            VariantRequest("sku-1", weightGrams = 500),
            VariantRequest("sku-1", dimensions = mapOf("height" to "2")),
            VariantRequest("sku-1", status = "INACTIVE"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(VariantRequest.serializer(), compactJson.encodeToString(VariantRequest.serializer(), value)))
        }
        listOf(
            MediaRequest("media-1"),
            MediaRequest("media-1", mediaType = "VIDEO"),
            MediaRequest("media-1", url = "https://cdn.test/media"),
            MediaRequest("media-1", sortOrder = 2),
            MediaRequest("media-1", altText = "demo"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(MediaRequest.serializer(), compactJson.encodeToString(MediaRequest.serializer(), value)))
        }
    }
}
