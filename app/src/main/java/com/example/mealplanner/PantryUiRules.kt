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
