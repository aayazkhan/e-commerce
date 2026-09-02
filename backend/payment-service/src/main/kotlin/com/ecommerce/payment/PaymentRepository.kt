package com.ecommerce.payment

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

class PaymentRepository(private val dataSource: DataSource, private val providers: Map<PaymentProviderName, PaymentProvider>) : ServiceOutboxStore, PaymentStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    override fun create(userId: String, request: PaymentCreateRequest, key: String, correlationId: String): PaymentResponse {
        validate(request)
        val hash = sha256(json.encodeToString(request))
        val initial = transaction { c ->
            val prior = c.prepareStatement("SELECT request_hash,id FROM payment_intents WHERE user_id=? AND idempotency_key=?").use { s -> s.setString(1,userId);s.setString(2,key);s.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getString(2) else null } }
            if (prior != null) { if(prior.first != hash) throw ApiException(ErrorCode.CONFLICT,"Idempotency key was reused with a different payment.",409); return@transaction get(c,prior.second)!! }
            val now=Instant.now();val id=CommerceId.new("pay").value
            try { c.prepareStatement("INSERT INTO payment_intents(id,user_id,order_id,provider,status,amount_minor,currency,idempotency_key,request_hash,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)").use{s->s.setString(1,id);s.setString(2,userId);s.setString(3,request.orderId);s.setString(4,request.provider.name);s.setString(5,PaymentStatus.PROCESSING.name);s.setLong(6,request.amountMinor);s.setString(7,request.currency);s.setString(8,key);s.setString(9,hash);s.setTimestamp(10,now.ts());s.setTimestamp(11,now.ts());s.executeUpdate()};c.prepareStatement("INSERT INTO payment_attempts(id,payment_id,attempt_no,status,created_at) VALUES (?,?,?,?,?)").use{s->s.setString(1,CommerceId.new("patt").value);s.setString(2,id);s.setInt(3,1);s.setString(4,PaymentStatus.PROCESSING.name);s.setTimestamp(5,now.ts());s.executeUpdate()} } catch(e:SQLException){if(e.sqlState=="23505") throw ApiException(ErrorCode.CONFLICT,"Payment idempotency key is already in use.",409);throw e}
            get(c,id)!!
        }
        val provider = providers[request.provider] ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider is unavailable.",503,true)
        val providerPayment = try { provider.create(ProviderCreateRequest(initial.id,request.orderId,request.amountMinor,request.currency,request.paymentMethodToken,request.returnUrl)) } catch (error: Exception) { throw error }
        return finalize(initial.id,providerPayment,correlationId)
    }

    override fun getOwned(userId: String,id:String):PaymentResponse?=withConnection{get(it,id,userId)}
    override fun getInternal(id:String):PaymentResponse?=withConnection{get(it,id)}

    override fun webhook(providerName: PaymentProviderName, request: PaymentWebhookRequest, correlationId: String): PaymentResponse {
        return transaction { c ->
            val now=Instant.now();val inserted=c.prepareStatement("INSERT INTO payment_webhooks(id,provider,provider_event_id,payload_json,received_at,processed_at) VALUES (?,?,?,?,?,?) ON CONFLICT(provider,provider_event_id) DO NOTHING").use{s->s.setString(1,CommerceId.new("pwh").value);s.setString(2,providerName.name);s.setString(3,request.providerEventId);s.setString(4,json.encodeToString(request.payload));s.setTimestamp(5,now.ts());s.setTimestamp(6,now.ts());s.executeUpdate()}
            val payment=getByProvider(c,request.providerPaymentId,true) ?: throw ApiException(ErrorCode.NOT_FOUND,"Payment not found.",404)
            if(payment.amountMinor!=request.amountMinor || payment.currency!=request.currency) throw ApiException(ErrorCode.CONFLICT,"Payment webhook amount does not match the intent.",409)
            updateStatus(c,payment.id,request.status,request.providerPaymentId,null,correlationId,now)
            get(c,payment.id)!!
        }
    }

    override fun refund(id:String, userId:String?, request:RefundPaymentRequest, correlationId:String):RefundPaymentResponse {
        if(request.amountMinor<=0 || !request.currency.matches(Regex("[A-Z]{3}"))) throw ApiException(ErrorCode.VALIDATION_ERROR,"Refund request is invalid.",400)
        val prior = withConnection { c -> c.prepareStatement("SELECT amount_minor,status,provider_transaction_id,created_at FROM payment_transactions WHERE payment_id=? AND type='REFUND' AND idempotency_key=?").use { s -> s.setString(1,id); s.setString(2,request.idempotencyKey); s.executeQuery().use { r -> if (r.next()) Triple(r.getLong(1), r.getString(2), r.getString(3) to r.getTimestamp(4).toInstant().toString()) else null } } }
        if (prior != null) return RefundPaymentResponse(id, prior.first, if (prior.second == "SUCCESS") PaymentStatus.REFUNDED else PaymentStatus.REFUND_PENDING, prior.third.first, prior.third.second)
        val reservation=transaction { c ->
            val payment=get(c,id,userId,true) ?: throw ApiException(ErrorCode.NOT_FOUND,"Payment not found.",404)
            if(payment.currency!=request.currency || payment.status !in setOf(PaymentStatus.CAPTURED,PaymentStatus.PARTIALLY_REFUNDED,PaymentStatus.AUTHORIZED)) throw ApiException(ErrorCode.CONFLICT,"Payment is not refundable.",409)
            val refunded=c.prepareStatement("SELECT COALESCE(SUM(amount_minor),0) FROM payment_transactions WHERE payment_id=? AND type='REFUND' AND status IN ('PENDING','SUCCESS')").use{s->s.setString(1,id);s.executeQuery().use{r->r.next();r.getLong(1)}}
            if(refunded+request.amountMinor>payment.amountMinor) throw ApiException(ErrorCode.CONFLICT,"Refund exceeds the refundable amount.",409)
            val now=Instant.now();c.prepareStatement("INSERT INTO payment_transactions(id,payment_id,type,provider_transaction_id,amount_minor,currency,status,idempotency_key,created_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(payment_id,type,idempotency_key) DO NOTHING").use{s->s.setString(1,CommerceId.new("ptr").value);s.setString(2,id);s.setString(3,"REFUND");s.setString(4,null);s.setLong(5,request.amountMinor);s.setString(6,request.currency);s.setString(7,"PENDING");s.setString(8,request.idempotencyKey);s.setTimestamp(9,now.ts());s.executeUpdate()}
            if(!payment.providerPaymentId.isNullOrBlank()) payment else throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider reference is missing.",503,true)
        }
        val provider=providers[reservation.provider] ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider is unavailable.",503,true)
        val result=provider.refund(reservation.providerPaymentId!!,request.amountMinor,request.currency,request.idempotencyKey)
        return transaction { c ->
            val now=Instant.now();c.prepareStatement("UPDATE payment_transactions SET provider_transaction_id=?,status=? WHERE payment_id=? AND type='REFUND' AND idempotency_key=?").use{s->s.setString(1,result.providerRefundId);s.setString(2,if(result.status in setOf(PaymentStatus.REFUNDED,PaymentStatus.PARTIALLY_REFUNDED))"SUCCESS" else "FAILED");s.setString(3,id);s.setString(4,request.idempotencyKey);s.executeUpdate()}
            val refunded=c.prepareStatement("SELECT COALESCE(SUM(amount_minor),0) FROM payment_transactions WHERE payment_id=? AND type='REFUND' AND status='SUCCESS'").use{s->s.setString(1,id);s.executeQuery().use{r->r.next();r.getLong(1)}}
            val status=if(refunded>=reservation.amountMinor)PaymentStatus.REFUNDED else PaymentStatus.PARTIALLY_REFUNDED
            updateStatus(c,id,status,reservation.providerPaymentId,null,correlationId,now)
            RefundPaymentResponse(id,request.amountMinor,status,result.providerRefundId,now.toString())
        }
    }

    fun reconcile(limit: Int, correlationId: String) {
        val candidates = withConnection { c ->
            c.prepareStatement("SELECT id,provider,provider_payment_id FROM payment_intents WHERE status IN ('PROCESSING','REQUIRES_ACTION') AND provider_payment_id IS NOT NULL ORDER BY updated_at LIMIT ?").use { s ->
                s.setInt(1, limit)
                s.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getString(1), PaymentProviderName.valueOf(r.getString(2)), r.getString(3))) } }
            }
        }
        candidates.forEach { (id, providerName, providerId) ->
            runCatching {
                val result = providers[providerName]!!.query(providerId)
                transaction { c ->
                    val now = Instant.now()
                    updateStatus(c, id, result.status, providerId, result.clientSecret, correlationId, now)
                    c.prepareStatement("INSERT INTO payment_reconciliation_runs(id,payment_id,provider_status,outcome,checked_at) VALUES (?,?,?,?,?)").use { s ->
                        s.setString(1, CommerceId.new("rec").value); s.setString(2, id); s.setString(3, result.status.name); s.setString(4, "APPLIED"); s.setTimestamp(5, now.ts()); s.executeUpdate()
                    }
                }
            }
        }
    }

    private fun finalize(id:String,result:ProviderPayment,correlationId:String)=transaction{c->updateStatus(c,id,result.status,result.providerPaymentId,result.clientSecret,correlationId,Instant.now());get(c,id)!!}
    private fun updateStatus(c:Connection,id:String,target:PaymentStatus,providerId:String?,clientSecret:String?,correlationId:String,now:Instant){val current=get(c,id,null,true)?:throw ApiException(ErrorCode.NOT_FOUND,"Payment not found.",404);if(current.status==target)return;try{assertPaymentTransition(current.status,target)}catch(_:IllegalStateException){throw ApiException(ErrorCode.CONFLICT,"Payment cannot transition from ${current.status} to $target.",409)};c.prepareStatement("UPDATE payment_intents SET status=?,provider_payment_id=COALESCE(?,provider_payment_id),client_secret=COALESCE(?,client_secret),updated_at=?,version=version+1 WHERE id=?").use{s->s.setString(1,target.name);s.setString(2,providerId);s.setString(3,clientSecret);s.setTimestamp(4,now.ts());s.setString(5,id);s.executeUpdate()};c.prepareStatement("UPDATE payment_attempts SET status=?,provider_payment_id=?,completed_at=? WHERE payment_id=? AND attempt_no=?").use{s->s.setString(1,target.name);s.setString(2,providerId);s.setTimestamp(3,now.ts());s.setString(4,id);s.setInt(5,current.attempt);s.executeUpdate()};if(target in setOf(PaymentStatus.AUTHORIZED,PaymentStatus.CAPTURED))c.prepareStatement("INSERT INTO payment_transactions(id,payment_id,type,provider_transaction_id,amount_minor,currency,status,created_at) VALUES (?,?,?,?,?,?,?,?)").use{s->s.setString(1,CommerceId.new("ptx").value);s.setString(2,id);s.setString(3,target.name);s.setString(4,providerId);s.setLong(5,current.amountMinor);s.setString(6,current.currency);s.setString(7,"SUCCESS");s.setTimestamp(8,now.ts());s.executeUpdate()};outbox(c,get(c,id)!!,eventType(target),correlationId,now)}

    private fun getByProvider(c:Connection,providerId:String,lock:Boolean)=get(c,providerId,null,lock,"provider_payment_id")
    private fun get(c:Connection,id:String,userId:String?=null,lock:Boolean=false,column:String="id"):PaymentResponse?{val suffix=if(lock)" FOR UPDATE" else"";val sql=if(userId==null)"SELECT * FROM payment_intents WHERE $column=?$suffix" else"SELECT * FROM payment_intents WHERE $column=? AND user_id=?$suffix";return c.prepareStatement(sql).use{s->s.setString(1,id);if(userId!=null)s.setString(2,userId);s.executeQuery().use{r->if(r.next())response(r)else null}}}
    private fun response(r:ResultSet)=PaymentResponse(r.getString("id"),r.getString("order_id"),r.getString("user_id"),PaymentProviderName.valueOf(r.getString("provider")),r.getString("provider_payment_id"),PaymentStatus.valueOf(r.getString("status")),r.getLong("amount_minor"),r.getString("currency"),r.getInt("attempt"),r.getString("client_secret"),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("updated_at").toInstant().toString())
    private fun outbox(c:Connection,response:PaymentResponse,type:String,correlation:String,now:Instant)=c.prepareStatement("INSERT INTO payment_outbox_events(id,aggregate_id,event_type,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,?,?::jsonb)").use{s->s.setString(1,newOutboxId());s.setString(2,response.id);s.setString(3,type);s.setTimestamp(4,now.ts());s.setString(5,correlation);s.setString(6,json.encodeToString(response));s.executeUpdate()}
    private fun eventType(status:PaymentStatus)=when(status){PaymentStatus.AUTHORIZED->"PaymentAuthorized";PaymentStatus.CAPTURED->"PaymentCaptured";PaymentStatus.REFUNDED,PaymentStatus.PARTIALLY_REFUNDED->"PaymentRefunded";PaymentStatus.FAILED->"PaymentFailed";else->"PaymentStatusChanged"}
    private fun validate(r:PaymentCreateRequest){if(r.orderId.isBlank()||r.amountMinor<=0||!r.currency.matches(Regex("[A-Z]{3}"))||r.paymentMethodToken.isBlank())throw ApiException(ErrorCode.VALIDATION_ERROR,"Payment request is invalid.",400)}
    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection{c->c.prepareStatement("SELECT id,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM payment_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use{s->s.setInt(1,limit);s.executeQuery().use{r->buildList{while(r.next())add(ServiceOutboxRecord(r.getString(1),"Payment",r.getString(2),r.getString(3),r.getInt(4),r.getTimestamp(5).toInstant(),r.getString(6),r.getString(7)))}}}}
    override fun markPublished(ids:List<String>,publishedAt:Instant){if(ids.isEmpty())return;transaction{c->c.prepareStatement("UPDATE payment_outbox_events SET published_at=? WHERE id=ANY(?)").use{s->s.setTimestamp(1,publishedAt.ts());s.setArray(2,c.createArrayOf("varchar",ids.toTypedArray()));s.executeUpdate()}}}
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){ "%02x".format(it) }
    private fun <T>withConnection(block:(Connection)->T):T=dataSource.connection.use(block)
    private fun <T>transaction(block:(Connection)->T):T=dataSource.connection.use{c->c.autoCommit=false;try{block(c).also{c.commit()}}catch(e:Throwable){c.rollback();throw e}}
}
private fun Instant.ts()=Timestamp.from(this)
