package com.ecommerce.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    // Every backend service's own Json config sets this too (see e.g. checkout-service's
    // Application.kt). Without it, a request field left at its DTO default is silently omitted
    // from the JSON body -- and if the server's own default for that field differs (as
    // CheckoutRequest.paymentProvider's does: "COD" here vs. "HTTP" server-side), the server
    // applies its own default instead of the caller's intended value.
    encodeDefaults = true
}

/**
 * Engine-agnostic on purpose: the concrete engine (Js on wasmJs, a JVM engine if a JVM app ever
 * consumes this module) is supplied by the caller, so this module needs no per-platform
 * expect/actual and stays testable with MockEngine everywhere.
 */
fun createHttpClient(engine: HttpClientEngine, json: Json = defaultJson()): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(json)
        }
    }
