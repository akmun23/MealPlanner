package com.example.mealplanner

import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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
    private val http = HttpClient(CIO)
    private val json = Json

    suspend fun status(): Result<JsonObject> = call("status")

    suspend fun saveKey(apiKey: String): Result<JsonObject> =
        call("set-key", apiKey = apiKey)

    suspend fun verifySavedKey(): Result<JsonObject> = call("verify-key")

    suspend fun removeKey(): Result<JsonObject> = call("remove-key")

    suspend fun refresh(householdId: String): Result<JsonObject> =
        call("refresh", householdId = householdId)

    suspend fun discoveryStatus(householdId: String): Result<JsonObject> =
        call("discovery-status", householdId = householdId)

    suspend fun discoverProducts(householdId: String): Result<JsonObject> =
        call("discover", householdId = householdId)

    private suspend fun call(
        action: String,
        apiKey: String? = null,
        householdId: String? = null
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken
                ?: throw BilkaApiError("Log ind for at bruge Bilka API")
            val payload = buildJsonObject {
                put("action", action)
                apiKey?.let { put("apiKey", it) }
                householdId?.let { put("householdId", it) }
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
                    "prej_key_invalid" -> "Nøglen er blevet ugyldig. Tilføj den igen"
                    "not_authenticated" -> "Log ind igen"
                    "prej_burst_limit" -> "For mange kald. Prøv igen senere"
                    else -> "Bilka API er ikke tilgængeligt lige nu"
                })
            }
            result
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                Result.failure(
                    if (throwable is BilkaApiError) throwable
                    else BilkaApiError("Forbindelsen til Bilka API fejlede")
                )
            }
        )
    }
}
