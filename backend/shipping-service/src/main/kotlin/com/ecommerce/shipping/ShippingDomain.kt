package com.ecommerce.shipping

import kotlinx.serialization.Serializable

@Serializable enum class ShipmentStatus { CREATED, LABEL_PENDING, LABEL_CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED, RETURN_REQUESTED, RETURN_PICKUP, RETURNED }
@Serializable enum class ShippingMethod { STANDARD, EXPRESS, SAME_DAY }
private val shipmentTransitions=mapOf(ShipmentStatus.CREATED to setOf(ShipmentStatus.LABEL_PENDING,ShipmentStatus.CANCELLED),ShipmentStatus.LABEL_PENDING to setOf(ShipmentStatus.LABEL_CREATED,ShipmentStatus.EXCEPTION,ShipmentStatus.CANCELLED),ShipmentStatus.LABEL_CREATED to setOf(ShipmentStatus.PICKED_UP,ShipmentStatus.CANCELLED),ShipmentStatus.PICKED_UP to setOf(ShipmentStatus.IN_TRANSIT,ShipmentStatus.EXCEPTION),ShipmentStatus.IN_TRANSIT to setOf(ShipmentStatus.OUT_FOR_DELIVERY,ShipmentStatus.EXCEPTION),ShipmentStatus.OUT_FOR_DELIVERY to setOf(ShipmentStatus.DELIVERED,ShipmentStatus.EXCEPTION),ShipmentStatus.DELIVERED to setOf(ShipmentStatus.RETURN_REQUESTED),ShipmentStatus.RETURN_REQUESTED to setOf(ShipmentStatus.RETURN_PICKUP),ShipmentStatus.RETURN_PICKUP to setOf(ShipmentStatus.RETURNED))
fun assertShipmentTransition(from:ShipmentStatus,to:ShipmentStatus){if(from!=to&&to !in shipmentTransitions.getOrDefault(from,emptySet()))error("Shipment cannot transition from $from to $to")}
@Serializable data class ShippingAddress(val recipientName:String,val phone:String,val line1:String,val line2:String?=null,val city:String,val state:String,val postalCode:String,val country:String)
@Serializable data class ShipmentItem(val variantId:String,val quantity:Int)
@Serializable data class ShippingQuoteRequest(val address:ShippingAddress,val items:List<ShipmentItem>,val method:ShippingMethod,val currency:String)
@Serializable data class ShippingQuote(val quoteId:String,val method:ShippingMethod,val amountMinor:Long,val currency:String,val expiresAt:String)
@Serializable data class ShipmentCreateRequest(val orderId:String,val userId:String,val address:ShippingAddress,val items:List<ShipmentItem>,val method:ShippingMethod,val amountMinor:Long,val currency:String)
@Serializable data class ShipmentResponse(val id:String,val orderId:String,val userId:String,val provider:String,val providerShipmentId:String?,val status:ShipmentStatus,val method:ShippingMethod,val trackingNumber:String?,val carrier:String?,val amountMinor:Long,val currency:String,val createdAt:String,val updatedAt:String)
@Serializable data class TrackingWebhook(val providerEventId:String,val providerShipmentId:String,val status:ShipmentStatus,val trackingNumber:String?,val payload:Map<String,String> = emptyMap())
data class ProviderShipment(val providerShipmentId:String,val status:ShipmentStatus,val trackingNumber:String?,val carrier:String?)
interface ShippingProvider { val name:String; fun quote(request:ShippingQuoteRequest):ShippingQuote; fun create(request:ShipmentCreateRequest):ProviderShipment; fun track(providerShipmentId:String):ProviderShipment; fun cancel(providerShipmentId:String):ProviderShipment; fun verifyWebhook(body:String,signature:String?):Boolean }
