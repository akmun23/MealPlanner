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
    val query = searchQuery.trim()
    val categoriesById = if (query.isNotEmpty() || sortOrder == PantrySortOrder.CATEGORY) {
        categories.associateBy { it.id }
    } else {
        emptyMap()
    }
    val visible = ArrayList<Ingredient>(ingredients.size)

    // Filter in one pass. The former path sorted alphabetically before applying
    // the stock filter, then sorted again for quantity/category views.
    for (ingredient in ingredients) {
        if (selectedCategoryId != null &&
            ingredient.shoppingCategoryId != selectedCategoryId
        ) continue

        val amount = pantry[ingredient.id] ?: 0.0
        val matchesStock = when (stockFilter) {
            PantryStockFilter.ALL -> true
            PantryStockFilter.IN_STOCK -> amount > 0.0
            PantryStockFilter.OUT_OF_STOCK -> amount <= 0.0
        }
        if (!matchesStock) continue

        if (query.isNotEmpty()) {
            val categoryName = ingredient.shoppingCategoryId
                ?.let { categoriesById[it]?.name }
                .orEmpty()
            if (!ingredient.name.contains(query, ignoreCase = true) &&
                !categoryName.contains(query, ignoreCase = true)
            ) continue
        }

        visible += ingredient
    }

    when (sortOrder) {
        PantrySortOrder.NAME_ASC -> visible.sortBy { it.name.lowercase() }
        PantrySortOrder.NAME_DESC -> {
            visible.sortBy { it.name.lowercase() }
            visible.reverse()
        }
        PantrySortOrder.QUANTITY_DESC -> visible.sortWith(
            compareByDescending<Ingredient> { pantry[it.id] ?: 0.0 }
                .thenBy { it.name.lowercase() }
        )
        PantrySortOrder.CATEGORY -> visible.sortWith(
            compareBy<Ingredient> {
                categoriesById[it.shoppingCategoryId]?.position ?: Int.MAX_VALUE
            }.thenBy {
                categoriesById[it.shoppingCategoryId]?.name?.lowercase() ?: "øvrige"
            }.thenBy { it.name.lowercase() }
        )
    }

    return visible
}
