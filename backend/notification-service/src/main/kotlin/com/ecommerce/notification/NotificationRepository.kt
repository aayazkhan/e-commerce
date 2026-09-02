package com.ecommerce.notification

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Timestamp
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource

data class Delivery(val id: String, val eventId: String, val userId: String, val channel: NotificationChannel, val templateKey: String, val locale: String, val subject: String, val body: String, val attempts: Int, val provider: String?, val platform: String = "FCM")

class NotificationRepository(
    private val dataSource: DataSource,
    private val json: Json,
    private val currentTime: () -> LocalTime = LocalTime::now,
) : NotificationStore {
    fun accept(event: EventEnvelope, defaultChannels: Set<NotificationChannel>) = tx { c ->
        val inserted = c.prepareStatement("INSERT INTO notification_inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING").use { s -> s.setString(1,event.eventId); s.setString(2,event.eventType); s.executeUpdate() > 0 }
        if (!inserted) return@tx
        val node = runCatching { json.parseToJsonElement(event.payloadJson).jsonObject }.getOrNull() ?: return@tx
        val userId = node["userId"]?.jsonPrimitive?.content ?: node["user_id"]?.jsonPrimitive?.content ?: return@tx
        val status = node["status"]?.jsonPrimitive?.content ?: event.eventType
        val template = when { event.eventType.startsWith("Payment") -> "payment.status"; event.eventType.startsWith("Shipment") -> "shipment.status"; event.eventType.startsWith("Refund") -> "refund.status"; else -> "order.status" }
        val aggregateId = event.aggregateId
        val locale = preferenceLocale(c,userId)
        val copy = template(c,template,locale) ?: template(c,template,"en-IN") ?: Triple(template,template,template)
        val values = mapOf("aggregateId" to aggregateId, "status" to status)
        defaultChannels.forEach { channel ->
            if (!enabled(c,userId,channel) || quiet(c,userId)) {
                c.prepareStatement("INSERT INTO notification_deliveries(id,event_id,user_id,channel,template_key,locale,subject_text,body_text,status,provider) VALUES (?,?,?,?,?,?,?,?,?,'local') ON CONFLICT DO NOTHING").use { s -> bind(s, "nd_${UUID.randomUUID()}",event.eventId,userId,channel,template,locale,render(copy.first,values),render(copy.second,values),"SUPPRESSED") }
            } else {
                val id="nd_${UUID.randomUUID()}"; c.prepareStatement("INSERT INTO notification_deliveries(id,event_id,user_id,channel,template_key,locale,subject_text,body_text,status,provider) VALUES (?,?,?,?,?,?,?,?,?,'local') ON CONFLICT DO NOTHING").use { s -> bind(s,id,event.eventId,userId,channel,template,locale,render(copy.first,values),render(copy.second,values),"QUEUED") }
            }
        }
    }

    fun claimDue(): Delivery? = tx { c ->
        c.prepareStatement("SELECT d.id,d.event_id,d.user_id,d.channel,d.template_key,d.locale,d.subject_text,d.body_text,d.attempts,d.provider,coalesce((SELECT platform FROM notification_devices n WHERE n.user_id=d.user_id AND n.active LIMIT 1),'FCM') FROM notification_deliveries d WHERE (d.status IN ('QUEUED','FAILED') AND d.next_attempt_at<=now()) OR (d.status='PROCESSING' AND d.updated_at < now() - interval '5 minutes') ORDER BY d.created_at FOR UPDATE SKIP LOCKED LIMIT 1").use { s -> s.executeQuery().use { r ->
            if (!r.next()) null else { val d=Delivery(r.getString(1),r.getString(2),r.getString(3),NotificationChannel.valueOf(r.getString(4)),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getInt(9),r.getString(10),r.getString(11)); c.prepareStatement("UPDATE notification_deliveries SET status='PROCESSING',updated_at=now() WHERE id=?").use { u->u.setString(1,d.id);u.executeUpdate() }; d }
        } }
    }
    fun sent(id:String, provider:String, messageId:String?) = update("UPDATE notification_deliveries SET status='SENT',provider=?,provider_message_id=?,updated_at=now() WHERE id=?",provider,messageId,id)
    fun inApp(id:String,userId:String,subject:String,body:String) = tx { c -> c.prepareStatement("INSERT INTO in_app_notifications(id,user_id,title,body) VALUES (?,?,?,?) ON CONFLICT DO NOTHING").use { s->s.setString(1,id);s.setString(2,userId);s.setString(3,subject);s.setString(4,body);s.executeUpdate() } }
    fun failed(d:Delivery,error:Throwable) = tx { c -> val next=d.attempts+1; if(NotificationRetryPolicy.isTerminal(next)){c.prepareStatement("UPDATE notification_deliveries SET status='DLQ',attempts=?,last_error=?,updated_at=now() WHERE id=?").use{s->s.setInt(1,next);s.setString(2,error.message?.take(1000));s.setString(3,d.id);s.executeUpdate()};c.prepareStatement("INSERT INTO notification_dlq(delivery_id,event_id,error_text,attempts) VALUES (?,?,?,?)").use{s->s.setString(1,d.id);s.setString(2,d.eventId);s.setString(3,error.message?.take(2000));s.setInt(4,next);s.executeUpdate()}}else{c.prepareStatement("UPDATE notification_deliveries SET status='FAILED',attempts=?,last_error=?,next_attempt_at=now() + (? || ' seconds')::interval,updated_at=now() WHERE id=?").use{s->s.setInt(1,next);s.setString(2,error.message?.take(1000));s.setInt(3,NotificationRetryPolicy.backoffSeconds(next));s.setString(4,d.id);s.executeUpdate()}} }
    override fun updateWebhook(messageId:String,status:String) = update("UPDATE notification_deliveries SET status=?,updated_at=now() WHERE provider_message_id=?",if(status.uppercase() in setOf("DELIVERED","SENT")) status.uppercase() else "FAILED",messageId)
    override fun getPreferences(userId:String):PreferenceRequest = dataSource.connection.use { c -> c.prepareStatement("SELECT email_enabled,sms_enabled,push_enabled,in_app_enabled,quiet_start,quiet_end,timezone,locale FROM notification_preferences WHERE user_id=?").use { s -> s.setString(1,userId); s.executeQuery().use { r -> if(!r.next()) PreferenceRequest() else PreferenceRequest(r.getBoolean(1),r.getBoolean(2),r.getBoolean(3),r.getBoolean(4),r.getTime(5)?.toLocalTime()?.toString(),r.getTime(6)?.toLocalTime()?.toString(),r.getString(7),r.getString(8)) } } }
    override fun preferences(userId:String, p:PreferenceRequest) = update("INSERT INTO notification_preferences(user_id,email_enabled,sms_enabled,push_enabled,in_app_enabled,quiet_start,quiet_end,timezone,locale) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET email_enabled=EXCLUDED.email_enabled,sms_enabled=EXCLUDED.sms_enabled,push_enabled=EXCLUDED.push_enabled,in_app_enabled=EXCLUDED.in_app_enabled,quiet_start=EXCLUDED.quiet_start,quiet_end=EXCLUDED.quiet_end,timezone=EXCLUDED.timezone,locale=EXCLUDED.locale,updated_at=now()",userId,p.emailEnabled,p.smsEnabled,p.pushEnabled,p.inAppEnabled,p.quietStart,p.quietEnd,p.timezone,p.locale)
    override fun device(userId:String,d:DeviceRequest) = update("INSERT INTO notification_devices(id,user_id,platform,token_hash) VALUES (?,?,?,?) ON CONFLICT(user_id,token_hash) DO UPDATE SET active=true", "dev_${UUID.randomUUID()}",userId,d.platform,MessageDigest.getInstance("SHA-256").digest(d.token.toByteArray()).joinToString(""){b->"%02x".format(b)})
    override fun listInApp(userId:String,limit:Int):List<InAppResponse> = dataSource.connection.use { c->c.prepareStatement("SELECT id,title,body,read_at,created_at FROM in_app_notifications WHERE user_id=? ORDER BY created_at DESC LIMIT ?").use{s->s.setString(1,userId);s.setInt(2,limit.coerceIn(1,100));s.executeQuery().use{r->buildList{while(r.next())add(InAppResponse(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4)==null,r.getTimestamp(5).toInstant().toString()))}}}}
    override fun markRead(userId:String,id:String) = update("UPDATE in_app_notifications SET read_at=now() WHERE id=? AND user_id=?",id,userId)
    override fun template(t:TemplateRequest) = update("INSERT INTO notification_templates(template_key,locale,subject_text,body_text) VALUES (?,?,?,?) ON CONFLICT(template_key,locale) DO UPDATE SET subject_text=EXCLUDED.subject_text,body_text=EXCLUDED.body_text,version=notification_templates.version+1",t.templateKey,t.locale,t.subject,t.body)
    fun dlq(event:EventEnvelope?, error:Throwable) = update("INSERT INTO notification_dlq(event_id,error_text,attempts) VALUES (?,?,0)",event?.eventId,error.message?.take(2000) ?: "consumer failure")

    private fun preferenceLocale(c:Connection,user:String):String = c.prepareStatement("SELECT locale FROM notification_preferences WHERE user_id=?").use { s -> s.setString(1,user); s.executeQuery().use { if(it.next()) it.getString(1) else "en-IN" } }
    private fun enabled(c:Connection,user:String,ch:NotificationChannel):Boolean = c.prepareStatement("SELECT email_enabled,sms_enabled,push_enabled,in_app_enabled FROM notification_preferences WHERE user_id=?").use { s ->
        s.setString(1,user)
        s.executeQuery().use { r ->
            if(!r.next()) true else when(ch) { NotificationChannel.EMAIL -> r.getBoolean(1); NotificationChannel.SMS -> r.getBoolean(2); NotificationChannel.PUSH -> r.getBoolean(3); NotificationChannel.IN_APP -> r.getBoolean(4) }
        }
    }
    private fun quiet(c:Connection,user:String):Boolean = c.prepareStatement("SELECT quiet_start,quiet_end FROM notification_preferences WHERE user_id=?").use { s ->
        s.setString(1,user)
        s.executeQuery().use { r ->
            if(!r.next() || r.getTime(1)==null || r.getTime(2)==null) false else { val start=r.getTime(1).toLocalTime(); val end=r.getTime(2).toLocalTime(); val now=currentTime(); if(start<=end) now>=start&&now<end else now>=start||now<end }
        }
    }
    private fun template(c:Connection,key:String,locale:String):Triple<String,String,String>? = c.prepareStatement("SELECT subject_text,body_text,locale FROM notification_templates WHERE template_key=? AND locale=? AND active").use { s -> s.setString(1,key); s.setString(2,locale); s.executeQuery().use { if(it.next()) Triple(it.getString(1),it.getString(2),it.getString(3)) else null } }
    private fun render(v:String,values:Map<String,String>)=values.entries.fold(v){a,(k,x)->a.replace("{{$k}}",x)}
    private fun bind(s:java.sql.PreparedStatement,id:String,event:String,user:String,ch:NotificationChannel,key:String,locale:String,subject:String,body:String,status:String){s.setString(1,id);s.setString(2,event);s.setString(3,user);s.setString(4,ch.name);s.setString(5,key);s.setString(6,locale);s.setString(7,subject);s.setString(8,body);s.setString(9,status);s.executeUpdate()}
    private fun update(sql:String,vararg values:Any?) = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value ->
                when (value) { is Boolean -> statement.setBoolean(index + 1, value); is Int -> statement.setInt(index + 1, value); else -> statement.setString(index + 1, value?.toString()) }
            }
            statement.executeUpdate()
        }
    }
    private fun <T> tx(block:(Connection)->T):T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try { block(connection).also { connection.commit() } } catch (error:Throwable) { connection.rollback(); throw error }
    }
}
