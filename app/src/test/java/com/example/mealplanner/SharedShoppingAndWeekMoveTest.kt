package com.example.mealplanner

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedShoppingAndWeekMoveTest {
    private val mon = DayOfWeek.MONDAY
    private val fri = DayOfWeek.FRIDAY
    private val dinners = mapOf(
        mon to "pasta",
        fri to "burrito"
    )
    private val home = DayOfWeek.entries.associateWith { DayMode.AUTO }

    @Test
    fun movingFridayBurritoToMondaySwapsRecipesWithoutLosingEither() {
        assertTrue(
            WeekMealMoveRules.allowed(
                fri, mon, dinners, home, emptySet(), null, null
            )
        )
        val moved = WeekMealMoveRules.swappedPlan(fri, mon, dinners)
        assertEquals("burrito", moved[mon])
        assertEquals("pasta", moved[fri])
        assertEquals(dinners.values.filterNotNull().sorted(), moved.values.filterNotNull().sorted())
    }

    @Test
    fun lockedOrAwayDaysRejectDragAndDrop() {
        assertFalse(
            WeekMealMoveRules.allowed(fri, mon, dinners, home, setOf(mon), null, null)
        )
        assertFalse(
            WeekMealMoveRules.allowed(
                fri, mon, dinners,
                home + (mon to DayMode.AWAY), emptySet(), null, null
            )
        )
        assertFalse(
            WeekMealMoveRules.allowed(mon, mon, dinners, home, emptySet(), null, null)
        )
        assertFalse(
            WeekMealMoveRules.allowed(
                DayOfWeek.TUESDAY, mon, dinners, home, emptySet(), null, null
            )
        )
    }

    @Test
    fun sandwichAssignmentMovesWithItsSpecialDinner() {
        val moved = WeekMealMoveRules.movedSandwichDay(fri, mon, fri)
        assertEquals(mon, moved)
        assertTrue(
            WeekMealMoveRules.allowed(
                fri, mon, dinners, home, emptySet(), fri, "sandwich"
            )
        )
    }

    @Test
    fun manualShoppingIngredientIsIncludedOnlyWhenExplicitlySelected() {
        val skyr = Ingredient(
            id = "skyr", name = "Skyr",
            quantityType = QuantityType.MASS,
            preferredUnit = MeasurementUnit.GRAM
        )
        val milk = Ingredient(
            id = "milk", name = "Mælk",
            quantityType = QuantityType.VOLUME,
            preferredUnit = MeasurementUnit.LITRE
        )
        val planned = listOf(ShoppingItem(milk, 1000.0, 0.0))
        val extra = ManualShoppingRules.additionalItems(
            planned = planned,
            ingredients = listOf(skyr, milk),
            pantry = mapOf("skyr" to 500.0),
            inclusion = mapOf("skyr" to true, "milk" to true)
        )
        assertEquals(listOf("skyr"), extra.map { it.ingredient.id })
        assertTrue(extra.single().manualOnly)
        assertEquals(0.0, extra.single().requiredBase, 0.000001)
        assertEquals(500.0, extra.single().pantryBase, 0.000001)
        assertTrue(
            ManualShoppingRules.additionalItems(
                planned, listOf(skyr, milk), emptyMap(),
                mapOf("skyr" to false)
            ).isEmpty()
        )
    }

    @Test
    fun manualRowsNeverCreateGhostIngredientsOrDuplicates() {
        val skyr = Ingredient(
            id = "skyr", name = "Skyr",
            quantityType = QuantityType.MASS,
            preferredUnit = MeasurementUnit.GRAM
        )
        val existingRecipeItem = ShoppingItem(skyr, 300.0, 0.0)
        assertTrue(
            ManualShoppingRules.additionalItems(
                listOf(existingRecipeItem), listOf(skyr), emptyMap(),
                mapOf("skyr" to true)
            ).isEmpty()
        )
        assertTrue(
            ManualShoppingRules.additionalItems(
                emptyList(), listOf(skyr), emptyMap(),
                mapOf("deleted-ingredient" to true)
            ).isEmpty()
        )
    }
}
