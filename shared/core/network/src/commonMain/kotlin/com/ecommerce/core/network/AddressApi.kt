package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/identity-service's AddressRequest/AddressResponse. Requires a logged-in user. */
@Serializable
data class AddressRequest(
    val label: String,
    val recipientName: String,
    val phone: String,
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String,
    val isDefault: Boolean = false,
)

@Serializable
data class AddressResponse(
    val id: String,
    val label: String,
    val recipientName: String,
    val phone: String,
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String,
    val isDefault: Boolean = false,
)

class AddressApi(private val client: ApiClient) {
    suspend fun list(): ApiResult<List<AddressResponse>> = client.get("/api/v1/addresses")
    suspend fun create(request: AddressRequest): ApiResult<AddressResponse> = client.post("/api/v1/addresses", request)
}
