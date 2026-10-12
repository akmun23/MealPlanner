package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealCompletionRulesTest {
    private val recipe = Recipe(
        id = "dinner",
        name = "Risret",
        ingredients = listOf(
            RecipeIngredient("rice", 200.0),
            RecipeIngredient("milk", 500.0),
            RecipeIngredient("rice", 100.0)
        )
    )
    private val pantry = mapOf("rice" to 500.0, "milk" to 100.0)

    @Test
    fun finishWithExplicitActualUsageOnlyAffectsCheckedIngredients() {
        assertEquals(
            mapOf("rice" to 260.0),
            MealCompletionRules.remainingAfterMeal(
                recipe, pantry, mapOf("rice" to 240.0)
            )
        )
        assertEquals(
            emptyMap<String, Double>(),
            MealCompletionRules.remainingAfterMeal(recipe, pantry, emptyMap())
        )
        assertEquals(pantry, pantry) // Source pantry is never mutated by the preview.
    }

    @Test
    fun cannotConsumeMoreThanStockOrUnexpectedIngredients() {
        assertNull(
            MealCompletionRules.remainingAfterMeal(
                recipe, pantry, mapOf("milk" to 101.0)
            )
        )
        assertNull(
            MealCompletionRules.remainingAfterMeal(
                recipe, pantry, mapOf("unknown" to 1.0)
            )
        )
        assertNull(
            MealCompletionRules.remainingAfterMeal(
                recipe, pantry, mapOf("rice" to Double.NaN)
            )
        )
        assertNull(
            MealCompletionRules.remainingAfterMeal(
                recipe, pantry, mapOf("rice" to -1.0)
            )
        )
    }

    @Test
    fun previousRegistrationPreventsDoubleConsumptionForSameDate() {
        val today = LocalDate.of(2026, 10, 11)
        assertTrue(MealCompletionRules.mayFinish(today, today, emptyList()))
        assertTrue(
            MealCompletionRules.mayFinish(
                today.minusDays(1), today, emptyList()
            )
        )
        assertFalse(
            MealCompletionRules.mayFinish(
                today, today, listOf(MealHistoryEntry("dinner", today))
            )
        )
        assertFalse(
            MealCompletionRules.mayFinish(
                today, today, listOf(MealHistoryEntry("other-recipe", today))
            )
        )
        assertFalse(
            MealCompletionRules.mayFinish(
                today.plusDays(1), today, emptyList()
            )
        )
        assertFalse(
            MealCompletionRules.mayFinish(
                today.minusDays(90), today, emptyList()
            )
        )
    }
}
