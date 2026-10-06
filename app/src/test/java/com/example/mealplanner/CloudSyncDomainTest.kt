package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSyncDomainTest {

    @Test
    fun pantryChangeOnlyTargetsPantryDomain() {
        assertEquals(
            CloudSyncDomain.PANTRY,
            syncDomainForTable("pantry_items")
        )
    }

    @Test
    fun recipeSideTablesShareRecipeDomain() {
        listOf(
            "recipes",
            "recipe_ingredients",
            "recipe_rotation_settings",
            "recipe_time_settings",
            "recipe_category_assignments"
        ).forEach { table ->
            assertEquals(
                CloudSyncDomain.RECIPES,
                syncDomainForTable(table)
            )
        }
    }

    @Test
    fun weekAndShoppingRemainSeparateDomains() {
        assertEquals(
            CloudSyncDomain.WEEK,
            syncDomainForTable("planned_meals")
        )
        assertEquals(
            CloudSyncDomain.SHOPPING,
            syncDomainForTable("shopping_checks")
        )
    }

    @Test
    fun unknownTableIsIgnored() {
        assertNull(syncDomainForTable("not_watched"))
    }
}
