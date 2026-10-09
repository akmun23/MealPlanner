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
    fun pantryAvailabilityFilterUsesLiveAmountsWithoutRemovingItems() {
        val amounts = mapOf("tomato" to 3.0, "pasta" to 0.0)
        fun view(filter: PantryStockFilter) = visiblePantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            pantry = amounts,
            searchQuery = "",
            selectedCategoryId = null,
            stockFilter = filter,
            sortOrder = PantrySortOrder.NAME_ASC
        ).map { it.id }

        assertEquals(listOf("pasta", "tomato"), view(PantryStockFilter.ALL))
        assertEquals(listOf("tomato"), view(PantryStockFilter.IN_STOCK))
        assertEquals(listOf("pasta"), view(PantryStockFilter.OUT_OF_STOCK))
    }

    @Test
    fun categoryAndStockFiltersCombineAndSortingUsesQuantity() {
        val ingredients = listOf(tomato, pasta, tomato.copy(id = "cherry", name = "Cherrytomat"))
        val amounts = mapOf("tomato" to 4.0, "pasta" to 500.0, "cherry" to 2.0)
        val result = visiblePantryIngredients(
            ingredients = ingredients,
            categories = listOf(produce, pantry),
            pantry = amounts,
            searchQuery = "tomat",
            selectedCategoryId = produce.id,
            stockFilter = PantryStockFilter.IN_STOCK,
            sortOrder = PantrySortOrder.QUANTITY_DESC
        )
        assertEquals(listOf("tomato", "cherry"), result.map { it.id })

        val reverse = visiblePantryIngredients(
            ingredients = ingredients,
            categories = listOf(produce, pantry),
            pantry = amounts,
            searchQuery = "",
            selectedCategoryId = null,
            stockFilter = PantryStockFilter.ALL,
            sortOrder = PantrySortOrder.NAME_DESC
        )
        assertEquals(listOf("tomato", "pasta", "cherry"), reverse.map { it.id })
    }

    @Test
    fun changingSortChoiceReordersTheSameVisibleIngredients() {
        val amounts = mapOf("tomato" to 10.0, "pasta" to 1.0)
        fun ids(sort: PantrySortOrder) = visiblePantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            pantry = amounts,
            searchQuery = "",
            selectedCategoryId = null,
            stockFilter = PantryStockFilter.ALL,
            sortOrder = sort
        ).map { it.id }

        assertEquals(listOf("pasta", "tomato"), ids(PantrySortOrder.NAME_ASC))
        assertEquals(listOf("tomato", "pasta"), ids(PantrySortOrder.NAME_DESC))
        assertEquals(listOf("tomato", "pasta"), ids(PantrySortOrder.QUANTITY_DESC))
        assertEquals(listOf("tomato", "pasta"), ids(PantrySortOrder.CATEGORY))
        // Switching back must restore original alphabetical order.
        assertEquals(listOf("pasta", "tomato"), ids(PantrySortOrder.NAME_ASC))
    }

    @Test
    fun changingStockFilterUpdatesItemsWithoutChangingSearch() {
        val amounts = mapOf("tomato" to 2.0)
        fun ids(stock: PantryStockFilter) = visiblePantryIngredients(
            ingredients = listOf(tomato, pasta),
            categories = listOf(produce, pantry),
            pantry = amounts,
            searchQuery = "a",
            selectedCategoryId = null,
            stockFilter = stock,
            sortOrder = PantrySortOrder.NAME_ASC
        ).map { it.id }

        assertEquals(listOf("pasta", "tomato"), ids(PantryStockFilter.ALL))
        assertEquals(listOf("tomato"), ids(PantryStockFilter.IN_STOCK))
        assertEquals(listOf("pasta"), ids(PantryStockFilter.OUT_OF_STOCK))
    }

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
