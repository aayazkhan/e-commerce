package com.ecommerce.analytics

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

private data class StoredEvent(val id:String,val type:String,val occurredAt:String,val producer:String,val aggregateId:String,val metrics:String)

class AnalyticsRepository(private val ds:DataSource, private val json:Json) : AnalyticsStore {
    fun accept(event:EventEnvelope) = tx { c ->
        val inserted = c.prepareStatement("INSERT INTO analytics_inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING").use { s -> s.setString(1,event.eventId); s.setString(2,event.eventType); s.executeUpdate() > 0 }
        if (!inserted) return@tx
        val snapshot = safePayload(event.payloadJson)
        c.prepareStatement("INSERT INTO analytics_events(event_id,event_type,occurred_at,producer,aggregate_id,user_hash,metric_json) VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING").use { s -> s.setString(1,event.eventId); s.setString(2,event.eventType); s.setTimestamp(3,Timestamp.from(parse(event.occurredAt))); s.setString(4,event.producer); s.setString(5,event.aggregateId); s.setString(6,extractUserHash(event.payloadJson)); s.setString(7,snapshot.toString()); s.executeUpdate() }
        apply(c,event,snapshot)
    }

    override fun summary(from:String?,to:String?):MetricSummary = ds.connection.use { c ->
        val where = buildString { append("WHERE 1=1"); if (from != null) append(" AND bucket>=?"); if (to != null) append(" AND bucket<?") }
        c.prepareStatement("SELECT coalesce(sum(events),0),coalesce(sum(views),0),coalesce(sum(searches),0),coalesce(sum(carts),0),coalesce(sum(orders),0),coalesce(sum(paid_orders),0),coalesce(sum(checkouts),0),coalesce(sum(revenue_minor),0) FROM analytics_daily $where").use { s ->
            var index=1; if(from!=null)s.setObject(index++,LocalDate.parse(from)); if(to!=null)s.setObject(index,LocalDate.parse(to))
            s.executeQuery().use { r -> r.next(); val events=r.getLong(1); val views=r.getLong(2); val searches=r.getLong(3); val carts=r.getLong(4); val orders=r.getLong(5); val paid=r.getLong(6); val checkouts=r.getLong(7); val revenue=r.getLong(8); MetricSummary(from,to,events,views,searches,carts,orders,paid,checkouts,revenue,if(orders==0L)0.0 else paid.toDouble()/orders,if(carts==0L)0.0 else (carts-paid).coerceAtLeast(0L).toDouble()/carts) }
        }
    }

    override fun replay(request:ReplayRequest):ReplayResponse = tx { c ->
        val run="replay_${UUID.randomUUID()}"; val sql=buildString { append("SELECT event_id,event_type,occurred_at,producer,aggregate_id,metric_json FROM analytics_events WHERE 1=1"); if(request.from!=null)append(" AND occurred_at>=?"); if(request.to!=null)append(" AND occurred_at<?") }
        val stored = c.prepareStatement(sql).use { s -> var index=1; if(request.from!=null)s.setTimestamp(index++,parseTimestamp(request.from)); if(request.to!=null)s.setTimestamp(index,parseTimestamp(request.to)); s.executeQuery().use { r -> buildList { while(r.next())add(StoredEvent(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant().toString(),r.getString(4),r.getString(5),r.getString(6))) } } }
        stored.forEach { row -> c.prepareStatement("INSERT INTO analytics_replay_audit(run_id,event_id) VALUES (?,?) ON CONFLICT DO NOTHING").use { s -> s.setString(1,run); s.setString(2,row.id); s.executeUpdate() }; apply(c,EventEnvelope(row.id,row.type,1,row.occurredAt,row.producer,"default",row.type,row.aggregateId,run,null,row.metrics),json.parseToJsonElement(row.metrics)) }
        ReplayResponse(run,stored.size)
    }

    fun dlq(event:EventEnvelope?,error:Throwable)=ds.connection.use{c->c.prepareStatement("INSERT INTO analytics_dlq(event_id,error_text) VALUES (?,?)").use{s->s.setString(1,event?.eventId);s.setString(2,error.message?.take(2000) ?: "consumer failure");s.executeUpdate()}}

