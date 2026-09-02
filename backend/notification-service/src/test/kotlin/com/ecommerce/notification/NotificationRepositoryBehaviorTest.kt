package com.ecommerce.notification

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationRepositoryBehaviorTest {
    private val database = FakeNotificationDatabase()
    private val repository = NotificationRepository(database.dataSource(), Json.Default)

    @Test
    fun `accept deduplicates events applies preferences and renders fallback templates`() {
        val event = event("PaymentCaptured", "{\"userId\":\"user-1\",\"status\":\"CAPTURED\"}")

        repository.accept(event, NotificationChannel.entries.toSet())
        repository.accept(event, NotificationChannel.entries.toSet())

        assertEquals(1, database.inboxInsertions)
        assertEquals(4, database.deliveryInsertions)
        assertTrue(database.deliveryStatuses.contains("SUPPRESSED"))
        assertTrue(database.deliveryStatuses.contains("QUEUED"))
        assertTrue(database.executedSql.any { it.contains("notification_templates") })
        assertTrue(database.executedSql.any { it.contains("notification_deliveries") })
    }

    @Test
    fun `accept safely ignores malformed and incomplete payloads after recording inbox`() {
        repository.accept(event("OrderCreated", "not-json"), setOf(NotificationChannel.PUSH))
        repository.accept(event("OrderCreated", "{}"), setOf(NotificationChannel.PUSH))

        assertEquals(2, database.inboxInsertions)
        assertEquals(0, database.deliveryInsertions)
    }

    @Test
    fun `claim and delivery lifecycle cover provider fallback success retries and DLQ`() {
        val claimed = repository.claimDue()
        assertNotNull(claimed)
        assertEquals(NotificationChannel.PUSH, claimed.channel)
        assertEquals("FCM", claimed.platform)

        repository.sent(claimed.id, "fcm", "message-1")
        repository.updateWebhook("message-1", "delivered")
        repository.updateWebhook("message-1", "unknown")
        repository.inApp("in-app-1", "user-1", "Title", "Body")

        repository.failed(claimed.copy(attempts = 0), IllegalStateException("provider timeout"))
        repository.failed(claimed.copy(attempts = NotificationRetryPolicy.maxAttempts - 1), IllegalStateException("provider down"))
        repository.failed(claimed.copy(attempts = 0), RuntimeException())
        repository.failed(claimed.copy(attempts = NotificationRetryPolicy.maxAttempts - 1), RuntimeException())

        assertTrue(database.executedSql.any { it.contains("status='SENT'") })
        assertTrue(database.executedSql.any { it.contains("status='DLQ'") })
        assertTrue(database.executedSql.any { it.contains("notification_dlq") })
    }

    @Test
    fun `preferences devices templates in-app and DLQ persistence preserve values`() {
        val preferences = repository.getPreferences("user-1")
        assertEquals("hi-IN", preferences.locale)
        assertEquals("22:00", preferences.quietStart)
        assertEquals("07:00", preferences.quietEnd)
        assertEquals(false, preferences.emailEnabled)

        repository.preferences("user-1", PreferenceRequest(false, true, true, false, "22:00", "07:00", "Asia/Kolkata", "hi-IN"))
        repository.device("user-1", DeviceRequest("ANDROID", "device-token"))
        repository.template(TemplateRequest("order.status", "hi-IN", "स्थिति", "Order {{aggregateId}}: {{status}}"))
        repository.markRead("user-1", "in-app-1")
        repository.dlq(event("RefundFailed", "{\"userId\":\"user-1\"}"), SQLException("consumer failed", "08001"))

        val inApp = repository.listInApp("user-1", 200)
        assertEquals(1, inApp.size)
        assertEquals("in-app-1", inApp.single().id)
        assertEquals("Body", inApp.single().body)
        assertTrue(database.executedSql.any { it.contains("notification_preferences") })
        assertTrue(database.executedSql.any { it.contains("notification_devices") })
        assertTrue(database.executedSql.any { it.contains("notification_templates") })
    }

    @Test
    fun `accept covers event template selection locale defaults and snake case payloads`() {
        val database = FakeNotificationDatabase(templateFound = true)
        val repository = NotificationRepository(database.dataSource(), Json.Default)
        listOf("ShipmentDispatched", "RefundIssued", "OrderCreated").forEach { type ->
            repository.accept(event(type, "{\"user_id\":\"user-1\"}", type), setOf(NotificationChannel.PUSH))
        }
        assertEquals(3, database.deliveryInsertions)
        assertTrue(database.deliveryStatuses.all { it == "QUEUED" })
        assertTrue(database.executedSql.count { it.contains("notification_templates") } >= 3)
    }

    @Test
    fun `notification repository covers quiet hours missing preferences and empty reads`() {
        val quietDatabase = FakeNotificationDatabase(quietWindow = QuietWindow.NORMAL)
        val quietRepository = NotificationRepository(quietDatabase.dataSource(), Json.Default)
        quietRepository.accept(event("OrderCreated", "{\"userId\":\"user-1\",\"status\":\"CREATED\"}"), setOf(NotificationChannel.PUSH))
        assertEquals(listOf("SUPPRESSED"), quietDatabase.deliveryStatuses)

        val overnightDatabase = FakeNotificationDatabase(quietWindow = QuietWindow.OVERNIGHT)
        val overnightRepository = NotificationRepository(overnightDatabase.dataSource(), Json.Default)
        overnightRepository.accept(event("OrderCreated", "{\"userId\":\"user-1\"}"), setOf(NotificationChannel.PUSH))
        assertEquals(listOf("SUPPRESSED"), overnightDatabase.deliveryStatuses)

        val emptyDatabase = FakeNotificationDatabase(noPreferences = true, claimEmpty = true, noInApp = true)
        val emptyRepository = NotificationRepository(emptyDatabase.dataSource(), Json.Default)
        assertEquals(PreferenceRequest(), emptyRepository.getPreferences("missing"))
        assertEquals(null, emptyRepository.claimDue())
        assertTrue(emptyRepository.listInApp("missing", 0).isEmpty())
        emptyRepository.accept(event("OrderCreated", "{\"userId\":\"user-1\"}"), setOf(NotificationChannel.PUSH))
        assertEquals(listOf("QUEUED"), emptyDatabase.deliveryStatuses)
        emptyRepository.dlq(null, RuntimeException())

        val nullTimesDatabase = FakeNotificationDatabase(nullPreferenceTimes = true)
        val nullTimesPreferences = NotificationRepository(nullTimesDatabase.dataSource(), Json.Default).getPreferences("user-1")
        assertEquals(null, nullTimesPreferences.quietStart)
        assertEquals(null, nullTimesPreferences.quietEnd)

        val inactiveDatabase = FakeNotificationDatabase(quietWindow = QuietWindow.NOT_ACTIVE)
        val inactiveRepository = NotificationRepository(inactiveDatabase.dataSource(), Json.Default)
        inactiveRepository.accept(event("OrderCreated", "{\"userId\":\"user-1\"}"), setOf(NotificationChannel.PUSH))
        assertEquals(listOf("QUEUED"), inactiveDatabase.deliveryStatuses)

        val expiredDatabase = FakeNotificationDatabase(quietWindow = QuietWindow.NORMAL_EXPIRED)
        val expiredRepository = NotificationRepository(expiredDatabase.dataSource(), Json.Default)
        expiredRepository.accept(event("OrderCreated", "{\"userId\":\"user-1\"}"), setOf(NotificationChannel.PUSH))
        assertEquals(listOf("QUEUED"), expiredDatabase.deliveryStatuses)
    }

    @Test
    fun `claim and in app mapping preserve provider platform and read state`() {
        val database = FakeNotificationDatabase(activePlatform = "APNS", readAtPresent = true)
        val repository = NotificationRepository(database.dataSource(), Json.Default)
        val delivery = repository.claimDue()
        assertNotNull(delivery)
        assertEquals("APNS", delivery.platform)
        val notifications = repository.listInApp("user-1", 101)
        assertEquals(false, notifications.single().read)
    }

    @Test
    fun `quiet hours honor normal and overnight start and end boundaries`() {
        fun statusAt(start: String, end: String, now: String): String {
            val database = FakeNotificationDatabase(fixedQuietTimes = Time.valueOf(start) to Time.valueOf(end))
            NotificationRepository(database.dataSource(), Json.Default) { LocalTime.parse(now) }
                .accept(event("OrderCreated", "{\"userId\":\"user-1\"}", "boundary-$start-$end-$now"), setOf(NotificationChannel.PUSH))
            return database.deliveryStatuses.single()
        }

        assertEquals("SUPPRESSED", statusAt("10:00:00", "14:00:00", "10:00"))
        assertEquals("QUEUED", statusAt("10:00:00", "14:00:00", "14:00"))
        assertEquals("QUEUED", statusAt("10:00:00", "14:00:00", "09:00"))
        assertEquals("SUPPRESSED", statusAt("22:00:00", "07:00:00", "22:00"))
        assertEquals("SUPPRESSED", statusAt("22:00:00", "07:00:00", "06:59"))
        assertEquals("QUEUED", statusAt("22:00:00", "07:00:00", "07:00"))
        assertEquals("QUEUED", statusAt("22:00:00", "07:00:00", "12:00"))

        fun statusWithMissingBoundary(start: Time?, end: Time?): String {
            val database = FakeNotificationDatabase(fixedQuietTimes = start to end)
            NotificationRepository(database.dataSource(), Json.Default) { LocalTime.NOON }
                .accept(event("OrderCreated", "{\"userId\":\"user-1\"}", "missing-$start-$end"), setOf(NotificationChannel.PUSH))
            return database.deliveryStatuses.single()
        }
        assertEquals("QUEUED", statusWithMissingBoundary(Time.valueOf("10:00:00"), null))
        assertEquals("QUEUED", statusWithMissingBoundary(null, Time.valueOf("14:00:00")))
    }

    private fun event(type: String, payload: String, eventId: String = "event-${database.inboxInsertions + 1}") = EventEnvelope(
        eventId = eventId,
        eventType = type,
        schemaVersion = 1,
        occurredAt = "2026-08-20T00:00:00Z",
        producer = "test",
        tenantId = "tenant-1",
        aggregateType = "Order",
        aggregateId = "order-1",
        correlationId = "corr-1",
        payloadJson = payload,
    )

    private enum class QuietWindow { NORMAL, OVERNIGHT, NOT_ACTIVE, NORMAL_EXPIRED }

    private class FakeNotificationDatabase(
        private val noPreferences: Boolean = false,
        private val templateFound: Boolean = false,
        private val quietWindow: QuietWindow? = null,
        private val claimEmpty: Boolean = false,
        private val activePlatform: String? = null,
        private val noInApp: Boolean = false,
        private val readAtPresent: Boolean = false,
        private val nullPreferenceTimes: Boolean = false,
        private val fixedQuietTimes: Pair<Time?, Time?>? = null,
    ) {
        var inboxInsertions = 0
        var deliveryInsertions = 0
        val executedSql = mutableListOf<String>()
        val deliveryStatuses = mutableListOf<String>()
        private val inboxEventIds = mutableSetOf<String>()

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setBoolean", "setTime", "setTimestamp" -> parameters[args!![0] as Int] = args[1]
                    "executeQuery" -> { executedSql += sql; resultSet(query(sql, parameters)) }
                    "executeUpdate" -> update(sql, parameters)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, parameters: Map<Int, Any?>): Int {
            executedSql += sql
            return when {
                sql.startsWith("INSERT INTO notification_inbox_events") -> {
                    if (inboxEventIds.add(parameters[1].toString())) { inboxInsertions++; 1 } else 0
                }
                sql.startsWith("INSERT INTO notification_deliveries") -> { deliveryInsertions++; deliveryStatuses += parameters[9].toString(); 1 }
                else -> 1
            }
        }

        private fun query(sql: String, parameters: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.contains("SELECT email_enabled,sms_enabled,push_enabled,in_app_enabled,quiet_start") -> if (noPreferences) emptyList() else listOf(mapOf<Any, Any?>(1 to false, 2 to true, 3 to true, 4 to true, 5 to if (nullPreferenceTimes) null else Time.valueOf("22:00:00"), 6 to if (nullPreferenceTimes) null else Time.valueOf("07:00:00"), 7 to "Asia/Kolkata", 8 to "hi-IN"))
            sql.contains("SELECT locale FROM notification_preferences") -> if (noPreferences) emptyList() else listOf(mapOf(1 to "hi-IN"))
            sql.contains("SELECT email_enabled,sms_enabled,push_enabled,in_app_enabled") -> if (noPreferences) emptyList() else listOf(mapOf<Any, Any?>(1 to false, 2 to true, 3 to true, 4 to true))
            sql.contains("SELECT quiet_start,quiet_end") -> fixedQuietTimes?.let { listOf(mapOf<Any, Any?>(1 to it.first, 2 to it.second)) } ?: when (quietWindow) {
                QuietWindow.NORMAL -> {
                    val now = java.time.LocalTime.now()
                    listOf(mapOf<Any, Any?>(1 to Time.valueOf(now.minusMinutes(1)), 2 to Time.valueOf(now.plusMinutes(1))))
                }
                QuietWindow.OVERNIGHT -> {
                    val now = java.time.LocalTime.now()
                    listOf(mapOf<Any, Any?>(1 to Time.valueOf(now.minusMinutes(1)), 2 to Time.valueOf(now.minusMinutes(2))))
                }
                QuietWindow.NOT_ACTIVE -> {
                    val now = java.time.LocalTime.now()
                    listOf(mapOf<Any, Any?>(1 to Time.valueOf(now.plusMinutes(1)), 2 to Time.valueOf(now.plusMinutes(2))))
                }
                QuietWindow.NORMAL_EXPIRED -> {
                    val now = java.time.LocalTime.now()
                    listOf(mapOf<Any, Any?>(1 to Time.valueOf(now.minusMinutes(2)), 2 to Time.valueOf(now.minusMinutes(1))))
                }
                null -> if (noPreferences) emptyList() else listOf(mapOf<Any, Any?>(1 to null, 2 to null))
            }
            sql.contains("SELECT subject_text,body_text,locale") && templateFound -> listOf(mapOf<Any, Any?>(1 to "Localized", 2 to "{{aggregateId}} {{status}}", 3 to "hi-IN"))
            sql.contains("SELECT subject_text,body_text,locale") && parameters[2] == "en-IN" && !noPreferences -> listOf(mapOf<Any, Any?>(1 to "Order", 2 to "{{aggregateId}} {{status}}", 3 to "en-IN"))
            sql.contains("SELECT subject_text,body_text,locale") -> emptyList()
            sql.contains("SELECT d.id,d.event_id") && claimEmpty -> emptyList()
            sql.contains("SELECT d.id,d.event_id") -> listOf(
                mapOf<Any, Any?>(1 to "delivery-1", 2 to "event-1", 3 to "user-1", 4 to "PUSH", 5 to "order.status", 6 to "en-IN", 7 to "Subject", 8 to "Body", 9 to 0, 10 to null, 11 to (activePlatform ?: "FCM")),
            )
            sql.contains("SELECT id,title,body") && noInApp -> emptyList()
            sql.contains("SELECT id,title,body") -> listOf(mapOf<Any, Any?>(1 to "in-app-1", 2 to "Title", 3 to "Body", 4 to if (readAtPresent) Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")) else null, 5 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))))
            else -> emptyList()
        }

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getBoolean" -> value(key) as? Boolean ?: false
                    "getTime" -> value(key) as? Time
                    "getTimestamp" -> value(key) as? Timestamp
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
    }
}
