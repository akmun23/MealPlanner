package com.example.mealplanner

import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * Authenticated calls to MealPlanner's Edge Function. The Prej API key is
 * transmitted once to Supabase for Vault storage; never write it to preferences,
 * Room, logs, BuildConfig or a third-party endpoint from Android.
 */
private class BilkaApiError(message: String) : RuntimeException(message)

internal object BilkaPrejClient {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 35_000
        }
    }
    private val json = Json

    suspend fun status(): Result<JsonObject> = call("status")

    suspend fun saveKey(apiKey: String): Result<JsonObject> =
        call("set-key", apiKey = apiKey)

    suspend fun verifySavedKey(): Result<JsonObject> = call("verify-key")

    suspend fun removeKey(): Result<JsonObject> = call("remove-key")

    suspend fun nightlyStatus(householdId: String): Result<JsonObject> =
        call("nightly-status", householdId = householdId)

    suspend fun setNightlyEnabled(
        householdId: String,
        enabled: Boolean
    ): Result<JsonObject> =
        call("set-nightly", householdId = householdId, enabled = enabled)

    suspend fun refresh(householdId: String): Result<JsonObject> =
        call("refresh", householdId = householdId)

    suspend fun manualSearch(
        householdId: String,
        ingredientId: String,
        query: String,
        page: Int = 1
    ): Result<JsonObject> =
        call("manual-search", householdId = householdId,
            ingredientId = ingredientId, query = query, page = page)

    suspend fun lookupEan(
        householdId: String, ingredientId: String, gtin: String
    ): Result<JsonObject> =
        call("ean-lookup", householdId = householdId,
            ingredientId = ingredientId, gtin = gtin)

    suspend fun bulkSearch(
        householdId: String, ingredientIds: List<String>
    ): Result<JsonObject> =
        call("bulk-search", householdId = householdId, ingredientIds = ingredientIds)

    suspend fun shoppingContext(
        householdId: String,
        weekId: String
    ): Result<JsonObject> =
        call("shopping-context", householdId = householdId, weekId = weekId)

    suspend fun recordPurchase(
        householdId: String,
        weekId: String,
        ingredientId: String,
        productId: String?,
        manualProductName: String?,
        packageCount: Int?,
        amountBase: Double,
        paidTotalOre: Long?
    ): Result<JsonObject> =
        call("record-purchase", householdId = householdId, weekId = weekId,
            ingredientId = ingredientId, productId = productId,
            manualProductName = manualProductName, packageCount = packageCount,
            amountBase = amountBase, paidTotalOre = paidTotalOre)

    suspend fun catalog(householdId: String, ingredientIds: List<String>? = null): Result<JsonObject> =
        call("catalog", householdId = householdId, ingredientIds = ingredientIds)

    suspend fun chooseProduct(
        householdId: String,
        ingredientId: String,
        productId: String
    ): Result<JsonObject> =
        call("choose-product", householdId = householdId,
            ingredientId = ingredientId, productId = productId)

    suspend fun discoveryStatus(householdId: String): Result<JsonObject> =
        call("discovery-status", householdId = householdId)

    private suspend fun call(
        action: String,
        apiKey: String? = null,
        householdId: String? = null,
        ingredientId: String? = null,
        ingredientIds: List<String>? = null,
        productId: String? = null,
        query: String? = null,
        gtin: String? = null,
        page: Int? = null,
        enabled: Boolean? = null,
        weekId: String? = null,
        manualProductName: String? = null,
        packageCount: Int? = null,
        amountBase: Double? = null,
        paidTotalOre: Long? = null
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken
                ?: throw BilkaApiError("Log ind for at bruge Bilka API")
            val payload = buildJsonObject {
                put("action", action)
                apiKey?.let { put("apiKey", it) }
                householdId?.let { put("householdId", it) }
                ingredientId?.let { put("ingredientId", it) }
                ingredientIds?.let { ids ->
                    put("ingredientIds", buildJsonArray { ids.forEach { add(it) } })
                }
                productId?.let { put("productId", it) }
                query?.let { put("query", it) }
                gtin?.let { put("gtin", it) }
                page?.let { put("page", it) }
                enabled?.let { put("enabled", it) }
                weekId?.let { put("weekId", it) }
                manualProductName?.let { put("manualProductName", it) }
                packageCount?.let { put("packageCount", it) }
                amountBase?.let { put("amountBase", it) }
                paidTotalOre?.let { put("paidTotalOre", it) }
            }
            val response = http.post(
                BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/bilka-prej"
            ) {
                header("Authorization", "Bearer $accessToken")
                header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            val result = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: throw BilkaApiError("Ugyldigt serversvar")
            if (response.status.value !in 200..299) {
                val code = result["error"]?.jsonPrimitive?.content ?: "server_error"
                throw BilkaApiError(when (code) {
                    "invalid_key", "invalid_key_format" -> "Prej API-nøglen er ugyldig"
                    "prej_rate_limited", "daily_budget_exhausted", "prej_daily_limit" ->
                        "Dagens Prej-kvote er brugt"
                    "prej_key_not_configured" -> "Tilføj først din Prej API-nøgle"
                    "prej_key_not_verified" ->
                        "Kontrollér først din Prej-nøgle, før automatisk opdatering aktiveres"
                    "nightly_scheduler_not_ready" ->
                        "Automatisk opdatering kan først aktiveres, når serverens tidsplan er klar"
                    "prej_key_invalid" -> "Nøglen er blevet ugyldig. Tilføj den igen"
                    "not_authenticated" -> "Log ind igen"
                    "invalid_search" -> "Skriv et konkret varenavn på mindst to tegn."
                    "invalid_gtin" ->
                        "EAN skal være 8, 12, 13 eller 14 cifre med gyldigt kontrolciffer."
                    "invalid_bulk_selection" -> "Vælg mellem 1 og 5 forskellige ingredienser."
                    "purchase_not_saved_or_already_recorded" ->
                        "Købet kunne ikke gemmes – muligvis er det allerede lagt på Lager."
                    "incompatible_package_units", "package_amount_mismatch" ->
                        "Pakkestørrelsen stemmer ikke med ingrediensen."
                    "invalid_packages", "invalid_amount", "invalid_cost" ->
                        "Kontrollér købt antal, mængde og pris."
                    "not_household_product" ->
                        "Varen er ikke et godkendt Bilka-forslag til denne ingrediens."
                    "invalid_product_choice", "not_a_suggested_product" ->
                        "Denne vare er ikke længere tilgængelig. Opdatér produktlisten."
                    "unpriced_product" ->
                        "Varen kan ses, men mangler en BilkaToGo-pris og kan ikke vælges som fast vare."
                    "unknown_action" ->
                        "Bilka-serveren er ikke opdateret til denne funktion endnu."
                    "prej_unavailable", "invalid_provider_response" ->
                        "Prej svarer ikke med brugbare produktdata lige nu."
                    "prej_burst_limit" -> "For mange kald. Prøv igen senere"
                    else -> "Bilka API kunne ikke gennemføre forespørgslen ($code)."
                })
            }
            result
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                Result.failure(
                    throwable as? BilkaApiError ?: BilkaApiError("Forbindelsen til Bilka API fejlede")
                )
            }
        )
    }
}