    private fun apply(c:Connection,event:EventEnvelope,payload:JsonElement) {
        val marker=c.prepareStatement("INSERT INTO analytics_applied_events(event_id,projection) VALUES (?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,event.eventId);s.setString(2,"commerce-v1");s.executeUpdate()>0}; if(!marker)return
        val node=payload.jsonObject; val amount=node["amountMinor"]?.jsonPrimitive?.longOrNull ?: node["totalMinor"]?.jsonPrimitive?.longOrNull ?: 0L
        val views=if(event.eventType.contains("Viewed",true))1L else 0L; val searches=if(event.eventType.contains("Search",true))1L else 0L; val carts=if(event.eventType.contains("Cart",true))1L else 0L; val orders=if(event.eventType=="OrderCreated")1L else 0L; val paid=if(event.eventType=="PaymentCaptured"||event.eventType=="OrderPaid")1L else 0L; val checkouts=if(event.eventType.contains("Checkout",true))1L else 0L; val revenue=if(paid==1L)amount else 0L
        metric(c,"analytics_hourly",parse(event.occurredAt),views,searches,carts,orders,paid,checkouts,revenue); metric(c,"analytics_daily",parse(event.occurredAt),views,searches,carts,orders,paid,checkouts,revenue)
        val eventDate=parse(event.occurredAt); val items=node["items"]
        if(items is JsonArray) items.forEach { item ->
            val obj=item.jsonObject; val product=obj["productId"]?.jsonPrimitive?.contentOrNull; val seller=obj["sellerId"]?.jsonPrimitive?.contentOrNull
            if(product!=null)c.prepareStatement("INSERT INTO analytics_product_metrics(bucket,product_id,views,orders,revenue_minor) VALUES (date(?),?,?,?,?) ON CONFLICT(bucket,product_id) DO UPDATE SET views=analytics_product_metrics.views+EXCLUDED.views,orders=analytics_product_metrics.orders+EXCLUDED.orders,revenue_minor=analytics_product_metrics.revenue_minor+EXCLUDED.revenue_minor").use{s->s.setTimestamp(1,Timestamp.from(eventDate));s.setString(2,product);s.setLong(3,views);s.setLong(4,orders);s.setLong(5,revenue);s.executeUpdate()}
            if(seller!=null)c.prepareStatement("INSERT INTO analytics_seller_metrics(bucket,seller_id,orders,revenue_minor) VALUES (date(?),?,?,?) ON CONFLICT(bucket,seller_id) DO UPDATE SET orders=analytics_seller_metrics.orders+EXCLUDED.orders,revenue_minor=analytics_seller_metrics.revenue_minor+EXCLUDED.revenue_minor").use{s->s.setTimestamp(1,Timestamp.from(eventDate));s.setString(2,seller);s.setLong(3,orders);s.setLong(4,revenue);s.executeUpdate()}
        }
    }

    private fun metric(c:Connection,table:String,at:Instant,views:Long,searches:Long,carts:Long,orders:Long,paid:Long,checkouts:Long,revenue:Long){val hourly=table.contains("hourly");val sql=if(hourly)"INSERT INTO analytics_hourly(bucket,events,views,searches,carts,orders,paid_orders,checkouts,revenue_minor) VALUES (date_trunc('hour',?),1,?,?,?,?,?,?,?) ON CONFLICT(bucket) DO UPDATE SET events=analytics_hourly.events+1,views=analytics_hourly.views+EXCLUDED.views,searches=analytics_hourly.searches+EXCLUDED.searches,carts=analytics_hourly.carts+EXCLUDED.carts,orders=analytics_hourly.orders+EXCLUDED.orders,paid_orders=analytics_hourly.paid_orders+EXCLUDED.paid_orders,checkouts=analytics_hourly.checkouts+EXCLUDED.checkouts,revenue_minor=analytics_hourly.revenue_minor+EXCLUDED.revenue_minor" else "INSERT INTO analytics_daily(bucket,events,views,searches,carts,orders,paid_orders,checkouts,revenue_minor) VALUES (date(?),1,?,?,?,?,?,?,?) ON CONFLICT(bucket) DO UPDATE SET events=analytics_daily.events+1,views=analytics_daily.views+EXCLUDED.views,searches=analytics_daily.searches+EXCLUDED.searches,carts=analytics_daily.carts+EXCLUDED.carts,orders=analytics_daily.orders+EXCLUDED.orders,paid_orders=analytics_daily.paid_orders+EXCLUDED.paid_orders,checkouts=analytics_daily.checkouts+EXCLUDED.checkouts,revenue_minor=analytics_daily.revenue_minor+EXCLUDED.revenue_minor";c.prepareStatement(sql).use{s->s.setTimestamp(1,Timestamp.from(at));s.setLong(2,views);s.setLong(3,searches);s.setLong(4,carts);s.setLong(5,orders);s.setLong(6,paid);s.setLong(7,checkouts);s.setLong(8,revenue);s.executeUpdate()}}
    private fun safePayload(raw:String):JsonElement{val n=runCatching{json.parseToJsonElement(raw).jsonObject}.getOrElse{return buildJsonObject{}};return buildJsonObject{n["amountMinor"]?.jsonPrimitive?.longOrNull?.let{put("amountMinor",it)};n["totalMinor"]?.jsonPrimitive?.longOrNull?.let{put("totalMinor",it)};n["items"]?.let{if(it is JsonArray)put("items",JsonArray(it.map{item->buildJsonObject{item.jsonObject["productId"]?.jsonPrimitive?.contentOrNull?.let{x->put("productId",x)};item.jsonObject["sellerId"]?.jsonPrimitive?.contentOrNull?.let{x->put("sellerId",x)}}}))}}}
    private fun extractUserHash(raw:String):String? = runCatching { json.parseToJsonElement(raw).jsonObject["userId"]?.jsonPrimitive?.contentOrNull?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)} } }.getOrNull()
    private fun parse(v:String)=runCatching{Instant.parse(v)}.getOrElse{OffsetDateTime.parse(v).toInstant()}
    private fun parseTimestamp(v:String)=runCatching{Timestamp.from(parse(v))}.getOrElse{Timestamp.valueOf(LocalDate.parse(v).atStartOfDay())}
    private fun <T>tx(block:(Connection)->T):T=ds.connection.use{c->c.autoCommit=false;try{block(c).also{c.commit()}}catch(e:Throwable){c.rollback();throw e}}
}
