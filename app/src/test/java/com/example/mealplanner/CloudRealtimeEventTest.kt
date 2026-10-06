package com.example.mealplanner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRealtimeEventTest {

    @Test
    fun pantryUpdateBecomesDirectPatch() {
        val event = realtimeEventForPayload(
            table = "pantry_items",
            record = buildJsonObject {
                put("household_id", JsonPrimitive("home"))
                put("ingredient_id", JsonPrimitive("chicken"))
                put("amount_base", JsonPrimitive(500.0))
            },
            oldRecord = null,
            expectedHouseholdId = "home"
        )

        assertTrue(event is CloudRealtimeEvent.PantryChanged)
        event as CloudRealtimeEvent.PantryChanged
        assertEquals("chicken", event.ingredientId)
        assertEquals(500.0, event.amountBase ?: -1.0, 0.0001)
    }

    @Test
    fun foreignHouseholdEventIsIgnored() {
        val event = realtimeEventForPayload(
            table = "shopping_checks",
            record = buildJsonObject {
                put("household_id", JsonPrimitive("other-home"))
                put("weekly_plan_id", JsonPrimitive("week"))
                put("ingredient_id", JsonPrimitive("milk"))
                put("checked", JsonPrimitive(true))
            },
            oldRecord = null,
            expectedHouseholdId = "home"
        )

        assertNull(event)
    }

    @Test
    fun deleteWithPrimaryKeyOnlyStillRemovesPantryItem() {
        val event = realtimeEventForPayload(
            table = "pantry_items",
            record = null,
            oldRecord = buildJsonObject {
                put("ingredient_id", JsonPrimitive("chicken"))
            },
            expectedHouseholdId = "home"
        )

        assertEquals(
            CloudRealtimeEvent.PantryChanged(
                ingredientId = "chicken",
                amountBase = null
            ),
            event
        )
    }

    @Test
    fun complexTableStillUsesDomainReconciliation() {
        val event = realtimeEventForPayload(
            table = "recipe_ingredients",
            record = buildJsonObject {
                put("household_id", JsonPrimitive("home"))
                put("recipe_id", JsonPrimitive("recipe"))
                put("ingredient_id", JsonPrimitive("ingredient"))
                put("amount_base", JsonPrimitive(100.0))
            },
            oldRecord = null,
            expectedHouseholdId = "home"
        )

        assertEquals(
            CloudRealtimeEvent.DomainChanged(
                CloudSyncDomain.RECIPES
            ),
            event
        )
    }
}
