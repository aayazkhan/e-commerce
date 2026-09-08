package com.ecommerce.checkout

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.service.InternalHttpClient
import com.ecommerce.platform.service.InternalHttpResponse
import com.ecommerce.platform.service.KafkaOutboxPublisher
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.ServiceKafkaConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface CheckoutStore {
    fun start(userId: String, request: CheckoutRequest, key: String): CheckoutResponse
    fun get(id: String): CheckoutResponse?
    fun getOwned(userId: String, id: String): CheckoutResponse?
    fun checkpoint(id: String, status: CheckoutStatus, step: CheckoutStep, reservationId: String? = null, orderId: String? = null, promotionId: String? = null, paymentId: String? = null, paymentStatus: String? = null, paymentSecret: String? = null, shipmentId: String? = null, totals: CheckoutTotals? = null, error: String? = null)
    fun fail(id: String, message: String, recoverable: Boolean)
}

interface CheckoutWorkflow {
    suspend fun validate(userId: String, bearer: String, request: CheckoutRequest): CheckoutValidationResponse
    suspend fun execute(initial: CheckoutResponse, request: CheckoutRequest, userId: String, bearer: String, correlation: String, internalToken: String): CheckoutResponse
}

interface CheckoutDependencies {
    suspend fun validate(userId: String, bearer: String, request: CheckoutRequest): CheckoutValidationResponse
    suspend fun details(userId: String, bearer: String, request: CheckoutRequest, totals: CheckoutTotals): CheckoutDetails
    suspend fun reserve(userId: String, bearer: String, key: String, items: List<OrderItemWire>, token: String): ReservationWire
    suspend fun release(userId: String, token: String, id: String, key: String)
    suspend fun commit(userId: String, token: String, id: String, key: String)
    suspend fun createOrder(userId: String, bearer: String, token: String, checkoutId: String, reservationId: String, details: CheckoutDetails, totals: CheckoutTotals): OrderWire
    suspend fun createPayment(userId: String, token: String, checkoutId: String, orderId: String, totals: CheckoutTotals, request: CheckoutRequest): PaymentWire
    suspend fun applyPromotion(userId: String, token: String, checkoutId: String, orderId: String, items: List<OrderItemWire>, request: CheckoutRequest, totals: CheckoutTotals): RedemptionWire
    suspend fun commitPromotion(userId: String, token: String, id: String)
    suspend fun releasePromotion(userId: String, token: String, id: String)
    suspend fun transitionOrder(orderId: String, status: String, token: String)
    suspend fun createShipment(userId: String, token: String, checkoutId: String, orderId: String, details: CheckoutDetails, totals: CheckoutTotals, request: CheckoutRequest): ShipmentWire
    suspend fun refund(paymentId: String, token: String, amount: Long, currency: String, checkoutId: String)
}

data class CheckoutDetails(
    val items: List<OrderItemWire>,
    val shipping: AddressSnapshotWire,
    val billing: AddressSnapshotWire,
    val cartId: String,
)

fun Application.module(){
    val config=environment.config;val json=Json{encodeDefaults=true;explicitNulls=false;ignoreUnknownKeys=true};val db=ServiceDatabase(ServiceDatabaseConfig(config.required("checkout.database.url"),config.required("checkout.database.username"),config.required("checkout.database.password"),config.required("checkout.database.maximumPoolSize").toInt(),config.required("checkout.database.connectionTimeoutMillis").toLong()),"classpath:db/migration");val repo=CheckoutRepository(db.dataSource());val token=config.required("checkout.internalToken");val verifier=HmacJwtAccessVerifier(config.required("checkout.jwt.issuer"),config.required("checkout.jwt.audience"),parseKeys(config.required("checkout.jwt.keys")));val clients=CheckoutClients(config,json);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val publisher=KafkaOutboxPublisher(repo,ServiceKafkaConfig(config.required("checkout.kafka.bootstrapServers"),config.required("checkout.kafka.topic"),config.required("checkout.kafka.tenantId")),"checkout-service");publisher.start(scope);monitor.subscribe(ApplicationStopping){scope.cancel();publisher.close();db.close()};install(DefaultHeaders);install(CallId){header(HttpHeaders.XRequestId);verify{it.length in 8..128};generate{"req_${java.util.UUID.randomUUID()}"}};install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}};install(ContentNegotiation){json(json)};install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}};install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowHeader("Idempotency-Key");allowHeader("X-Internal-Service-Token");allowHeader("X-Actor-Id");allowCredentials=true};routing{
    get("/health/live"){call.respond(Health("UP","checkout-service"))};get("/health/ready"){if(runCatching{db.ping()}.getOrDefault(false))call.respond(Health("UP","checkout-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","checkout-service"))};get("/metrics"){call.respondText("# TYPE checkout_requests_total counter\ncheckout_requests_total 1\n",ContentType.Text.Plain)}
    }
    val store = CheckoutRepositoryAdapter(repo)
    configureCheckoutRoutes(store, ProductionCheckoutWorkflow(store, clients), verifier, token)
}

