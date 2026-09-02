package com.ecommerce.cart

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class CartActor(val userId: String? = null, val guestToken: String? = null) {
    init { require((userId != null) xor !guestToken.isNullOrBlank()) }
}

data class PriceSnapshot(val unitMinor: Long, val currency: String, val version: String)

class CartRepository(private val dataSource: DataSource, private val guestExpirationSeconds: Long = 2_592_000, private val userExpirationSeconds: Long = 31_536_000) : CartStore, ServiceOutboxStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override fun getOrCreate(actor: CartActor, currency: String): CartResponse = transaction { connection ->
        val existing = find(connection, actor, false)
        if (existing != null) {
            ensureCurrency(existing, currency)
            return@transaction existing.toResponse(connection)
        }
        val now = Instant.now()
        val id = CommerceId.new("cart").value
        val guestHash = actor.guestToken?.let(::sha256)
        try {
            connection.prepareStatement("INSERT INTO carts(id,user_id,guest_id_hash,currency,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, id); statement.setString(2, actor.userId); statement.setString(3, guestHash); statement.setString(4, currency.uppercase()); statement.setTimestamp(5, now.timestamp()); statement.setTimestamp(6, now.timestamp()); statement.setTimestamp(7, now.plusSeconds(if (actor.userId == null) guestExpirationSeconds else userExpirationSeconds).timestamp()); statement.executeUpdate()
            }
        } catch (error: SQLException) {
            if (error.sqlState != "23505") throw error
            return@transaction find(connection, actor, false)!!.toResponse(connection)
        }
        val row = findById(connection, id)!!
        outbox(connection, row.toResponse(connection), "CartCreated", "cart-created", now)
        row.toResponse(connection)
    }

    override fun add(actor: CartActor, productId: String, variantId: String, quantity: Int, price: PriceSnapshot, key: String, correlationId: String): CartResponse = mutate(actor, price.currency, key, "add:$productId:$variantId:$quantity:${price.unitMinor}:${price.version}", correlationId, "CartItemAdded") { connection, row ->
        validateQuantity(quantity)
        val now = Instant.now()
        val item = item(connection, row.id, variantId, true)
        if (item == null) insertItem(connection, row.id, productId, variantId, quantity, price, now)
        else {
            if (item.productId != productId) throw ApiException(ErrorCode.CONFLICT, "Variant belongs to a different product.", 409)
            val total = item.quantity + quantity
            validateQuantity(total)
            connection.prepareStatement("UPDATE cart_items SET quantity=?,unit_price_minor=?,currency=?,price_version=?,updated_at=? WHERE id=?").use { statement ->
                statement.setInt(1, total); statement.setLong(2, price.unitMinor); statement.setString(3, price.currency); statement.setString(4, price.version); statement.setTimestamp(5, now.timestamp()); statement.setString(6, item.id); statement.executeUpdate()
            }
        }
        row.version += 1
        updateVersion(connection, row.id, row.version, now)
    }

    override fun update(actor: CartActor, variantId: String, quantity: Int, price: PriceSnapshot?, key: String, correlationId: String): CartResponse = mutate(actor, price?.currency ?: "INR", key, "update:$variantId:$quantity:${price?.unitMinor}:${price?.version}", correlationId, "CartItemUpdated") { connection, row ->
        validateQuantity(quantity)
        val current = item(connection, row.id, variantId, true) ?: throw ApiException(ErrorCode.NOT_FOUND, "Cart item not found.", 404)
        val now = Instant.now()
        connection.prepareStatement("UPDATE cart_items SET quantity=?,unit_price_minor=?,currency=?,price_version=?,updated_at=? WHERE id=?").use { statement ->
            statement.setInt(1, quantity); statement.setLong(2, price?.unitMinor ?: current.unitPriceMinor); statement.setString(3, price?.currency ?: current.currency); statement.setString(4, price?.version ?: current.priceVersion); statement.setTimestamp(5, now.timestamp()); statement.setString(6, current.id); statement.executeUpdate()
        }
        row.version += 1; updateVersion(connection, row.id, row.version, now)
    }

    override fun remove(actor: CartActor, variantId: String, currency: String, key: String, correlationId: String): CartResponse = mutate(actor, currency, key, "remove:$variantId", correlationId, "CartItemRemoved") { connection, row ->
        val now = Instant.now()
        connection.prepareStatement("DELETE FROM cart_items WHERE cart_id=? AND variant_id=?").use { statement -> statement.setString(1, row.id); statement.setString(2, variantId); statement.executeUpdate() }
        row.version += 1; updateVersion(connection, row.id, row.version, now)
    }

    override fun clear(actor: CartActor, currency: String, key: String, correlationId: String): CartResponse = mutate(actor, currency, key, "clear", correlationId, "CartCleared") { connection, row ->
        val now = Instant.now()
        connection.prepareStatement("DELETE FROM cart_items WHERE cart_id=?").use { statement -> statement.setString(1, row.id); statement.executeUpdate() }
        row.version += 1; updateVersion(connection, row.id, row.version, now)
    }

    fun abandonExpired(limit: Int, correlationId: String): Int = transaction { connection ->
        val ids = connection.prepareStatement("SELECT id FROM carts WHERE status='ACTIVE' AND expires_at <= now() ORDER BY expires_at FOR UPDATE SKIP LOCKED LIMIT ?").use { statement -> statement.setInt(1, limit.coerceIn(1, 500)); statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } } }
        ids.forEach { id -> connection.prepareStatement("UPDATE carts SET status='ABANDONED',updated_at=now() WHERE id=?").use { statement -> statement.setString(1, id); statement.executeUpdate() }; val row = findById(connection, id) ?: return@forEach; outbox(connection, row.toResponse(connection), "CartAbandoned", correlationId, Instant.now()) }
        ids.size
    }

    override fun merge(userId: String, guestToken: String, currency: String, correlationId: String): CartResponse = transaction { connection ->
        val user = find(connection, CartActor(userId = userId), true) ?: createUser(connection, userId, currency)
        val guest = find(connection, CartActor(guestToken = guestToken), true) ?: return@transaction user.toResponse(connection)
        ensureCurrency(user, currency); ensureCurrency(guest, currency)
        val guestItems = items(connection, guest.id)
        guestItems.forEach { guestItem ->
            val current = item(connection, user.id, guestItem.variantId, true)
            if (current == null) insertItem(connection, user.id, guestItem.productId, guestItem.variantId, guestItem.quantity, PriceSnapshot(guestItem.unitPriceMinor, guestItem.currency, guestItem.priceVersion), Instant.now())
            else connection.prepareStatement("UPDATE cart_items SET quantity=?,updated_at=? WHERE id=?").use { statement -> statement.setInt(1, (current.quantity + guestItem.quantity).coerceAtMost(99)); statement.setTimestamp(2, Instant.now().timestamp()); statement.setString(3, current.id); statement.executeUpdate() }
        }
        connection.prepareStatement("DELETE FROM carts WHERE id=?").use { statement -> statement.setString(1, guest.id); statement.executeUpdate() }
        user.version += 1; updateVersion(connection, user.id, user.version, Instant.now())
        val response = user.toResponse(connection)
        outbox(connection, response, "CartMerged", correlationId, Instant.now())
        response
    }

    private fun mutate(actor: CartActor, currency: String, key: String, request: String, correlationId: String, eventType: String, operation: (Connection, CartRow) -> Unit): CartResponse = transaction { connection ->
        if (key.isBlank() || key.length > 128) throw ApiException(ErrorCode.VALIDATION_ERROR, "A valid Idempotency-Key is required.", 400)
        val existingCart = find(connection, actor, true)
        val row = existingCart ?: createActor(connection, actor, currency)
        if (existingCart == null) outbox(connection, row.toResponse(connection), "CartCreated", correlationId, Instant.now())
        ensureCurrency(row, currency)
        val hash = sha256(request)
        val prior = connection.prepareStatement("SELECT request_hash,response_json::text FROM cart_idempotency WHERE cart_id=? AND idempotency_key=?").use { statement -> statement.setString(1, row.id); statement.setString(2, key); statement.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null } }
        if (prior != null) {
            if (prior.first != hash) throw ApiException(ErrorCode.CONFLICT, "Idempotency key was reused with a different request.", 409)
            return@transaction json.decodeFromString<CartResponse>(prior.second)
        }
        operation(connection, row)
        val response = row.toResponse(connection)
        val now = Instant.now()
        connection.prepareStatement("INSERT INTO cart_idempotency(cart_id,idempotency_key,request_hash,response_json,created_at) VALUES (?,?,?,?::jsonb,?)").use { statement -> statement.setString(1, row.id); statement.setString(2, key); statement.setString(3, hash); statement.setString(4, json.encodeToString(response)); statement.setTimestamp(5, now.timestamp()); statement.executeUpdate() }
        outbox(connection, response, eventType, correlationId, now)
        response
    }

    private fun createActor(connection: Connection, actor: CartActor, currency: String): CartRow {
        val now = Instant.now(); val id = CommerceId.new("cart").value
        connection.prepareStatement("INSERT INTO carts(id,user_id,guest_id_hash,currency,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?)").use { statement -> statement.setString(1, id); statement.setString(2, actor.userId); statement.setString(3, actor.guestToken?.let(::sha256)); statement.setString(4, currency.uppercase()); statement.setTimestamp(5, now.timestamp()); statement.setTimestamp(6, now.timestamp()); statement.setTimestamp(7, now.plusSeconds(if (actor.userId == null) guestExpirationSeconds else userExpirationSeconds).timestamp()); statement.executeUpdate() }
        return findById(connection, id)!!
    }

    private fun createUser(connection: Connection, userId: String, currency: String) = createActor(connection, CartActor(userId = userId), currency)
    private fun find(connection: Connection, actor: CartActor, lock: Boolean): CartRow? {
        val suffix = if (lock) " FOR UPDATE" else ""
        val sql = if (actor.userId != null) "SELECT * FROM carts WHERE user_id=? AND status='ACTIVE'$suffix" else "SELECT * FROM carts WHERE guest_id_hash=? AND status='ACTIVE'$suffix"
        return connection.prepareStatement(sql).use { statement -> statement.setString(1, actor.userId ?: sha256(actor.guestToken!!)); statement.executeQuery().use { if (it.next()) it.row() else null } }
    }
    private fun findById(connection: Connection, id: String) = connection.prepareStatement("SELECT * FROM carts WHERE id=? FOR UPDATE").use { statement -> statement.setString(1, id); statement.executeQuery().use { if (it.next()) it.row() else null } }
    private fun item(connection: Connection, cartId: String, variantId: String, lock: Boolean): CartItem? { val suffix = if (lock) " FOR UPDATE" else ""; return connection.prepareStatement("SELECT * FROM cart_items WHERE cart_id=? AND variant_id=?$suffix").use { statement -> statement.setString(1, cartId); statement.setString(2, variantId); statement.executeQuery().use { if (it.next()) it.item() else null } } }
    private fun items(connection: Connection, cartId: String): List<CartItem> = connection.prepareStatement("SELECT * FROM cart_items WHERE cart_id=? ORDER BY added_at,id").use { statement -> statement.setString(1, cartId); statement.executeQuery().use { result -> buildList { while (result.next()) add(result.item()) } } }
    private fun insertItem(connection: Connection, cartId: String, productId: String, variantId: String, quantity: Int, price: PriceSnapshot, now: Instant) = connection.prepareStatement("INSERT INTO cart_items(id,cart_id,product_id,variant_id,quantity,unit_price_minor,currency,price_version,added_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)").use { statement -> statement.setString(1, CommerceId.new("ci").value); statement.setString(2, cartId); statement.setString(3, productId); statement.setString(4, variantId); statement.setInt(5, quantity); statement.setLong(6, price.unitMinor); statement.setString(7, price.currency); statement.setString(8, price.version); statement.setTimestamp(9, now.timestamp()); statement.setTimestamp(10, now.timestamp()); statement.executeUpdate() }
    private fun updateVersion(connection: Connection, id: String, version: Long, now: Instant) = connection.prepareStatement("UPDATE carts SET version=?,updated_at=? WHERE id=?").use { statement -> statement.setLong(1, version); statement.setTimestamp(2, now.timestamp()); statement.setString(3, id); statement.executeUpdate() }
    private fun ensureCurrency(row: CartRow, currency: String) { if (row.currency != currency.uppercase()) throw ApiException(ErrorCode.VALIDATION_ERROR, "Cart currency cannot be changed.", 400) }
    private fun validateQuantity(quantity: Int) { if (quantity !in 1..99) throw ApiException(ErrorCode.VALIDATION_ERROR, "Quantity must be between 1 and 99.", 400) }
    private fun outbox(connection: Connection, response: CartResponse, type: String, correlationId: String, now: Instant) = connection.prepareStatement("INSERT INTO cart_outbox_events(id,aggregate_id,event_type,occurred_at,correlation_id,payload_json) VALUES (?,?,?, ?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, response.id); statement.setString(3, type); statement.setTimestamp(4, now.timestamp()); statement.setString(5, correlationId); statement.setString(6, json.encodeToString(response)); statement.executeUpdate() }
    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection { connection -> connection.prepareStatement("SELECT id,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM cart_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement -> statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), "Cart", result.getString(2), result.getString(3), result.getInt(4), result.getTimestamp(5).toInstant(), result.getString(6), result.getString(7))) } } } }
    override fun markPublished(ids: List<String>, publishedAt: Instant) { if (ids.isEmpty()) return; transaction { connection -> connection.prepareStatement("UPDATE cart_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement -> statement.setTimestamp(1, publishedAt.timestamp()); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() } } }
    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

private data class CartRow(val id: String, val userId: String?, val currency: String, var version: Long) {
    fun toResponse(connection: Connection): CartResponse = CartResponse(id, userId, currency, version, connection.prepareStatement("SELECT * FROM cart_items WHERE cart_id=? ORDER BY added_at,id").use { statement -> statement.setString(1, id); statement.executeQuery().use { result -> buildList { while (result.next()) add(result.item()) } } })
}

private fun ResultSet.row() = CartRow(getString("id"), getString("user_id"), getString("currency"), getLong("version"))
private fun ResultSet.item() = CartItem(getString("id"), getString("product_id"), getString("variant_id"), getInt("quantity"), getLong("unit_price_minor"), getString("currency"), getString("price_version"), getTimestamp("added_at").toInstant().toString(), getTimestamp("updated_at").toInstant().toString())
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun Instant.timestamp() = Timestamp.from(this)
