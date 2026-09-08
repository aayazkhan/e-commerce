package com.ecommerce.checkout

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckoutClientsTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `validation returns warnings for invalid and empty carts without calling later services`() {
        val invalid = CheckoutClients(json, "warehouse-1", clients(cart = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(valid = false, empty = false))))))
        val invalidResult = kotlinx.coroutines.runBlocking { invalid.validate("user-1", "bearer", request()) }
        assertEquals(false, invalidResult.valid)
        assertEquals(listOf("stale cart"), invalidResult.warnings)

        val empty = CheckoutClients(json, "warehouse-1", clients(cart = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(valid = true, empty = true))))))
        val emptyResult = kotlinx.coroutines.runBlocking { empty.validate("user-1", "bearer", request()) }
        assertEquals(false, emptyResult.valid)
        assertEquals(listOf("cart is empty"), emptyResult.warnings)
    }

    @Test
    fun `validation calculates totals and propagates authorization headers across services`() {
        val cartClient = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(valid = true, empty = false))))
        val pricingClient = FakeClient(posts = listOf(InternalHttpResponse(200, """{"items":[],"subtotalMinor":1000,"discountMinor":0,"taxMinor":180,"shippingEstimateMinor":0,"totalMinor":1180,"currency":"INR","priceVersion":"price-v1"}""")))
        val promotionClient = FakeClient(posts = listOf(InternalHttpResponse(200, """{"promotionId":null,"couponCode":null,"currency":"INR","discountMinor":100,"freeShipping":false,"eligibleLineDiscounts":{},"reason":null}""")))
        val identityClient = FakeClient(gets = listOf(InternalHttpResponse(200, addressJson())))
        val shippingClient = FakeClient(posts = listOf(InternalHttpResponse(200, """{"quoteId":"quote-1","method":"EXPRESS","amountMinor":50,"currency":"INR","expiresAt":"2026-08-21T01:00:00Z"}""")))
        val result = kotlinx.coroutines.runBlocking {
            CheckoutClients(json, "warehouse-1", clients(cart = cartClient, pricing = pricingClient, promotion = promotionClient, identity = identityClient, shipping = shippingClient)).validate("user-1", "bearer", request(couponCode = "SAVE10"))
        }

        assertEquals(CheckoutTotals(1000, 0, 100, 50, 180, 1130, "INR"), result.totals)
        assertEquals("Bearer bearer", identityClient.requests.single().headers["Authorization"])
        assertEquals("Bearer bearer", cartClient.requests.single().headers["Authorization"])
        assertTrue(promotionClient.requests.single().body.contains("SAVE10"))
    }

    @Test
    fun `details supports shipping billing fallback and rejects missing variants or addresses`() {
        val cartResponses = listOf(
            InternalHttpResponse(200, cartJson(valid = true, empty = false)),
            InternalHttpResponse(200, cartJson(valid = true, empty = false)),
            InternalHttpResponse(200, cartJson(valid = true, empty = false)),
        )
        val identity = FakeClient(gets = listOf(InternalHttpResponse(200, addressJson()), InternalHttpResponse(200, addressJson()), InternalHttpResponse(200, addressJson()), InternalHttpResponse(200, addressJson())))
        val catalog = FakeClient(gets = listOf(InternalHttpResponse(200, productJson()), InternalHttpResponse(200, productJson()), InternalHttpResponse(200, """{"id":"product-1","name":"Shoe","variants":[]}""")))
        val checkoutClients = CheckoutClients(json, "warehouse-1", clients(cart = FakeClient(posts = cartResponses), identity = identity, catalog = catalog))
        val details = kotlinx.coroutines.runBlocking { checkoutClients.details("user-1", "bearer", request(), totals()) }
        assertEquals("address-1", details.billing.addressId)
        assertEquals("cart-1", details.cartId)

        val withBilling = kotlinx.coroutines.runBlocking { checkoutClients.details("user-1", "bearer", request(billingAddressId = "address-2"), totals()) }
        assertEquals("address-2", withBilling.billing.addressId)

        val missingVariant = assertFailsWith<ApiException> {
            kotlinx.coroutines.runBlocking { checkoutClients.details("user-1", "bearer", request(), totals()) }
        }
        assertEquals(ErrorCode.CONFLICT, missingVariant.errorCode)

        val missingAddress = assertFailsWith<ApiException> {
            kotlinx.coroutines.runBlocking {
                val missingAddressClients = CheckoutClients(
                    json,
                    "warehouse-1",
                    clients(
                        cart = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(true, false)))),
                        identity = FakeClient(gets = listOf(InternalHttpResponse(200, "[]"))),
                        catalog = FakeClient(gets = listOf(InternalHttpResponse(200, productJson()))),
                    ),
                )
                missingAddressClients.details("user-1", "bearer", request(), totals())
            }
        }
        assertEquals(ErrorCode.NOT_FOUND, missingAddress.errorCode)
    }

    @Test
    fun `details accepts omitted optional address and variant fields`() {
        val sparseAddress = """
            [{"id":"address-1","label":"HOME","recipientName":"Customer","phone":"+911234567890","line1":"Line 1","city":"Pune","state":"MH","postalCode":"411001","country":"IN"}]
        """.trimIndent()
        val sparseProduct = """
            {"id":"product-1","name":"Shoe","variants":[{"id":"variant-1","productId":"product-1","sku":"SKU-1"}]}
        """.trimIndent()
        val service = CheckoutClients(
            json,
            "warehouse-1",
            clients(
                cart = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(true, false)))),
                identity = FakeClient(gets = listOf(InternalHttpResponse(200, sparseAddress))),
                catalog = FakeClient(gets = listOf(InternalHttpResponse(200, sparseProduct))),
            ),
        )

        val details = kotlinx.coroutines.runBlocking { service.details("user-1", "bearer", request(), totals()) }

        assertEquals(null, details.shipping.line2)
        assertEquals("SKU-1", details.items.single().sku)
        assertEquals(emptyMap(), details.items.single().attributes)
    }

    @Test
    fun `downstream operations serialize requests and use blank bearer fallback`() {
        val reservation = ReservationWire("reservation-1", "checkout-1", "user-1", "cart-1", null, "RESERVED", "2026-08-21T00:15:00Z", "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z", listOf(ReservationItemWire("variant-1", "warehouse-1", 2)))
        val details = CheckoutDetails(listOf(OrderItemWire("product-1", "variant-1", "Shoe", "SKU-1", "seller-1", 2, 500, 0, 0, 1000, "INR", "v1")), address(), address(), "cart-1")
        val clientsByName = clients(
            inventory = FakeClient(posts = listOf(InternalHttpResponse(200, json.encodeToString(reservation)), InternalHttpResponse(200, "{}"), InternalHttpResponse(200, "{}"))),
            order = FakeClient(posts = listOf(InternalHttpResponse(200, json.encodeToString(OrderWire("order-1", "CREATED", 1130))))),
            payment = FakeClient(posts = listOf(InternalHttpResponse(200, json.encodeToString(PaymentWire("payment-1", "AUTHORIZED", "secret"))))),
            promotion = FakeClient(posts = listOf(InternalHttpResponse(200, json.encodeToString(RedemptionWire("redemption-1", "promotion-1", "SAVE10", "user-1", "order-1", 100, "INR", "RESERVED"))), InternalHttpResponse(200, "{}"), InternalHttpResponse(200, "{}"))),
            shipping = FakeClient(posts = listOf(InternalHttpResponse(200, json.encodeToString(ShipmentWire("shipment-1", "CREATED"))))),
        )
        val service = CheckoutClients(json, "warehouse-1", clientsByName)
        kotlinx.coroutines.runBlocking {
            assertEquals(reservation.id, service.reserve("user-1", "", "key-1", details.items, "internal").id)
            service.release("user-1", "internal", reservation.id, "key-1")
            service.commit("user-1", "internal", reservation.id, "key-1")
            assertEquals("order-1", service.createOrder("user-1", "bearer", "internal", "checkout-1", reservation.id, details, totals()).id)
            assertEquals("payment-1", service.createPayment("user-1", "internal", "checkout-1", "order-1", totals(), request(), details).id)
            assertEquals("redemption-1", service.applyPromotion("user-1", "internal", "checkout-1", "order-1", details.items, request(couponCode = "SAVE10"), totals()).id)
            service.commitPromotion("user-1", "internal", "redemption-1")
            service.releasePromotion("user-1", "internal", "redemption-1")
            service.transitionOrder("order-1", "CONFIRMED", "internal")
            assertEquals("shipment-1", service.createShipment("user-1", "internal", "checkout-1", "order-1", details, totals(), request()).id)
            service.refund("payment-1", "internal", 100, "INR", "checkout-1")
        }
        val inventoryRequest = (clientsByName.getValue("inventory") as FakeClient).requests.first()
        assertTrue(inventoryRequest.headers["X-Internal-Service-Token"] == "internal")
        assertTrue(inventoryRequest.headers["Authorization"].isNullOrEmpty())
    }

    @Test
    fun `downstream operations preserve nullable payment and promotion fields`() {
        val details = CheckoutDetails(
            listOf(OrderItemWire("product-1", "variant-1", "Shoe", null, null, 1, 500, 0, 0, 500, "INR", "v1")),
            address(),
            address(),
            "cart-1",
        )
        val payment = FakeClient(posts = listOf(InternalHttpResponse(200, "{\"id\":\"payment-1\",\"status\":\"AUTHORIZED\"}")))
        val promotion = FakeClient(posts = listOf(InternalHttpResponse(200, "{\"id\":\"redemption-1\",\"promotionId\":\"promotion-1\",\"couponCode\":null,\"userId\":\"user-1\",\"orderId\":null,\"discountMinor\":0,\"currency\":\"INR\",\"status\":\"RESERVED\"}")))
        val service = CheckoutClients(json, "warehouse-1", clients(payment = payment, promotion = promotion))

        kotlinx.coroutines.runBlocking {
            assertEquals(null, service.createPayment("user-1", "internal", "checkout-1", "order-1", totals(), request(), details).clientSecret)
            assertEquals(null, service.applyPromotion("user-1", "internal", "checkout-1", "order-1", details.items, request(), totals()).orderId)
        }

        assertTrue(promotion.requests.single().body.contains("lines"))
        assertTrue(!promotion.requests.single().body.contains("couponCode"))
    }

    @Test
    fun `non-success downstream responses map to dependency unavailable`() {
        val payment = FakeClient(posts = listOf(InternalHttpResponse(503, "down")))
        val service = CheckoutClients(json, "warehouse-1", clients(payment = payment))
        val error = assertFailsWith<ApiException> {
            kotlinx.coroutines.runBlocking { service.createPayment("user-1", "internal", "checkout-1", "order-1", totals(), request(), details()) }
        }
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode)
        assertEquals(503, error.statusCode)
    }

    @Test
    fun `compact client contract omits default request fields without changing checkout totals`() {
        val compact = Json { explicitNulls = false }
        val cart = FakeClient(posts = listOf(InternalHttpResponse(200, cartJson(valid = true, empty = false))))
        val pricing = FakeClient(posts = listOf(InternalHttpResponse(200, """{"items":[],"subtotalMinor":1000,"discountMinor":0,"taxMinor":180,"shippingEstimateMinor":0,"totalMinor":1180,"currency":"INR","priceVersion":"v1"}""")))
        val promotion = FakeClient(posts = listOf(InternalHttpResponse(200, """{"promotionId":null,"couponCode":null,"currency":"INR","discountMinor":0,"freeShipping":false}""")))
        val identity = FakeClient(gets = listOf(InternalHttpResponse(200, addressJson())))
        val shipping = FakeClient(posts = listOf(InternalHttpResponse(200, """{"quoteId":"q1","method":"STANDARD","amountMinor":0,"currency":"INR","expiresAt":"2026-08-21T01:00:00Z"}""")))
        val service = CheckoutClients(compact, "warehouse-1", clients(cart = cart, pricing = pricing, promotion = promotion, identity = identity, shipping = shipping))

        val validation = kotlinx.coroutines.runBlocking {
            service.validate("user-1", "bearer", request())
        }

        assertEquals(CheckoutTotals(1000, 0, 0, 0, 180, 1180, "INR"), validation.totals)
        assertTrue(pricing.requests.single().body.contains("items"))
        assertTrue(!pricing.requests.single().body.contains("country"))
        assertTrue(!pricing.requests.single().body.contains("customerSegment"))
        assertTrue(!promotion.requests.single().body.contains("couponCode"))
        assertTrue(!promotion.requests.single().body.contains("shippingMinor"))

        val details = CheckoutDetails(listOf(OrderItemWire("product-1", "variant-1", "Shoe", quantity = 1, unitPriceMinor = 500, taxMinor = 0, discountMinor = 0, lineTotalMinor = 500, currency = "INR", priceVersion = "v1")), address(), address(), "cart-1")
        val totals = CheckoutTotals(500, 0, 0, 0, 90, 590, "INR")
        val reservation = ReservationWire("reservation-1", "key-1", "user-1", null, null, "RESERVED", "2026-08-21T00:15:00Z", "2026-08-20T00:00:00Z", "2026-08-20T00:01:00Z", listOf(ReservationItemWire("variant-1", "warehouse-1", 1)))
        val inventory = FakeClient(posts = listOf(InternalHttpResponse(200, compact.encodeToString(reservation))))
        val order = FakeClient(posts = listOf(InternalHttpResponse(200, compact.encodeToString(OrderWire("order-1", "CREATED", 590)))))
        val payment = FakeClient(posts = listOf(InternalHttpResponse(200, compact.encodeToString(PaymentWire("payment-1", "AUTHORIZED")))))
        val promotionApply = FakeClient(posts = listOf(InternalHttpResponse(200, compact.encodeToString(RedemptionWire("redemption-1", "promotion-1", null, "user-1", null, 0, "INR", "RESERVED")))))
        val shipment = FakeClient(posts = listOf(InternalHttpResponse(200, compact.encodeToString(ShipmentWire("shipment-1", "CREATED")))))
        val downstream = CheckoutClients(compact, "warehouse-1", clients(inventory = inventory, order = order, payment = payment, promotion = promotionApply, shipping = shipment))

        kotlinx.coroutines.runBlocking {
            downstream.reserve("user-1", "", "key-1", details.items, "internal")
            downstream.createOrder("user-1", "", "internal", "checkout-1", "reservation-1", details, totals)
            downstream.createPayment("user-1", "internal", "checkout-1", "order-1", totals, request(), details)
            downstream.applyPromotion("user-1", "internal", "checkout-1", "order-1", details.items, request(), totals)
            downstream.createShipment("user-1", "internal", "checkout-1", "order-1", details, totals, request())
        }

        assertTrue(!order.requests.single().body.contains("initialStatus"))
        assertTrue(!promotionApply.requests.single().body.contains("couponCode"))
        assertTrue(!promotionApply.requests.single().body.contains("\"orderId\":null"))
    }

    private fun request(couponCode: String? = null, billingAddressId: String? = null) = CheckoutRequest("cart-1", "address-1", billingAddressId, CheckoutShippingMethod.EXPRESS, "token", "HTTP", "INR", couponCode)
    private fun totals() = CheckoutTotals(1000, 0, 100, 50, 180, 1130, "INR")
    private fun address() = AddressSnapshotWire("address-1", "Customer", "+911234567890", "Line 1", null, "Pune", "MH", "411001", "IN")
    private fun details() = CheckoutDetails(listOf(OrderItemWire("product-1", "variant-1", "Shoe", "SKU-1", "seller-1", 1, 500, 0, 0, 500, "INR", "v1")), address(), address(), "cart-1")
    private fun addressJson() = """[{"id":"address-1","label":"HOME","recipientName":"Customer","phone":"+911234567890","line1":"Line 1","line2":null,"city":"Pune","state":"MH","postalCode":"411001","country":"IN","latitude":null,"longitude":null,"isDefault":true},{"id":"address-2","label":"WORK","recipientName":"Customer","phone":"+911234567890","line1":"Line 2","line2":"Floor 2","city":"Pune","state":"MH","postalCode":"411002","country":"IN","latitude":18.5,"longitude":73.8,"isDefault":false}]"""
    private fun productJson() = """{"id":"product-1","name":"Shoe","variants":[{"id":"variant-1","productId":"product-1","sku":"SKU-1","barcode":null,"attributes":{"size":"9"}}]}"""
    private fun cartJson(valid: Boolean, empty: Boolean) = """{"cart":{"id":"cart-1","userId":"user-1","currency":"INR","version":1,"items":${if (empty) "[]" else "[{\"id\":\"item-1\",\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"quantity\":2,\"unitPriceMinor\":500,\"currency\":\"INR\",\"priceVersion\":\"v1\",\"addedAt\":\"2026-08-20T00:00:00Z\",\"updatedAt\":\"2026-08-20T00:00:00Z\"}]"}},"valid":$valid,"warnings":[{"variantId":"variant-1","code":"STALE","message":"${if (valid && !empty) "ignored" else if (!valid) "stale cart" else "cart is empty"}"}]}"""

    private fun clients(
        cart: FakeClient = FakeClient(),
        pricing: FakeClient = FakeClient(),
        promotion: FakeClient = FakeClient(),
        inventory: FakeClient = FakeClient(),
        identity: FakeClient = FakeClient(),
        shipping: FakeClient = FakeClient(),
        order: FakeClient = FakeClient(),
        payment: FakeClient = FakeClient(),
        catalog: FakeClient = FakeClient(),
    ) = mapOf("cart" to cart, "pricing" to pricing, "promotion" to promotion, "inventory" to inventory, "identity" to identity, "shipping" to shipping, "order" to order, "payment" to payment, "catalog" to catalog)

    private class FakeClient(
        posts: List<InternalHttpResponse> = emptyList(),
        gets: List<InternalHttpResponse> = emptyList(),
    ) : CheckoutHttpClient {
        private val postResponses = ArrayDeque(posts)
        private val getResponses = ArrayDeque(gets)
        val requests = mutableListOf<Request>()
        override fun get(path: String, headers: Map<String, String>): InternalHttpResponse {
            requests += Request("GET", path, "", headers)
            return if (getResponses.isEmpty()) InternalHttpResponse(200, "{}") else getResponses.removeFirst()
        }
        override fun post(path: String, body: String, headers: Map<String, String>): InternalHttpResponse {
            requests += Request("POST", path, body, headers)
            return if (postResponses.isEmpty()) InternalHttpResponse(200, "{}") else postResponses.removeFirst()
        }
    }

    private data class Request(val method: String, val path: String, val body: String, val headers: Map<String, String>)
}