fun Application.configureCheckoutRoutes(repository: CheckoutStore, workflow: CheckoutWorkflow, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        post("/api/v1/checkout/validate"){val user=call.userToken(verifier);val request=call.receive<CheckoutRequest>();call.respond(workflow.validate(user.subject,user.token,request))}
        post("/api/v1/checkout"){val user=call.userToken(verifier);val key=call.request.header("Idempotency-Key")?:throw ApiException(ErrorCode.VALIDATION_ERROR,"Idempotency-Key is required.",400);val request=call.receive<CheckoutRequest>();val initial=repository.start(user.subject,request,key);call.respond(workflow.execute(initial,request,user.subject,user.token,call.callId.orEmpty(),internalToken))}
        get("/api/v1/checkout/{checkoutId}"){val user=call.userToken(verifier);val result=repository.getOwned(user.subject,call.parameters.required("checkoutId"))?:throw ApiException(ErrorCode.NOT_FOUND,"Checkout not found.",404);call.respond(result)}
        post("/api/v1/checkout/{checkoutId}/retry"){val user=call.userToken(verifier);val key=call.request.header("Idempotency-Key")?:throw ApiException(ErrorCode.VALIDATION_ERROR,"Idempotency-Key is required.",400);val request=call.receive<CheckoutRequest>();val initial=repository.start(user.subject,request,key);call.respond(workflow.execute(initial,request,user.subject,user.token,call.callId.orEmpty(),internalToken))}
    }
}

private class ProductionCheckoutWorkflow(private val repository: CheckoutStore, private val clients: CheckoutDependencies) : CheckoutWorkflow {
    override suspend fun validate(userId: String, bearer: String, request: CheckoutRequest) = clients.validate(userId,bearer,request)
    override suspend fun execute(initial: CheckoutResponse, request: CheckoutRequest, userId: String, bearer: String, correlation: String, internalToken: String) = runCheckoutSaga(repository,clients,initial,request,userId,bearer,correlation,internalToken)
}

private class CheckoutRepositoryAdapter(private val delegate: CheckoutRepository) : CheckoutStore {
    override fun start(userId: String, request: CheckoutRequest, key: String) = delegate.start(userId, request, key)
    override fun get(id: String) = delegate.get(id)
    override fun getOwned(userId: String, id: String) = delegate.getOwned(userId, id)
    override fun checkpoint(id: String, status: CheckoutStatus, step: CheckoutStep, reservationId: String?, orderId: String?, promotionId: String?, paymentId: String?, paymentStatus: String?, paymentSecret: String?, shipmentId: String?, totals: CheckoutTotals?, error: String?) = delegate.checkpoint(id, status, step, reservationId, orderId, promotionId, paymentId, paymentStatus, paymentSecret, shipmentId, totals, error)
    override fun fail(id: String, message: String, recoverable: Boolean) = delegate.fail(id, message, recoverable)
}

