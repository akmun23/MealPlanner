package com.example.mealplanner

import java.time.DayOfWeek
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekRegenerationConfirmationTest {

    @Test
    fun firstGenerationDoesNotPromptWhenNoMealsArePlanned() {
        assertFalse(WeekRules.needsRegenerationConfirmation(
            currentPlan = orderedWeekDays.associateWith { null as String? },
            sandwichRecipeId = null
        ))
    }

    @Test
    fun asksBeforeReplacingAnExistingMealPlan() {
        assertTrue(WeekRules.needsRegenerationConfirmation(
            currentPlan = mapOf(DayOfWeek.MONDAY to "recipe-id"),
            sandwichRecipeId = null
        ))
    }

    @Test
    fun asksWhenOnlyTheHouseholdSandwichHasBeenChosen() {
        assertTrue(WeekRules.needsRegenerationConfirmation(
            currentPlan = emptyMap(),
            sandwichRecipeId = "sandwich-id"
        ))
    }
}
