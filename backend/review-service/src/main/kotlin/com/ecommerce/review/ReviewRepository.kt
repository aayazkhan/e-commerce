package com.ecommerce.review

import com.ecommerce.platform.kafka.EventEnvelope
import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class ReviewRepository(private val ds:DataSource, private val json:Json, private val autoPublish:Boolean) {
    fun accept(event:EventEnvelope) = tx { c ->
        val inserted=c.prepareStatement("INSERT INTO review_inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,event.eventId);s.setString(2,event.eventType);s.executeUpdate()>0};if(!inserted)return@tx
        val node=runCatching{json.parseToJsonElement(event.payloadJson).jsonObject}.getOrNull()?:return@tx
        val user=node["userId"]?.jsonPrimitive?.contentOrNull ?: node["user_id"]?.jsonPrimitive?.contentOrNull ?: return@tx
        val order=node["id"]?.jsonPrimitive?.contentOrNull ?: node["orderId"]?.jsonPrimitive?.contentOrNull ?: event.aggregateId
        val items=node["items"]?.toString() ?: return@tx
        val array=json.parseToJsonElement(items)
        if(array !is kotlinx.serialization.json.JsonArray)return@tx
        array.forEach{e->val item=e as? JsonObject ?: return@forEach;val product=item["productId"]?.jsonPrimitive?.contentOrNull?:return@forEach;val seller=item["sellerId"]?.jsonPrimitive?.contentOrNull;val variant=item["variantId"]?.jsonPrimitive?.contentOrNull;c.prepareStatement("INSERT INTO review_purchase_facts(user_id,order_id,product_id,variant_id,seller_id,purchased_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,user);s.setString(2,order);s.setString(3,product);s.setString(4,variant);s.setString(5,seller);s.setTimestamp(6,Timestamp.from(Instant.now()));s.executeUpdate()}}
    }
    fun create(user:String,r:ReviewRequest):ReviewResponse=tx{c->
        if(r.targetId.isBlank()||r.orderId.isBlank()||r.body.isBlank()||r.rating !in 1..5)throw ApiException(ErrorCode.VALIDATION_ERROR,"Review is invalid.",400)
        val verified=c.prepareStatement(if(r.targetType==ReviewTarget.PRODUCT)"SELECT 1 FROM review_purchase_facts WHERE user_id=? AND order_id=? AND product_id=?" else "SELECT 1 FROM review_purchase_facts WHERE user_id=? AND order_id=? AND seller_id=?").use{s->s.setString(1,user);s.setString(2,r.orderId);s.setString(3,r.targetId);s.executeQuery().use{it.next()}};if(!verified)throw ApiException(ErrorCode.CONFLICT,"A verified purchase is required to review this item.",409)
        val recent=c.prepareStatement("SELECT count(*) FROM reviews WHERE user_id=? AND created_at>now()-interval '24 hours'").use{s->s.setString(1,user);s.executeQuery().use{it.next();it.getLong(1)}};if(recent>=5)throw ApiException(ErrorCode.CONFLICT,"Review rate limit exceeded.",429)
        val id=CommerceId.new("rev").value;val status=if(autoPublish)ReviewStatus.PUBLISHED else ReviewStatus.SUBMITTED;c.prepareStatement("INSERT INTO reviews(id,user_id,target_type,target_id,order_id,rating,title,body,verified_purchase,status) VALUES (?,?,?,?,?,?,?,?,?,?)").use{s->s.setString(1,id);s.setString(2,user);s.setString(3,r.targetType.name);s.setString(4,r.targetId);s.setString(5,r.orderId);s.setInt(6,r.rating);s.setString(7,r.title?.take(240));s.setString(8,r.body.take(5000));s.setBoolean(9,true);s.setString(10,status.name);s.executeUpdate()};audit(c,id,null,status,user,"created");if(status==ReviewStatus.PUBLISHED)aggregate(c,r.targetType,r.targetId);get(c,id)!!
    }
    fun list(type:ReviewTarget,target:String,cursor:Int,limit:Int):ReviewPage=ds.connection.use{c->val size=limit.coerceIn(1,50);val rows=c.prepareStatement("SELECT id FROM reviews WHERE target_type=? AND target_id=? AND status='PUBLISHED' ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?").use{s->s.setString(1,type.name);s.setString(2,target);s.setInt(3,cursor.coerceAtLeast(0));s.setInt(4,size+1);s.executeQuery().use{buildList{while(it.next())add(it.getString(1))}}};val more=rows.size>size;val selected=rows.take(size).mapNotNull{get(c,it)};ReviewPage(selected,if(more)(cursor+size).toString()else null)}
    fun aggregate(type:ReviewTarget,target:String):RatingAggregate=ds.connection.use{c->c.prepareStatement("SELECT review_count,average FROM review_rating_aggregates WHERE target_type=? AND target_id=?").use{s->s.setString(1,type.name);s.setString(2,target);s.executeQuery().use{if(it.next())RatingAggregate(type,target,it.getLong(1),it.getDouble(2))else RatingAggregate(type,target,0,0.0)}}}
    fun vote(user:String,id:String){ds.connection.use{c->c.prepareStatement("INSERT INTO review_helpful_votes(review_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,id);s.setString(2,user);s.executeUpdate()}}}
    fun report(user:String,id:String,r:ReportRequest){if(r.reason.isBlank())throw ApiException(ErrorCode.VALIDATION_ERROR,"Report reason is required.",400);ds.connection.use{c->c.prepareStatement("INSERT INTO review_reports(review_id,user_id,reason) VALUES (?,?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,id);s.setString(2,user);s.setString(3,r.reason.take(500));s.executeUpdate()}}}
    fun moderate(id:String,request:ModerationRequest,actor:String):ReviewResponse=tx{c->val old=get(c,id)?.status?:throw ApiException(ErrorCode.NOT_FOUND,"Review not found.",404);if(old==request.status)return@tx get(c,id)!!;c.prepareStatement("UPDATE reviews SET status=?,updated_at=now() WHERE id=?").use{s->s.setString(1,request.status.name);s.setString(2,id);s.executeUpdate()};audit(c,id,old,request.status,actor,request.reason);if(old==ReviewStatus.PUBLISHED)aggregate(c,get(c,id)!!.targetType,get(c,id)!!.targetId);if(request.status==ReviewStatus.PUBLISHED){val x=get(c,id)!!;aggregate(c,x.targetType,x.targetId)};get(c,id)!!}
    fun dlq(event:EventEnvelope?,e:Throwable)=ds.connection.use{c->c.prepareStatement("INSERT INTO review_dlq(event_id,error_text) VALUES (?,?)").use{s->s.setString(1,event?.eventId);s.setString(2,e.message?.take(2000) ?: "consumer failure");s.executeUpdate()}}
    private fun get(c:Connection,id:String):ReviewResponse?=c.prepareStatement("SELECT r.*, (SELECT count(*) FROM review_helpful_votes v WHERE v.review_id=r.id) AS helpful_count FROM reviews r WHERE r.id=?").use{s->s.setString(1,id);s.executeQuery().use{if(!it.next())null else ReviewResponse(it.getString("id"),it.getString("user_id"),ReviewTarget.valueOf(it.getString("target_type")),it.getString("target_id"),it.getString("order_id"),it.getInt("rating"),it.getString("title"),it.getString("body"),it.getBoolean("verified_purchase"),ReviewStatus.valueOf(it.getString("status")),it.getInt("helpful_count"),it.getTimestamp("created_at").toInstant().toString())}}
    private fun aggregate(c:Connection,type:ReviewTarget,target:String){c.prepareStatement("DELETE FROM review_rating_aggregates WHERE target_type=? AND target_id=?").use{s->s.setString(1,type.name);s.setString(2,target);s.executeUpdate()};c.prepareStatement("INSERT INTO review_rating_aggregates(target_type,target_id,review_count,rating_sum,average) SELECT target_type,target_id,count(*),coalesce(sum(rating),0),coalesce(avg(rating),0) FROM reviews WHERE target_type=? AND target_id=? AND status='PUBLISHED' GROUP BY target_type,target_id").use{s->s.setString(1,type.name);s.setString(2,target);s.executeUpdate()}}
    private fun audit(c:Connection,id:String,from:ReviewStatus?,to:ReviewStatus,actor:String,reason:String?)=c.prepareStatement("INSERT INTO review_moderation_audit(review_id,from_status,to_status,actor_id,reason) VALUES (?,?,?,?,?)").use{s->s.setString(1,id);s.setString(2,from?.name);s.setString(3,to.name);s.setString(4,actor);s.setString(5,reason);s.executeUpdate()}
    private fun <T>tx(b:(Connection)->T):T=ds.connection.use{c->c.autoCommit=false;try{b(c).also{c.commit()}}catch(e:Throwable){c.rollback();throw e}}
}
