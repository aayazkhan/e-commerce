package com.ecommerce.recommendation

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class RecommendationRepository(private val ds:DataSource,private val json:Json): RecommendationStore {
    fun accept(event:EventEnvelope)=tx{c->val inserted=c.prepareStatement("INSERT INTO recommendation_inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING").use{s->s.setString(1,event.eventId);s.setString(2,event.eventType);s.executeUpdate()>0};if(!inserted)return@tx;val node=runCatching{json.parseToJsonElement(event.payloadJson).jsonObject}.getOrNull()?:return@tx;val user=node["userId"]?.jsonPrimitive?.contentOrNull?:node["user_id"]?.jsonPrimitive?.contentOrNull;val product=node["productId"]?.jsonPrimitive?.contentOrNull?:node["product_id"]?.jsonPrimitive?.contentOrNull;record(c,event.eventId,user,null,event.eventType,product,null,event.occurredAt);if(product!=null)popular(c,product,1.0);val items=node["items"];if(items is JsonArray){val ids=items.mapNotNull{(it as? JsonObject)?.get("productId")?.jsonPrimitive?.contentOrNull};ids.forEach{a->ids.filter{it!=a}.forEach{b->pair(c,a,b)}}}}
    override fun record(request:BehaviorRequest)=tx{c->record(c,"api_${UUID.randomUUID()}",request.userId,request.sessionId,request.eventType,request.productId,request.query,Instant.now().toString());request.productId?.let{popular(c,it,1.0)}}
    override fun recommendations(type:RecommendationType,user:String?,product:String?,limit:Int):List<String>{val size=limit.coerceIn(1,50);return ds.connection.use{c->when(type){RecommendationType.RECENTLY_VIEWED->if(user==null)emptyList()else c.query("SELECT product_id FROM recommendation_behavior_events WHERE user_id=? AND product_id IS NOT NULL ORDER BY occurred_at DESC LIMIT ?",user,size);RecommendationType.FREQUENTLY_BOUGHT_TOGETHER->if(product==null)emptyList()else c.query("SELECT product_b FROM recommendation_product_pairs WHERE product_a=? ORDER BY score DESC LIMIT ?",product,size);RecommendationType.SIMILAR->if(product==null)popular(c,size)else c.query("SELECT product_b FROM recommendation_product_pairs WHERE product_a=? ORDER BY score DESC LIMIT ?",product,size);RecommendationType.TRENDING->popular(c,size);RecommendationType.PERSONALIZED->{val recent=if(user==null)emptyList()else c.query("SELECT product_id FROM recommendation_behavior_events WHERE user_id=? AND product_id IS NOT NULL ORDER BY occurred_at DESC LIMIT ?",user,size);(recent+popular(c,size)).distinct().take(size)}}}}
    override fun popular(limit:Int)=ds.connection.use{popular(it,limit)}
    fun dlq(event:EventEnvelope?,e:Throwable)=ds.connection.use{c->c.prepareStatement("INSERT INTO recommendation_dlq(event_id,error_text) VALUES (?,?)").use{s->s.setString(1,event?.eventId);s.setString(2,e.message?.take(2000)?:"consumer failure");s.executeUpdate()}}
    private fun record(c:Connection,id:String,user:String?,session:String?,type:String,product:String?,query:String?,occurred:String){c.prepareStatement("INSERT INTO recommendation_behavior_events(event_id,user_id,session_id,event_type,product_id,query_text,occurred_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING").use{s->s.setString(1,id);s.setString(2,user);s.setString(3,session);s.setString(4,type);s.setString(5,product);s.setString(6,query);s.setTimestamp(7,Timestamp.from(runCatching{Instant.parse(occurred)}.getOrDefault(Instant.now())));s.executeUpdate()}}
    private fun popular(c:Connection,product:String,score:Double)=c.prepareStatement("INSERT INTO recommendation_popular_products(product_id,score) VALUES (?,?) ON CONFLICT(product_id) DO UPDATE SET score=recommendation_popular_products.score+EXCLUDED.score,last_seen=now()").use{s->s.setString(1,product);s.setDouble(2,score);s.executeUpdate()}
    private fun pair(c:Connection,a:String,b:String)=c.prepareStatement("INSERT INTO recommendation_product_pairs(product_a,product_b,score) VALUES (?,?,1) ON CONFLICT(product_a,product_b) DO UPDATE SET score=recommendation_product_pairs.score+1").use{s->s.setString(1,a);s.setString(2,b);s.executeUpdate()}
    private fun popular(c:Connection,n:Int)=c.queryLimit("SELECT product_id FROM recommendation_popular_products ORDER BY score DESC,last_seen DESC LIMIT ?",n)
    private fun Connection.query(sql:String,value:String,limit:Int):List<String>{prepareStatement(sql).use{s->s.setString(1,value);s.setInt(2,limit);s.executeQuery().use{r->return buildList{while(r.next())add(r.getString(1))}}}}
    private fun Connection.queryLimit(sql:String,limit:Int):List<String>{prepareStatement(sql).use{s->s.setInt(1,limit);s.executeQuery().use{r->return buildList{while(r.next())add(r.getString(1))}}}}
    private fun <T>tx(b:(Connection)->T):T=ds.connection.use{c->c.autoCommit=false;try{b(c).also{c.commit()}}catch(e:Throwable){c.rollback();throw e}}
}
