package com.example.mealplanner

import android.content.Context
import kotlinx.serialization.json.Json
import androidx.core.content.edit

/** Local, household-scoped planner preferences. They work without a network. */
internal class PlannerSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "mealplanner_planner_preferences",
        Context.MODE_PRIVATE
    )

    fun load(householdId: String): PlannerWeights =
        preferences.getString(key(householdId), null)?.let { payload ->
            runCatching { Json.decodeFromString<PlannerWeights>(payload) }
                .getOrNull()?.takeIf(::valid)
        } ?: PlannerWeights()

    fun save(householdId: String, weights: PlannerWeights) {
        require(valid(weights)) { "Invalid planner scoring preference" }
        preferences.edit {
            putString(key(householdId), Json.encodeToString(weights))
        }
    }

    fun reset(householdId: String) {
        preferences.edit { remove(key(householdId)) }
    }

    private fun valid(weights: PlannerWeights): Boolean =
        listOf(
            weights.pantryCoverage,
            weights.ingredientReuse,
            weights.duplicateRecipe,
            weights.repeatedProtein,
            weights.adjacentProtein,
            weights.repeatedCategory,
            weights.adjacentCategory,
            weights.recency7Days,
            weights.recency14Days,
            weights.recency30Days,
            weights.keepSameDay,
            weights.jitter
        ).all { it.isFinite() && it >= 0.0 && it <= 1000.0 }

    private fun key(householdId: String) = "planner-$householdId"
}
