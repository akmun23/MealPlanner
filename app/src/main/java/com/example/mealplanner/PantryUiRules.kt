package com.example.mealplanner

internal fun filterPantryIngredients(
    ingredients: List<Ingredient>,
    categories: List<IngredientCategory>,
    searchQuery: String,
    selectedCategoryId: String?
): List<Ingredient> {
    val query = searchQuery.trim()
    val categoryNames = categories.associateBy({ it.id }, { it.name })

    return ingredients
        .asSequence()
        .filter { ingredient ->
            selectedCategoryId == null ||
                ingredient.shoppingCategoryId == selectedCategoryId
        }
        .filter { ingredient ->
            if (query.isBlank()) {
                true
            } else {
                val categoryName = ingredient.shoppingCategoryId
                    ?.let(categoryNames::get)
                    .orEmpty()

                ingredient.name.contains(query, ignoreCase = true) ||
                    categoryName.contains(query, ignoreCase = true)
            }
        }
        .sortedBy { it.name.lowercase() }
        .toList()
}

/** User-facing filters are local UI choices; they never delete inventory. */
internal enum class PantryStockFilter(val label: String) {
    ALL("Alle varer"),
    IN_STOCK("På lager"),
    OUT_OF_STOCK("Mangler")
}

internal enum class PantrySortOrder(val label: String) {
    NAME_ASC("Navn A–Å"),
    NAME_DESC("Navn Å–A"),
    QUANTITY_DESC("Mest på lager"),
    CATEGORY("Kategori")
}

internal fun visiblePantryIngredients(
    ingredients: List<Ingredient>,
    categories: List<IngredientCategory>,
    pantry: Map<String, Double>,
    searchQuery: String,
    selectedCategoryId: String?,
    stockFilter: PantryStockFilter,
    sortOrder: PantrySortOrder
): List<Ingredient> {
    val visible = filterPantryIngredients(
        ingredients = ingredients,
        categories = categories,
        searchQuery = searchQuery,
        selectedCategoryId = selectedCategoryId
    ).filter { ingredient ->
        val amount = pantry[ingredient.id] ?: 0.0
        when (stockFilter) {
            PantryStockFilter.ALL -> true
            PantryStockFilter.IN_STOCK -> amount > 0.0
            PantryStockFilter.OUT_OF_STOCK -> amount <= 0.0
        }
    }

    return when (sortOrder) {
        PantrySortOrder.NAME_ASC -> visible
        PantrySortOrder.NAME_DESC -> visible.asReversed()
        PantrySortOrder.QUANTITY_DESC -> visible.sortedWith(
            compareByDescending<Ingredient> { pantry[it.id] ?: 0.0 }
                .thenBy { it.name.lowercase() }
        )
        PantrySortOrder.CATEGORY -> {
            val categoriesById = categories.associateBy { it.id }
            visible.sortedWith(
                compareBy<Ingredient> {
                    categoriesById[it.shoppingCategoryId]?.position
                        ?: Int.MAX_VALUE
                }.thenBy {
                    categoriesById[it.shoppingCategoryId]?.name?.lowercase()
                        ?: "øvrige"
                }.thenBy { it.name.lowercase() }
            )
        }
    }
}