public suspend fun runCheckoutSaga(repo:CheckoutStore,clients:CheckoutDependencies,initial:CheckoutResponse,request:CheckoutRequest,userId:String,bearer:String,correlation:String,internalToken:String):CheckoutResponse{
    if(initial.status==CheckoutStatus.COMPLETED)return initial
    var current=initial
    try{
        repo.checkpoint(current.checkoutId,CheckoutStatus.VALIDATING,CheckoutStep.VALIDATE_CART);val quote=clients.validate(userId,bearer,request);if(!quote.valid||quote.totals==null)throw ApiException(ErrorCode.CONFLICT,"Cart is no longer valid.",409);val details=clients.details(userId,bearer,request,quote.totals)
        repo.checkpoint(current.checkoutId,CheckoutStatus.INVENTORY_RESERVING,CheckoutStep.RESERVE_INVENTORY,totals=quote.totals);val reservation=clients.reserve(userId,bearer,current.checkoutId,details.items,internalToken);repo.checkpoint(current.checkoutId,CheckoutStatus.INVENTORY_RESERVED,CheckoutStep.CREATE_ORDER,reservationId=reservation.id,totals=quote.totals)
        val order=clients.createOrder(userId,bearer,internalToken,current.checkoutId,reservation.id,details,quote.totals);repo.checkpoint(current.checkoutId,CheckoutStatus.ORDER_CREATING,CheckoutStep.CREATE_PAYMENT,orderId=order.id,reservationId=reservation.id,totals=quote.totals);val redemption=if(request.couponCode.isNullOrBlank())null else clients.applyPromotion(userId,internalToken,current.checkoutId,order.id,details.items,request,quote.totals);if(redemption!=null)repo.checkpoint(current.checkoutId,CheckoutStatus.ORDER_CREATING,CheckoutStep.CREATE_PAYMENT,orderId=order.id,reservationId=reservation.id,promotionId=redemption.id,totals=quote.totals)
        val payment=clients.createPayment(userId,internalToken,current.checkoutId,order.id,quote.totals,request);repo.checkpoint(current.checkoutId,if(payment.status in setOf("AUTHORIZED","CAPTURED"))CheckoutStatus.PAYMENT_PROCESSING else CheckoutStatus.PAYMENT_ACTION_REQUIRED,CheckoutStep.COMMIT_INVENTORY,orderId=order.id,reservationId=reservation.id,paymentId=payment.id,paymentStatus=payment.status,paymentSecret=payment.clientSecret,totals=quote.totals)
        if(payment.status !in setOf("AUTHORIZED","CAPTURED")){current=repo.get(current.checkoutId)!!;return current}
        clients.transitionOrder(order.id,"PAID",internalToken);clients.transitionOrder(order.id,"CONFIRMED",internalToken);clients.commit(userId,internalToken,reservation.id,current.checkoutId);if(redemption!=null)clients.commitPromotion(userId,internalToken,redemption.id);repo.checkpoint(current.checkoutId,CheckoutStatus.PAYMENT_PROCESSING,CheckoutStep.CREATE_SHIPMENT,orderId=order.id,reservationId=reservation.id,promotionId=redemption?.id,paymentId=payment.id,paymentStatus=payment.status,totals=quote.totals);val shipment=clients.createShipment(userId,internalToken,current.checkoutId,order.id,details,quote.totals,request);repo.checkpoint(current.checkoutId,CheckoutStatus.COMPLETED,CheckoutStep.COMPLETE,orderId=order.id,reservationId=reservation.id,promotionId=redemption?.id,paymentId=payment.id,paymentStatus=payment.status,shipmentId=shipment.id,totals=quote.totals);return repo.get(current.checkoutId)!!
    }catch(error:ApiException){val saved=repo.get(current.checkoutId);saved?.reservationId?.let{runCatching{clients.release(userId,internalToken,it,current.checkoutId)}};saved?.promotionRedemptionId?.let{runCatching{clients.releasePromotion(userId,internalToken,it)}};saved?.payment?.id?.let{paymentId->saved.orderId?.let{orderId->runCatching{clients.transitionOrder(orderId,"REFUND_PENDING",internalToken);clients.refund(paymentId,internalToken,saved.totals?.totalMinor?:0,saved.totals?.currency?:request.currency,current.checkoutId)}}};repo.fail(current.checkoutId,error.message,error.retryable||error.statusCode>=500);return repo.get(current.checkoutId)!!}catch(error:Exception){val saved=repo.get(current.checkoutId);saved?.reservationId?.let{runCatching{clients.release(userId,internalToken,it,current.checkoutId)}};repo.fail(current.checkoutId,"Checkout could not be completed.",true);return repo.get(current.checkoutId)!!}
}

