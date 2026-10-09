package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryRulesTest {

    private val recipe = Recipe(
        id = "recipe",
        name = "Test",
        ingredients = listOf(
            RecipeIngredient(
                ingredientId = "rice",
                amountBase = 200.0
            ),
            RecipeIngredient(
                ingredientId = "milk",
                amountBase = 500.0
            )
        )
    )

    @Test
    fun canMakeRequiresAllIngredientQuantities() {
        assertTrue(
            PantryRules.canMake(
                recipe = recipe,
                pantry = mapOf(
                    "rice" to 200.0,
                    "milk" to 500.0
                )
            )
        )

        assertFalse(
            PantryRules.canMake(
                recipe = recipe,
                pantry = mapOf(
                    "rice" to 200.0,
                    "milk" to 499.0
                )
            )
        )
    }

    @Test
    fun coverageUsesPartialPantryAmounts() {
        val coverage = PantryRules.coverage(
            recipe = recipe,
            pantry = mapOf(
                "rice" to 100.0,
                "milk" to 500.0
            )
        )

        assertEquals(0.75, coverage, 0.0001)
    }

    @Test
    fun emptyRecipeIsNotMakeable() {
        val emptyRecipe = Recipe(
            id = "empty",
            name = "Empty",
            ingredients = emptyList()
        )

        assertFalse(
            PantryRules.canMake(
                recipe = emptyRecipe,
                pantry = emptyMap()
            )
        )
    }

    private val repeatedIngredientRecipe = Recipe(
        id = "repeated",
        name = "Repeated ingredients",
        ingredients = listOf(
            RecipeIngredient("rice", 200.0),
            RecipeIngredient("milk", 500.0),
            RecipeIngredient("rice", 150.0)
        )
    )

    @Test
    fun repeatedIngredientRequiresTotalStock() {
        assertFalse(
            PantryRules.canMake(
                repeatedIngredientRecipe,
                mapOf("rice" to 300.0, "milk" to 500.0)
            )
        )
        assertTrue(
            PantryRules.canMake(
                repeatedIngredientRecipe,
                mapOf("rice" to 350.0, "milk" to 500.0)
            )
        )
    }

    @Test
    fun repeatedIngredientCoverageUsesCombinedAmount() {
        assertEquals(
            0.75,
            PantryRules.coverage(
                repeatedIngredientRecipe,
                mapOf("rice" to 175.0, "milk" to 500.0)
            ),
            0.0001
        )
    }

    @Test
    fun cookingDeductsCombinedAmountsAndNeverGoesBelowZero() {
        assertEquals(
            mapOf("rice" to 150.0, "milk" to 100.0),
            PantryRules.remainingAfterCooking(
                repeatedIngredientRecipe,
                mapOf("rice" to 500.0, "milk" to 600.0)
            )
        )
        assertEquals(
            mapOf("rice" to 0.0, "milk" to 0.0),
            PantryRules.remainingAfterCooking(
                repeatedIngredientRecipe,
                mapOf("rice" to 100.0, "milk" to 100.0)
            )
        )
    }


    @Test
    fun duplicateIngredientContributesOnlyItsCombinedPantryCoverage() {
        assertEquals(
            1.5,
            PantryRules.coveredIngredientUnits(
                repeatedIngredientRecipe,
                mapOf("rice" to 175.0, "milk" to 500.0)
            ),
            0.0001
        )
        assertEquals(
            0.0,
            PantryRules.coveredIngredientUnits(
                repeatedIngredientRecipe,
                emptyMap()
            ),
            0.0001
        )
    }

}
