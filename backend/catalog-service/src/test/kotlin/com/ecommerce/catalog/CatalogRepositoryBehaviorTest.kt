package com.ecommerce.catalog

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `create reads complete product graph and publishes lifecycle events`() {
        val database = FakeCatalogDatabase()
        val repository = CatalogRepository(database.dataSource())
        val created = repository.create(input(status = ProductStatus.ACTIVE), "seller-1", "corr-create")

        assertEquals(ProductStatus.ACTIVE, created.status)
        assertEquals(1, created.variants.size)
        assertEquals("SKU-1", created.variants.single().sku)
        assertEquals(1, created.media.size)
        assertEquals("media-1", created.media.single().mediaId)
        assertEquals(created, repository.find(created.id, false))
        assertEquals(created, repository.find(created.id, true))
        assertEquals(created, repository.findBySlug("useful-product"))
        assertNull(repository.findBySlug("missing-product"))
        assertEquals(listOf(created), repository.list(null, 10, null, "seller-1", null).first)

        val events = repository.unpublished(20)
        assertEquals(2, events.size)
        assertEquals(listOf("ProductCreated", "ProductVariantCreated"), events.map { it.eventType })
        repository.markPublished(events.map { it.id }, now)
        assertTrue(database.published)
    }

    @Test
    fun `update status delete and ownership checks preserve seller isolation`() {
        val database = FakeCatalogDatabase(seed = true)
        val repository = CatalogRepository(database.dataSource())

        val forbidden = assertFailsWith<ApiException> { repository.update("product-1", input(), "seller-2", "corr") }
        assertEquals(ErrorCode.FORBIDDEN, forbidden.errorCode)
        val updated = repository.update("product-1", input(name = "Updated", status = ProductStatus.REVIEW), "seller-1", "corr-update")
        assertEquals("Updated", updated.name)
        assertEquals(ProductStatus.REVIEW, updated.status)

        val active = repository.changeStatus("product-1", ProductStatus.ACTIVE, "seller-1", "corr-publish")
        assertEquals(ProductStatus.ACTIVE, active.status)
        val inactive = repository.changeStatus("product-1", ProductStatus.INACTIVE, "seller-1", "corr-unpublish")
        assertEquals(ProductStatus.INACTIVE, inactive.status)
        repository.delete("product-1", "seller-1", "corr-delete")
        assertEquals(ProductStatus.ARCHIVED, database.productStatus())

        assertNull(repository.find("missing", false))
        val missing = assertFailsWith<ApiException> { repository.changeStatus("missing", ProductStatus.ACTIVE, "seller-1", "corr") }
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> { repository.update("missing", input(), "seller-1", "corr") }.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> { repository.delete("missing", "seller-1", "corr") }.errorCode)

        val systemOwned = FakeCatalogDatabase(seed = true, ownerType = OwnerType.SYSTEM)
        val tokenSellerMismatch = assertFailsWith<ApiException> {
            CatalogRepository(systemOwned.dataSource()).update("product-1", input(), "seller-2", "corr")
        }
        assertEquals(ErrorCode.FORBIDDEN, tokenSellerMismatch.errorCode)

        val systemUpdate = CatalogRepository(FakeCatalogDatabase(seed = true, ownerType = OwnerType.SYSTEM).dataSource())
            .update("product-1", input(ownerType = OwnerType.SYSTEM), "system-owner", "corr-system")
        assertEquals(OwnerType.SYSTEM, systemUpdate.ownerType)
    }

    @Test
    fun `pagination handles cursor and duplicate SKU errors are mapped`() {
        val database = FakeCatalogDatabase(seed = true)
        val repository = CatalogRepository(database.dataSource())
        val cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2026-08-19T00:00:00Z|product-1".toByteArray())
        assertEquals(1, repository.list(cursor, 1, null, null, ProductStatus.ACTIVE).first.size)

        val duplicateDatabase = FakeCatalogDatabase().also { it.failProductInsert = SQLException("duplicate", "23505") }
        val duplicate = assertFailsWith<ApiException> {
            CatalogRepository(duplicateDatabase.dataSource()).create(input(), "seller-1", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, duplicate.errorCode)
    }

    @Test
    fun `listing supports optional filters malformed cursors and next-page cursors`() {
        val filteredDatabase = FakeCatalogDatabase(seed = true)
        val filteredRepository = CatalogRepository(filteredDatabase.dataSource())
        val filtered = filteredRepository.list("not-a-valid-cursor", 10, "category-1", null, null)
        assertEquals(1, filtered.first.size)
        assertNull(filtered.second)

        val allFilters = filteredRepository.list(
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2026-08-19T00:00:00Z|product-1".toByteArray()),
            10,
            "category-1",
            "seller-1",
            ProductStatus.REVIEW,
        )
        assertEquals(1, allFilters.first.size)

        val pagedDatabase = FakeCatalogDatabase(seed = true).also { it.extraListRow = true }
        val paged = CatalogRepository(pagedDatabase.dataSource()).list(null, 1, null, null, null)
        assertEquals(1, paged.first.size)
        assertNotNull(paged.second)
    }

    @Test
    fun `create accepts nullable variant weight and products without nested records`() {
        val database = FakeCatalogDatabase()
        val repository = CatalogRepository(database.dataSource())
        val created = repository.create(
            input(
                ownerType = OwnerType.SYSTEM,
                variants = listOf(VariantInput("SKU-NULL-WEIGHT", null, emptyMap(), null, null, emptyMap(), VariantStatus.INACTIVE)),
                media = emptyList(),
            ),
            "system-owner",
            "corr",
        )

        assertEquals(1, created.variants.size)
        assertEquals(null, created.variants.single().weightGrams)
        assertTrue(created.media.isEmpty())
    }

    @Test
    fun `create propagates unexpected persistence failures`() {
        val database = FakeCatalogDatabase().also { it.failProductInsert = SQLException("database unavailable", "08001") }

        assertFailsWith<SQLException> {
            CatalogRepository(database.dataSource()).create(input(), "seller-1", "corr")
        }

        val unexpected = FakeCatalogDatabase().also { it.failProductInsert = IllegalStateException("wrapped catalog failure") }
        assertFailsWith<IllegalStateException> {
            CatalogRepository(unexpected.dataSource()).create(input(), "seller-1", "corr")
        }
    }

    @Test
    fun `update maps duplicate persistence failures and preserves unexpected failures`() {
        val duplicateDatabase = FakeCatalogDatabase(seed = true).also { it.failProductUpdate = SQLException("duplicate", "23505") }
        val duplicate = assertFailsWith<ApiException> {
            CatalogRepository(duplicateDatabase.dataSource()).update("product-1", input(), "seller-1", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, duplicate.errorCode)

        val unexpectedDatabase = FakeCatalogDatabase(seed = true).also { it.failProductUpdate = IllegalStateException("catalog update failed") }
        assertFailsWith<IllegalStateException> {
            CatalogRepository(unexpectedDatabase.dataSource()).update("product-1", input(), "seller-1", "corr")
        }
    }

    private fun input(
        name: String = "Product",
        status: ProductStatus = ProductStatus.DRAFT,
        ownerType: OwnerType = OwnerType.SELLER,
        variants: List<VariantInput> = listOf(VariantInput("SKU-1", "BAR-1", mapOf("size" to "M"), "1000", 100, mapOf("width" to "10"), VariantStatus.ACTIVE)),
        media: List<MediaInput> = listOf(MediaInput("media-1", "IMAGE", "https://cdn.test/image", 1, "Product image")),
    ) = ProductInput(
        "seller-1", ownerType, "brand-1", "category-1", name, "useful-product", "A useful product", "Short", "SKU-REF", status,
        "STANDARD", mapOf("color" to "blue"), "SEO", "SEO description", "https://shop.test/useful-product",
        variants,
        media,
    )

    private class FakeCatalogDatabase(seed: Boolean = false, private val ownerType: OwnerType = OwnerType.SELLER) {
        private val timestamp = Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
        var product: MutableProduct = if (seed) MutableProduct("product-1", ProductStatus.ACTIVE, 1, "seller-1", "Product", "useful-product") else MutableProduct("", ProductStatus.DRAFT, 1, "seller-1", "", "useful-product")
        var hasProduct = seed
        var failProductInsert: Throwable? = null
        var failProductUpdate: Throwable? = null
        var published = false
        var extraListRow = false
        val variants = mutableListOf<MutableVariant>()
        val media = mutableListOf<MutableMedia>()
        val events = mutableListOf<String>()

        fun productStatus() = product.status

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    "createArrayOf" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val p = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setArray" -> p[args!![0] as Int] = args.getOrNull(1)
                    "setNull" -> p[args!![0] as Int] = null
                    "executeQuery" -> resultSet(query(sql, p))
                    "executeUpdate" -> update(sql, p)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO products") -> {
                    failProductInsert?.let { throw it }
                    hasProduct = true
                    product = MutableProduct(p[1].toString(), ProductStatus.valueOf(p[11].toString()), 1, p[2].toString(), p[6].toString(), p[7].toString())
                }
            sql.startsWith("INSERT INTO product_variants") -> variants += MutableVariant(p[1].toString(), p[2].toString(), p[3].toString(), (p[7] as? Number)?.toInt(), VariantStatus.valueOf(p[9].toString()))
                sql.startsWith("INSERT INTO product_media") -> media += MutableMedia(p[2].toString(), p[3].toString(), p[4]?.toString())
                sql.startsWith("UPDATE products SET seller_id") -> { failProductUpdate?.let { throw it }; product.name = p[5].toString(); product.status = ProductStatus.valueOf(p[10].toString()); product.version++ }
                sql.startsWith("UPDATE products SET status=?") -> { product.status = ProductStatus.valueOf(p[1].toString()); product.version++ }
                sql.startsWith("UPDATE products SET status='ARCHIVED'") -> { product.status = ProductStatus.ARCHIVED; product.version++ }
                sql.startsWith("DELETE FROM product_variants") -> variants.clear()
                sql.startsWith("DELETE FROM product_media") -> media.clear()
                sql.startsWith("INSERT INTO product_status_history") -> Unit
                sql.startsWith("INSERT INTO catalog_outbox_events") -> events += p[4].toString()
                sql.startsWith("UPDATE catalog_outbox_events") -> published = true
            }
            return 1
        }

        private fun query(sql: String, p: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT * FROM products WHERE id") && hasProduct && p[1] == product.id && (!sql.contains("status='ACTIVE'") || product.status == ProductStatus.ACTIVE) -> listOf(productRow())
            sql.startsWith("SELECT * FROM products WHERE slug") && hasProduct && p[1] == product.slug && product.status == ProductStatus.ACTIVE -> listOf(productRow())
            sql.startsWith("SELECT p.*") && hasProduct -> if (extraListRow) {
                listOf(productRow(), productRow().toMutableMap().apply { this["id"] = "product-2" })
            } else listOf(productRow())
            sql.contains("FROM product_variants") -> variants.map { mapOf<Any, Any?>("id" to it.id, "product_id" to it.productId, "sku" to it.sku, "barcode" to null, "attributes" to "{}", "price_reference" to null, "weight_grams" to it.weight, "dimensions" to "{}", "status" to it.status.name) }
            sql.contains("FROM product_media") -> media.map { mapOf<Any, Any?>("media_id" to it.mediaId, "media_type" to it.type, "url" to it.url, "sort_order" to 1, "alt_text" to "image") }
            sql.contains("FROM catalog_outbox_events") -> events.mapIndexed { index, type -> mapOf<Any, Any?>(1 to "event-$index", 2 to "Product", 3 to product.id, 4 to type, 5 to 1, 6 to timestamp, 7 to "corr", 8 to "{}") }
            else -> emptyList()
        }

        private fun productRow() = mapOf<Any, Any?>(
            "id" to product.id, "seller_id" to product.sellerId, "owner_type" to ownerType.name, "brand_id" to "brand-1", "category_id" to "category-1", "name" to product.name,
            "slug" to product.slug, "description" to "A useful product", "short_description" to "Short", "sku_reference" to "SKU-REF", "status" to product.status.name,
            "tax_category" to "STANDARD", "attributes" to "{}", "seo_title" to "SEO", "seo_description" to "SEO description", "canonical_url" to null,
            "version" to product.version, "created_at" to timestamp, "updated_at" to timestamp,
        )

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            var wasNull = false
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key).also { wasNull = it == null }
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getTimestamp" -> value(key) as? Timestamp
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }

        private data class MutableProduct(val id: String, var status: ProductStatus, var version: Long, val sellerId: String, var name: String, val slug: String)
        private data class MutableVariant(val id: String, val productId: String, val sku: String, val weight: Int?, val status: VariantStatus)
        private data class MutableMedia(val mediaId: String, val type: String, val url: String?)
    }
}
