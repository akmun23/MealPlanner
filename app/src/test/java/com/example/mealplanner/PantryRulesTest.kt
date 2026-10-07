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
}
