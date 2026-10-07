package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class PantryUiRulesTest {

    private val produce = IngredientCategory(
        id = "produce",
        name = "Frugt og grønt"
    )

    private val pantry = IngredientCategory(
        id = "pantry",
        name = "Kolonial"
    )

    private val tomato = Ingredient(
        id = "tomato",
        name = "Tomat",
        quantityType = QuantityType.COUNT,
        preferredUnit = MeasurementUnit.PIECE,
        shoppingCategoryId = produce.id
    )

    private val pasta = Ingredient(
        id = "pasta",
        name = "Pasta",
        quantityType = QuantityType.MASS,
        preferredUnit = MeasurementUnit.GRAM,
        shoppingCategoryId = pantry.id
    )

    @Test
    fun searchMatchesCategoryName() {
        val result = filterPantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            searchQuery = "kolonial",
            selectedCategoryId = null
        )

        assertEquals(listOf("pasta"), result.map { it.id })
    }

    @Test
    fun categoryFilterCombinesWithSearch() {
        val result = filterPantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            searchQuery = "tom",
            selectedCategoryId = produce.id
        )

        assertEquals(listOf("tomato"), result.map { it.id })
    }

    @Test
    fun categoryFilterExcludesOtherCategories() {
        val result = filterPantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            searchQuery = "",
            selectedCategoryId = pantry.id
        )

        assertEquals(listOf("pasta"), result.map { it.id })
    }
}
