package com.ecommerce.category

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CategorySerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `category and event payload round trip while path remains transient`() {
        CategoryStatus.entries.forEach { status ->
            val category = Category("category-1", "parent-1", "Footwear", "footwear", "Shoes", "https://cdn.test/category.jpg", "shoe", 1, status, "Footwear", "Shop footwear", listOf("shoes", "fashion"), "https://shop.test/footwear", 2, "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z")
            assertEquals(category, json.decodeFromString<Category>(json.encodeToString(category)))
        }
        val payload = CategoryEventPayload("category-1", "footwear", "ACTIVE", 2)
        assertEquals(payload, json.decodeFromString<CategoryEventPayload>(json.encodeToString(payload)))
    }

    @Test
    fun `category request serialization covers nullable and default field combinations`() {
        val sparse = CategoryRequest(name = "Shoes", slug = "shoes")
        val complete = CategoryRequest(
            parentId = "parent-1",
            name = "Shoes",
            slug = "shoes",
            description = "Footwear",
            imageUrl = "https://cdn.test/shoes.jpg",
            icon = "shoe",
            sortOrder = 3,
            status = "ACTIVE",
            seoTitle = "Shoes",
            seoDescription = "Shop shoes",
            seoKeywords = listOf("shoes", "footwear"),
            canonicalUrl = "https://shop.test/shoes",
        )

        assertEquals(sparse, json.decodeFromString<CategoryRequest>(json.encodeToString(sparse)))
        assertEquals(complete, json.decodeFromString<CategoryRequest>(json.encodeToString(complete)))
        assertEquals(sparse, compactJson.decodeFromString<CategoryRequest>(compactJson.encodeToString(sparse)))
        assertEquals(complete, compactJson.decodeFromString<CategoryRequest>(compactJson.encodeToString(complete)))
        listOf(
            ReorderRequest(categoryIds = listOf("category-1")),
            ReorderRequest(parentId = "parent-1", categoryIds = listOf("category-1", "category-2")),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(ReorderRequest.serializer(), compactJson.encodeToString(ReorderRequest.serializer(), value)))
        }
    }
}
