package com.example.mealplanner

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Retains only the most recently selected household metadata for each account.
 * The shared meal data remains in Room and is always keyed by household id.
 * Never use this cache to grant access when there is no authenticated user.
 */
internal class CachedHouseholdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "mealplanner_last_household",
        Context.MODE_PRIVATE
    )

    fun get(userId: String): HouseholdDto? =
        preferences.getString(key(userId), null)?.let { payload ->
            runCatching {
                Json.decodeFromString<HouseholdDto>(payload)
            }.getOrNull()
        }

    fun put(userId: String, household: HouseholdDto) {
        preferences.edit()
            .putString(key(userId), Json.encodeToString(household))
            .apply()
    }

    fun remove(userId: String) {
        preferences.edit().remove(key(userId)).apply()
    }

    private fun key(userId: String) = "account-household-$userId"
}
