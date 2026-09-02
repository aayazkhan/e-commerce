package com.ecommerce.category

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Array
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryRepositoryLifecycleTest {
    @Test
    fun `hierarchy creation reads and public filtering preserve parent paths`() {
        val database = MemoryCategoryDatabase()
        val repository = CategoryRepository(database.dataSource())
        val root = repository.create(input("Women", "women", status = CategoryStatus.ACTIVE), "corr")
        val child = repository.create(input("Shoes", "shoes", parentId = root.id, status = CategoryStatus.ACTIVE), "corr")
        assertEquals("/${root.id}/", root.path)
        assertEquals("/${root.id}/${child.id}/", database.categories[child.id]!!.path)
        assertEquals(listOf(child), repository.list(root.id, publicOnly = true))
        assertEquals(listOf(child), repository.list(root.id, publicOnly = false))
        assertEquals(child, repository.find(child.id, publicOnly = true))
        assertNull(repository.find("missing", publicOnly = false))
        assertEquals(2, repository.tree().size)

        val archivedParent = repository.create(input("Archive", "archive", status = CategoryStatus.ARCHIVED), "corr")
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.create(input("Invalid", "invalid", parentId = archivedParent.id), "corr")
        }.errorCode)

        database.duplicateInsert = true
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.create(input("Duplicate", "duplicate"), "corr")
        }.errorCode)
    }

    @Test
    fun `update rejects missing parents self parent cycles and conflicts while versioning changes`() {
        val database = MemoryCategoryDatabase()
        val repository = CategoryRepository(database.dataSource())
        val root = repository.create(input("Root", "root"), "corr")
        val child = repository.create(input("Child", "child", parentId = root.id), "corr")
        assertEquals(2L, repository.update(child.id, input("Renamed", "renamed", parentId = root.id), "corr").version)
        val newRoot = repository.create(input("New root", "new-root"), "corr")
        val moved = repository.update(child.id, input("Moved", "moved", parentId = newRoot.id), "corr")
        assertEquals("/${newRoot.id}/${child.id}/", moved.path)
        database.duplicateUpdate = true
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.update(child.id, input("Duplicate update", "duplicate-update"), "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> { repository.update(child.id, input("Self", "self", parentId = child.id), "corr") }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> { repository.update(child.id, input("Missing", "missing", parentId = "missing"), "corr") }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> { repository.update(newRoot.id, input("Cycle", "cycle", parentId = child.id), "corr") }.errorCode)
        assertEquals(404, assertFailsWith<ApiException> { repository.update("missing", input("Missing", "missing"), "corr") }.statusCode)
    }

    @Test
    fun `status deletion and reorder enforce lifecycle and hierarchy invariants`() {
        val database = MemoryCategoryDatabase()
        val repository = CategoryRepository(database.dataSource())
        val parent = repository.create(input("Parent", "parent", status = CategoryStatus.INACTIVE), "corr")
        val child = repository.create(input("Child", "child", parentId = parent.id, status = CategoryStatus.DRAFT), "corr")
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> { repository.changeStatus(child.id, CategoryStatus.ACTIVE, "corr") }.errorCode)
        repository.changeStatus(parent.id, CategoryStatus.ACTIVE, "corr")
        assertEquals(CategoryStatus.ACTIVE, repository.changeStatus(child.id, CategoryStatus.ACTIVE, "corr").status)
        assertEquals(CategoryStatus.ARCHIVED, repository.changeStatus(child.id, CategoryStatus.ARCHIVED, "corr").status)
        assertEquals(listOf(child.id), repository.reorder(parent.id, listOf(child.id), "corr").map { it.id })
        assertTrue(repository.reorder(parent.id, emptyList(), "corr").isEmpty())
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> { repository.reorder(null, listOf(child.id), "corr") }.errorCode)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> { repository.delete(parent.id, "corr") }.errorCode)
        repository.delete(child.id, "corr")
        repository.delete(parent.id, "corr")
        assertEquals(404, assertFailsWith<ApiException> { repository.delete(parent.id, "corr") }.statusCode)
        val outbox = repository.unpublished(100)
        repository.markPublished(outbox.map { it.id }, Instant.now())
        assertTrue(repository.unpublished(100).isEmpty())
        assertEquals(404, assertFailsWith<ApiException> { repository.changeStatus("missing", CategoryStatus.ACTIVE, "corr") }.statusCode)

        val missingParentDatabase = MemoryCategoryDatabase()
        val missingParentRepository = CategoryRepository(missingParentDatabase.dataSource())
        val existingParent = missingParentRepository.create(input("Existing parent", "existing-parent", status = CategoryStatus.ACTIVE), "corr")
        val orphan = missingParentRepository.create(input("Orphan", "orphan", parentId = existingParent.id), "corr")
        missingParentDatabase.categories.remove(existingParent.id)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            missingParentRepository.changeStatus(orphan.id, CategoryStatus.ACTIVE, "corr")
        }.errorCode)
    }

    @Test
    fun `unexpected category persistence failures are propagated`() {
        val insertFailure = MemoryCategoryDatabase().also { it.insertFailure = IllegalStateException("category insert unavailable") }
        assertFailsWith<IllegalStateException> {
            CategoryRepository(insertFailure.dataSource()).create(input("Failure", "failure"), "corr")
        }

        val insertSqlFailure = MemoryCategoryDatabase().also { it.insertFailure = java.sql.SQLException("database unavailable", "08001") }
        assertFailsWith<java.sql.SQLException> {
            CategoryRepository(insertSqlFailure.dataSource()).create(input("SQL failure", "sql-failure"), "corr")
        }

        val updateFailure = MemoryCategoryDatabase()
        val repository = CategoryRepository(updateFailure.dataSource())
        val created = repository.create(input("Existing", "existing"), "corr")
        updateFailure.updateFailure = IllegalStateException("category update unavailable")
        assertFailsWith<IllegalStateException> {
            repository.update(created.id, input("Updated", "updated"), "corr")
        }

        val updateSqlFailure = MemoryCategoryDatabase()
        val updateSqlRepository = CategoryRepository(updateSqlFailure.dataSource())
        val sqlCreated = updateSqlRepository.create(input("Existing SQL", "existing-sql"), "corr")
        updateSqlFailure.updateFailure = java.sql.SQLException("database unavailable", "08001")
        assertFailsWith<java.sql.SQLException> {
            updateSqlRepository.update(sqlCreated.id, input("Updated SQL", "updated-sql"), "corr")
        }
    }

    private fun input(name: String, slug: String, parentId: String? = null, status: CategoryStatus = CategoryStatus.DRAFT) = CategoryInput(parentId, name, slug, "description", null, null, 0, status, "SEO", "SEO description", listOf("keyword"), null)
}

