package com.example.mealplanner

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supabase PostgREST needs an explicit boolean when writing planned_meals.
 * A Kotlin default on locked=false silently omits the property on upsert.
 */
class PlannedMealSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun unlockedDayAlwaysIncludesExplicitFalse() {
        val encoded = json.encodeToString(
            row(locked = false)
        )
        val fields = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive(false), fields["locked"])
        assertEquals(JsonPrimitive("weekly-id"), fields["weekly_plan_id"])
        assertEquals(JsonPrimitive(1), fields["day_of_week"])
        assertTrue("serialized row must include the locked key", fields.containsKey("locked"))
    }

    @Test
    fun lockedDayIncludesExplicitTrue() {
        val fields = json.parseToJsonElement(
            json.encodeToString(row(locked = true))
        ).jsonObject

        assertEquals(JsonPrimitive(true), fields["locked"])
    }

    @Test
    fun bothLockValuesRoundTrip() {
        for (locked in listOf(false, true)) {
            val expected = row(locked)
            assertEquals(expected, json.decodeFromString<PlannedMealRow>(
                json.encodeToString(expected)
            ))
        }
    }

    private fun row(locked: Boolean) = PlannedMealRow(
        householdId = "household-id",
        weeklyPlanId = "weekly-id",
        dayOfWeek = 1,
        mode = "AUTO",
        recipeId = "recipe-id",
        locked = locked
    )
}
