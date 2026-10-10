package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingIngredientRulesTest {
    private fun ingredient(neverBuy: Boolean) = Ingredient(
        id = "water",
        name = "Vand",
        quantityType = QuantityType.VOLUME,
        preferredUnit = MeasurementUnit.MILLILITRE,
        neverBuy = neverBuy
    )

    @Test
    fun ingredientIsStillARecipeRequirementEvenWhenNeverBought() {
        val water = ingredient(neverBuy = true)
        val recipe = Recipe(
            id = "soup",
            name = "Suppe",
            ingredients = listOf(RecipeIngredient("water", 500.0))
        )

        assertEquals(500.0, recipe.ingredients.single().amountBase, 0.0001)
        assertFalse(ShoppingIngredientRules.isAutomaticallyPurchased(water))
    }

    @Test
    fun normalIngredientsRemainPurchasable() {
        assertTrue(ShoppingIngredientRules.isAutomaticallyPurchased(ingredient(false)))
    }

    @Test
    fun existingSnapshotsWithoutFlagDefaultToPurchasable() {
        val oldPayload = """
            {
              "id": "salt",
              "name": "Salt",
              "quantityType": "MASS",
              "preferredUnit": "GRAM"
            }
        """.trimIndent()

        val restored = Json.decodeFromString(Ingredient.serializer(), oldPayload)
        assertFalse(restored.neverBuy)
        assertTrue(ShoppingIngredientRules.isAutomaticallyPurchased(restored))
    }

    @Test
    fun neverBuySurvivesSerializationForOfflineQueueAndBackup() {
        val water = ingredient(neverBuy = true)
        val restored = Json.decodeFromString(
            Ingredient.serializer(),
            Json.encodeToString(Ingredient.serializer(), water)
        )

        assertTrue(restored.neverBuy)
        assertFalse(ShoppingIngredientRules.isAutomaticallyPurchased(restored))
    }
}