private class MemoryCategoryDatabase {
    val categories = linkedMapOf<String, Category>()
    var duplicateInsert = false
    var duplicateUpdate = false
    var insertFailure: Throwable? = null
    var updateFailure: Throwable? = null
    data class Outbox(val id: String, val aggregate: String, val event: String, val at: Timestamp, val correlation: String, val payload: String, var published: Boolean = false)
    val outbox = linkedMapOf<String, Outbox>()

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection() else defaultValue(method.returnType) }) as DataSource

    private fun connection(): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args -> when (method.name) {
        "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
        "createArrayOf" -> sqlArray(args?.getOrNull(1))
        "setAutoCommit", "commit", "rollback", "close" -> null
        else -> defaultValue(method.returnType)
    } }) as Connection

    private fun statement(raw: String): PreparedStatement {
        val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
        val p = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args -> when {
            method.name.startsWith("set") && args?.firstOrNull() is Int -> { p[args[0] as Int] = args.getOrNull(1); null }
            method.name == "executeQuery" -> resultSet(query(sql, p))
            method.name == "executeUpdate" -> update(sql, p)
            method.name == "close" -> null
            else -> defaultValue(method.returnType)
        } }) as PreparedStatement
    }

    private fun query(sql: String, p: Map<Int, Any?>): List<Map<String, Any?>> = when {
        sql.startsWith("SELECT * FROM CATEGORIES WHERE PARENT_ID IS NOT DISTINCT") -> categories.values.filter { it.parentId == p[1] && (!sql.contains("STATUS = 'ACTIVE'") || it.status == CategoryStatus.ACTIVE) }.map(::categoryMap)
        sql.startsWith("SELECT * FROM CATEGORIES WHERE ID") -> categories[p[1]]?.takeIf { !sql.contains("STATUS = 'ACTIVE'") || it.status == CategoryStatus.ACTIVE }?.let { listOf(categoryMap(it)) } ?: emptyList()
        sql.startsWith("SELECT * FROM CATEGORIES WHERE STATUS = 'ACTIVE'") -> categories.values.filter { it.status == CategoryStatus.ACTIVE }.map(::categoryMap)
        sql.startsWith("SELECT 1 FROM CATEGORIES WHERE PARENT_ID") -> if (categories.values.any { it.parentId == p[1] }) listOf(mapOf("one" to 1)) else emptyList()
        sql.startsWith("SELECT ID,AGGREGATE_TYPE") -> outbox.values.filterNot { it.published }.take((p[1] as? Int) ?: 100).map { mapOf("id" to it.id, "aggregate_type" to "Category", "aggregate_id" to it.aggregate, "event_type" to it.event, "schema_version" to 1, "occurred_at" to it.at, "correlation_id" to it.correlation, "payload_json" to it.payload) }
        else -> emptyList()
    }

    private fun update(sql: String, p: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO CATEGORIES") -> {
            if (duplicateInsert) throw java.sql.SQLException("duplicate", "23505")
            insertFailure?.let { throw it }
            val id = p[1].toString()
            categories[id] = Category(id, p[2] as? String, p[3].toString(), p[4].toString(), p[5] as? String, p[6] as? String, p[7] as? String, (p[8] as Number).toInt(), CategoryStatus.valueOf(p[9].toString()), p[10] as? String, p[11] as? String, kotlinx.serialization.json.Json.decodeFromString(p[12].toString()), p[13] as? String, 1, (p[15] as Timestamp).toInstant().toString(), (p[16] as Timestamp).toInstant().toString(), p[14].toString())
            1
        }
        sql.startsWith("UPDATE CATEGORIES SET PARENT_ID=") -> {
            if (duplicateUpdate) throw java.sql.SQLException("duplicate", "23505")
            updateFailure?.let { throw it }
            categories[p[17]]?.let { old -> categories[p[17].toString()] = old.copy(parentId = p[1] as? String, name = p[2].toString(), slug = p[3].toString(), description = p[4] as? String, imageUrl = p[5] as? String, icon = p[6] as? String, sortOrder = (p[7] as Number).toInt(), status = CategoryStatus.valueOf(p[8].toString()), seoTitle = p[9] as? String, seoDescription = p[10] as? String, seoKeywords = kotlinx.serialization.json.Json.decodeFromString(p[11].toString()), canonicalUrl = p[12] as? String, path = p[13].toString(), version = old.version + 1, updatedAt = (p[14] as Timestamp).toInstant().toString()); 1 } ?: 0
        }
        sql.startsWith("UPDATE CATEGORIES SET PATH") -> { val prefix = p[1].toString(); categories.values.filter { it.path.startsWith(p[4].toString().removeSuffix("%")) && it.id != p[5] }.forEach { categories[it.id] = it.copy(path = prefix + it.path.substringAfter(p[4].toString().removeSuffix("%"))) }; 1 }
        sql.startsWith("UPDATE CATEGORIES SET STATUS=") -> categories[p[5]]?.let { categories[p[5].toString()] = it.copy(status = CategoryStatus.valueOf(p[1].toString()), version = it.version + 1, updatedAt = (p[2] as Timestamp).toInstant().toString()); 1 } ?: 0
        sql.startsWith("UPDATE CATEGORIES SET SORT_ORDER") -> categories[p[3]]?.let { categories[p[3].toString()] = it.copy(sortOrder = (p[1] as Number).toInt(), version = it.version + 1); 1 } ?: 0
        sql.startsWith("DELETE FROM CATEGORIES") -> if (categories.remove(p[1].toString()) != null) 1 else 0
        sql.startsWith("INSERT INTO CATEGORY_OUTBOX_EVENTS") -> { val id = p[1].toString(); outbox[id] = Outbox(id, p[3].toString(), p[4].toString(), p[5] as Timestamp, p[6].toString(), p[7].toString()); 1 }
        sql.startsWith("UPDATE CATEGORY_OUTBOX_EVENTS") -> { val ids = (p[2] as Array).getArray() as kotlin.Array<*>; ids.forEach { outbox[it.toString()]?.published = true }; 1 }
        else -> 1
    }

    private fun categoryMap(category: Category) = mapOf<String, Any?>("id" to category.id, "parent_id" to category.parentId, "name" to category.name, "slug" to category.slug, "description" to category.description, "image_url" to category.imageUrl, "icon" to category.icon, "sort_order" to category.sortOrder, "status" to category.status.name, "seo_title" to category.seoTitle, "seo_description" to category.seoDescription, "seo_keywords" to kotlinx.serialization.json.Json.encodeToString(category.seoKeywords), "canonical_url" to category.canonicalUrl, "version" to category.version, "created_at" to Timestamp.from(Instant.parse(category.createdAt)), "updated_at" to Timestamp.from(Instant.parse(category.updatedAt)), "path" to category.path)

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? { val row = rows.getOrNull(index); val k = key.toString(); val actual = k.toIntOrNull()?.let { row?.keys?.elementAtOrNull(it - 1) } ?: k; val value = row?.get(actual); wasNull = value == null; return value }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args -> when (method.name) {
            "next" -> if (index + 1 < rows.size) { index++; true } else false
            "getString" -> value(args?.firstOrNull() ?: "")?.toString()
            "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
            "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
            "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
            "wasNull" -> wasNull
            "close" -> null
            else -> defaultValue(method.returnType)
        } }) as ResultSet
    }

    private fun sqlArray(values: Any?): Array = Proxy.newProxyInstance(Array::class.java.classLoader, arrayOf(Array::class.java), InvocationHandler { _, method, _ -> if (method.name == "getArray") values else defaultValue(method.returnType) }) as Array
    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
}
