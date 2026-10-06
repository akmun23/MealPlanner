package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class IngredientSortingTest {

    @Test
    fun sortsRecipeIngredientsByCategoryThenIngredientName() {
        val categories = listOf(
            IngredientCategory(
                id = "veg",
                name = "Grønt",
                position = 0
            ),
            IngredientCategory(
                id = "dry",
                name = "Kolonial",
                position = 1
            )
        )
        val ingredients = listOf(
            Ingredient(
                id = "pasta",
                name = "Pasta",
                quantityType = QuantityType.MASS,
                preferredUnit = MeasurementUnit.GRAM,
                shoppingCategoryId = "dry"
            ),
            Ingredient(
                id = "tomato",
                name = "Tomat",
                quantityType = QuantityType.COUNT,
                preferredUnit = MeasurementUnit.PIECE,
                shoppingCategoryId = "veg"
            ),
            Ingredient(
                id = "cucumber",
                name = "Agurk",
                quantityType = QuantityType.COUNT,
                preferredUnit = MeasurementUnit.PIECE,
                shoppingCategoryId = "veg"
            )
        )
        val items = listOf(
            RecipeIngredient("pasta", 100.0),
            RecipeIngredient("tomato", 1.0),
            RecipeIngredient("cucumber", 1.0)
        )

        val sorted = sortByIngredientCategory(
            items = items,
            ingredientId = RecipeIngredient::ingredientId,
            ingredients = ingredients,
            categories = categories
        )

        assertEquals(
            listOf("cucumber", "tomato", "pasta"),
            sorted.map { it.ingredientId }
        )
    }

    @Test
    fun uncategorizedIngredientsSortLast() {
        val categories = listOf(
            IngredientCategory(
                id = "veg",
                name = "Grønt",
                position = 0
            )
        )
        val ingredients = listOf(
            Ingredient(
                id = "unknown",
                name = "Salt",
                quantityType = QuantityType.MASS,
                preferredUnit = MeasurementUnit.GRAM
            ),
            Ingredient(
                id = "tomato",
                name = "Tomat",
                quantityType = QuantityType.COUNT,
                preferredUnit = MeasurementUnit.PIECE,
                shoppingCategoryId = "veg"
            )
        )

        val sorted = sortByIngredientCategory(
            items = ingredients,
            ingredientId = Ingredient::id,
            ingredients = ingredients,
            categories = categories
        )

        assertEquals(
            listOf("tomato", "unknown"),
            sorted.map { it.id }
        )
    }
}