internal interface CheckoutHttpClient {
    fun get(path: String, headers: Map<String, String> = emptyMap()): InternalHttpResponse
    fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): InternalHttpResponse
}

private class CheckoutInternalHttpClient(private val delegate: InternalHttpClient) : CheckoutHttpClient {
    override fun get(path: String, headers: Map<String, String>) = delegate.get(path, headers)
    override fun post(path: String, body: String, headers: Map<String, String>) = delegate.post(path, body, headers)
}

internal class CheckoutClients private constructor(
    private val json: Json,
    private val cart: CheckoutHttpClient,
    private val pricing: CheckoutHttpClient,
    private val promotion: CheckoutHttpClient,
    private val inventory: CheckoutHttpClient,
    private val identity: CheckoutHttpClient,
    private val shipping: CheckoutHttpClient,
    private val order: CheckoutHttpClient,
    private val payment: CheckoutHttpClient,
    private val catalog: CheckoutHttpClient,
    private val warehouseId: String,
) : CheckoutDependencies {
    constructor(config: ApplicationConfig, json: Json, factory: (String) -> CheckoutHttpClient = { CheckoutInternalHttpClient(InternalHttpClient(it)) }) : this(
        json,
        factory(config.required("checkout.cartBaseUrl")),
        factory(config.required("checkout.pricingBaseUrl")),
        factory(config.required("checkout.promotionBaseUrl")),
        factory(config.required("checkout.inventoryBaseUrl")),
        factory(config.required("checkout.identityBaseUrl")),
        factory(config.required("checkout.shippingBaseUrl")),
        factory(config.required("checkout.orderBaseUrl")),
        factory(config.required("checkout.paymentBaseUrl")),
        factory(config.required("checkout.catalogBaseUrl")),
        config.required("checkout.warehouseId"),
    )

    internal constructor(json: Json, warehouseId: String, clients: Map<String, CheckoutHttpClient>) : this(
        json,
        clients.getValue("cart"),
        clients.getValue("pricing"),
        clients.getValue("promotion"),
        clients.getValue("inventory"),
        clients.getValue("identity"),
        clients.getValue("shipping"),
        clients.getValue("order"),
        clients.getValue("payment"),
        clients.getValue("catalog"),
        warehouseId,
    )

    override suspend fun validate(userId:String,bearer:String,request:CheckoutRequest)=validate(userId,bearer,request,false)
    private suspend fun validate(userId:String,bearer:String,request:CheckoutRequest,detailsOnly:Boolean):CheckoutValidationResponse{val headers=auth(bearer);val cart=decode<CartValidationWire>(cart.post("/api/v1/cart/validate","{}",headers));if(!cart.valid||cart.cart.items.isEmpty())return CheckoutValidationResponse(false,null,cart.warnings.map{it.message});val price=decode<PriceQuoteWire>(pricing.post("/api/v1/pricing/quote",json.encodeToString(PriceRequest(cart.cart.items.map{PriceItemWire(it.productId,it.variantId,it.quantity)},request.currency),),emptyMap()));val lines=cart.cart.items.map{PromotionLineWire(it.productId,it.variantId,it.quantity,it.unitPriceMinor)};val promo=decode<PromotionQuoteWire>(promotion.post("/api/v1/promotions/quote",json.encodeToString(PromotionRequestWire(request.currency,lines,request.couponCode),),emptyMap()));val address=address(userId,bearer,request.shippingAddressId);val ship=decode<ShippingQuoteWire>(shipping.post("/api/v1/shipping/quotes",json.encodeToString(ShippingQuoteRequestWire(address.toAddress(),cart.cart.items.map{ShipmentItemWire(it.variantId,it.quantity)},request.shippingMethod.name,request.currency),),headers));val discount=promo.discountMinor;val subtotal=price.subtotalMinor;val total=subtotal-discount+ship.amountMinor+price.taxMinor;return CheckoutValidationResponse(true,CheckoutTotals(subtotal,0,discount,ship.amountMinor,price.taxMinor,total,request.currency),emptyList())}
    override suspend fun details(userId:String,bearer:String,request:CheckoutRequest,totals:CheckoutTotals):CheckoutDetails{val cart=decode<CartValidationWire>(cart.post("/api/v1/cart/validate","{}",auth(bearer)));val address=address(userId,bearer,request.shippingAddressId);val billing=request.billingAddressId?.let{address(userId,bearer,it)}?:address;val items=cart.cart.items.map{val product=decode<ProductWire>(catalog.get("/api/v1/products/${it.productId}"));val variant=product.variants.firstOrNull{v->v.id==it.variantId}?:throw ApiException(ErrorCode.CONFLICT,"Product variant is no longer available.",409);val price=it.unitPriceMinor;OrderItemWire(it.productId,it.variantId,product.name,variant.sku,null,it.quantity,price,0,0,price*it.quantity,it.currency,it.priceVersion,variant.attributes)};return CheckoutDetails(items,address.toAddress(),billing.toAddress(),cart.cart.id)}
    override suspend fun reserve(userId:String,bearer:String,key:String,items:List<OrderItemWire>,token:String):ReservationWire{val body=ReservationRequestWire(key,null,null,items.map{ReservationItemWire(it.variantId,warehouseId,it.quantity.toLong())},900);return decode(inventory.post("/api/v1/inventory/reservations",json.encodeToString(body),internal(token,userId,bearer)))}
    override suspend fun release(userId:String,token:String,id:String,key:String){inventory.post("/api/v1/inventory/reservations/$id/release","{}",internal(token,userId,""))}
    override suspend fun commit(userId:String,token:String,id:String,key:String){inventory.post("/api/v1/inventory/reservations/$id/commit","{}",internal(token,userId,""))}
    override suspend fun createOrder(userId:String,bearer:String,token:String,checkoutId:String,reservationId:String,details:CheckoutDetails,totals:CheckoutTotals):OrderWire{val body=OrderRequestWire(checkoutId,reservationId,details.items,details.shipping,details.billing,totals.subtotalMinor,totals.itemDiscountMinor,totals.promotionDiscountMinor,totals.shippingMinor,totals.taxMinor,totals.totalMinor,totals.currency);return decode(order.post("/api/v1/orders",json.encodeToString(body),internal(token,userId,bearer)+mapOf("Idempotency-Key" to "order-$checkoutId")))}
    override suspend fun createPayment(userId:String,token:String,checkoutId:String,orderId:String,totals:CheckoutTotals,request:CheckoutRequest):PaymentWire{val body=PaymentEnvelopeWire(userId,PaymentRequestWire(orderId,totals.totalMinor,totals.currency,request.paymentProvider.uppercase(),request.paymentMethodToken));return decode(payment.post("/api/v1/internal/payments",json.encodeToString(body),mapOf("X-Internal-Service-Token" to token,"Idempotency-Key" to "payment-$checkoutId")))}
    override suspend fun applyPromotion(userId:String,token:String,checkoutId:String,orderId:String,items:List<OrderItemWire>,request:CheckoutRequest,totals:CheckoutTotals):RedemptionWire{val lines=items.map{PromotionLineWire(it.productId,it.variantId,it.quantity,it.unitPriceMinor)};val body=PromotionApplyEnvelopeWire(userId,PromotionRequestWire(request.currency,lines,request.couponCode,totals.shippingMinor),orderId);return decode(promotion.post("/api/v1/internal/promotions/apply",json.encodeToString(body),mapOf("X-Internal-Service-Token" to token,"X-Actor-Id" to userId,"Idempotency-Key" to "promotion-$checkoutId")))}
    override suspend fun commitPromotion(userId:String,token:String,id:String){promotion.post("/api/v1/internal/promotions/redemptions/$id/commit","{}",mapOf("X-Internal-Service-Token" to token,"X-Actor-Id" to userId))}
    override suspend fun releasePromotion(userId:String,token:String,id:String){promotion.post("/api/v1/internal/promotions/redemptions/$id/release","{}",mapOf("X-Internal-Service-Token" to token,"X-Actor-Id" to userId))}
    override suspend fun transitionOrder(orderId:String,status:String,token:String){order.post("/api/v1/internal/orders/$orderId/status",json.encodeToString(StatusWire(status)),mapOf("X-Internal-Service-Token" to token))}
    override suspend fun createShipment(userId:String,token:String,checkoutId:String,orderId:String,details:CheckoutDetails,totals:CheckoutTotals,request:CheckoutRequest):ShipmentWire{val body=ShipmentRequestWire(orderId,userId,details.shipping,details.items.map{ShipmentItemWire(it.variantId,it.quantity)},request.shippingMethod.name,totals.shippingMinor,totals.currency);return decode(shipping.post("/api/v1/internal/shipping/shipments",json.encodeToString(body),mapOf("X-Internal-Service-Token" to token,"Idempotency-Key" to "shipment-$checkoutId")))}
    override suspend fun refund(paymentId:String,token:String,amount:Long,currency:String,checkoutId:String){payment.post("/api/v1/internal/payments/$paymentId/refund","""{"amountMinor":$amount,"currency":"$currency","idempotencyKey":"checkout-refund-$checkoutId"}""",mapOf("X-Internal-Service-Token" to token))}
    private suspend fun address(userId:String,bearer:String,id:String)=decode<List<AddressWire>>(identity.get("/api/v1/addresses",auth(bearer))).firstOrNull{it.id==id}?:throw ApiException(ErrorCode.NOT_FOUND,"Address was not found.",404)
    private fun auth(token:String)=mapOf("Authorization" to "Bearer $token")
    private fun internal(token:String,userId:String,bearer:String)=mapOf("X-Internal-Service-Token" to token,"X-Actor-Id" to userId)+if(bearer.isBlank())emptyMap()else auth(bearer)
    private inline fun <reified T>decode(response:InternalHttpResponse):T{if(response.statusCode !in 200..299)throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Checkout dependency rejected the request.",503,true);return json.decodeFromString(response.body)}
}

@Serializable private data class Health(val status:String,val component:String)
@Serializable private data class CartValidationWire(val cart:CartWire,val valid:Boolean,val warnings:List<CartWarningWire>)
@Serializable private data class CartWire(val id:String,val userId:String?,val currency:String,val version:Long,val items:List<CartItemWire>)
@Serializable private data class CartItemWire(val id:String,val productId:String,val variantId:String,val quantity:Int,val unitPriceMinor:Long,val currency:String,val priceVersion:String,val addedAt:String,val updatedAt:String)
@Serializable private data class CartWarningWire(val variantId:String,val code:String,val message:String)
@Serializable internal data class PriceRequest(val items:List<PriceItemWire>,val currency:String="INR",val country:String="IN",val customerSegment:String="DEFAULT")
@Serializable internal data class PriceItemWire(val productId:String,val variantId:String,val quantity:Int)
@Serializable private data class PriceQuoteWire(val items:List<PriceLineWire>,val subtotalMinor:Long,val discountMinor:Long,val taxMinor:Long,val shippingEstimateMinor:Long,val totalMinor:Long,val currency:String,val priceVersion:String)
@Serializable private data class PriceLineWire(val productId:String,val variantId:String?,val quantity:Int,val unitMinor:Long,val lineSubtotalMinor:Long,val lineTaxMinor:Long)
@Serializable internal data class PromotionRequestWire(val currency:String,val lines:List<PromotionLineWire>,val couponCode:String?=null,val shippingMinor:Long=0)
@Serializable internal data class PromotionApplyEnvelopeWire(val userId:String,val request:PromotionRequestWire,val orderId:String?=null)
@Serializable internal data class PromotionLineWire(val productId:String,val variantId:String,val quantity:Int,val unitPriceMinor:Long)
@Serializable internal data class PromotionQuoteWire(val promotionId:String?,val couponCode:String?,val currency:String,val discountMinor:Long,val freeShipping:Boolean,val eligibleLineDiscounts:Map<String,Long> = emptyMap(),val reason:String?=null)
@Serializable internal data class AddressWire(val id:String,val label:String,val recipientName:String,val phone:String,val line1:String,val line2:String?,val city:String,val state:String,val postalCode:String,val country:String,val latitude:Double?=null,val longitude:Double?=null,val isDefault:Boolean=false)
@Serializable private data class ShippingQuoteRequestWire(val address:AddressSnapshotWire,val items:List<ShipmentItemWire>,val method:String,val currency:String)
@Serializable private data class ShippingQuoteWire(val quoteId:String,val method:String,val amountMinor:Long,val currency:String,val expiresAt:String)
@Serializable data class AddressSnapshotWire(val addressId:String,val recipientName:String,val phone:String,val line1:String,val line2:String?,val city:String,val state:String,val postalCode:String,val country:String)
@Serializable private data class ShipmentItemWire(val variantId:String,val quantity:Int)
@Serializable private data class ReservationRequestWire(val reservationKey:String,val cartId:String?,val orderId:String?,val items:List<ReservationItemWire>,val ttlSeconds:Long)
@Serializable data class ReservationItemWire(val variantId:String,val warehouseId:String,val quantity:Long)
@Serializable data class ReservationWire(val id:String,val reservationKey:String,val actorId:String,val cartId:String?,val orderId:String?,val status:String,val expiresAt:String,val createdAt:String,val updatedAt:String,val items:List<ReservationItemWire>)
@Serializable internal data class ProductWire(val id:String,val name:String,val variants:List<ProductVariantWire> = emptyList())
@Serializable internal data class ProductVariantWire(val id:String,val productId:String,val sku:String,val barcode:String?=null,val attributes:Map<String,String> = emptyMap())
@Serializable data class OrderItemWire(val productId:String,val variantId:String,val productName:String,val sku:String?=null,val sellerId:String?=null,val quantity:Int,val unitPriceMinor:Long,val taxMinor:Long,val discountMinor:Long,val lineTotalMinor:Long,val currency:String,val priceVersion:String,val attributes:Map<String,String> = emptyMap())
@Serializable internal data class OrderRequestWire(val checkoutId:String,val reservationId:String,val items:List<OrderItemWire>,val shippingAddress:AddressSnapshotWire,val billingAddress:AddressSnapshotWire,val subtotalMinor:Long,val itemDiscountMinor:Long,val promotionDiscountMinor:Long,val shippingMinor:Long,val taxMinor:Long,val totalMinor:Long,val currency:String,val initialStatus:String="INVENTORY_RESERVED")
@Serializable private data class OrderEnvelopeWire(val userId:String,val request:OrderRequestWire)
@Serializable data class OrderWire(val id:String,val status:String,val totalMinor:Long)
@Serializable private data class PaymentRequestWire(val orderId:String,val amountMinor:Long,val currency:String,val provider:String,val paymentMethodToken:String)
@Serializable private data class PaymentEnvelopeWire(val userId:String,val request:PaymentRequestWire)
@Serializable data class PaymentWire(val id:String,val status:String,val clientSecret:String?=null)
@Serializable private data class StatusWire(val status:String)
@Serializable private data class ShipmentRequestWire(val orderId:String,val userId:String,val address:AddressSnapshotWire,val items:List<ShipmentItemWire>,val method:String,val amountMinor:Long,val currency:String)
@Serializable data class ShipmentWire(val id:String,val status:String)
@Serializable data class RedemptionWire(val id:String,val promotionId:String,val couponCode:String?,val userId:String,val orderId:String?,val discountMinor:Long,val currency:String,val status:String)
private fun ApplicationConfig.required(path:String)=property(path).getString().takeIf{it.isNotBlank()}?:error("Missing configuration: $path")
private fun io.ktor.server.application.ApplicationCall.userToken(verifier:HmacJwtAccessVerifier):UserToken{val value=request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?:throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED,"Authentication is required.",401);return UserToken(verifier.verify(value).subject,value)}
private fun parseKeys(value:String)=value.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()}
private data class UserToken(val subject:String,val token:String)
private fun io.ktor.http.Parameters.required(name:String)=this[name]?:throw ApiException(ErrorCode.VALIDATION_ERROR,"Missing path parameter: $name",400)
private fun AddressWire.toAddress()=AddressSnapshotWire(id,recipientName,phone,line1,line2,city,state,postalCode,country)
