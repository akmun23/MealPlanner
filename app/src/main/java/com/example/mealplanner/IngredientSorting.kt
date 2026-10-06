package com.example.mealplanner

internal fun <T> sortByIngredientCategory(
    items: List<T>,
    ingredientId: (T) -> String,
    ingredients: List<Ingredient>,
    categories: List<IngredientCategory>
): List<T> {
    if (items.size < 2) return items

    val ingredientsById = ingredients.associateBy { it.id }
    val categoriesById = categories.associateBy { it.id }

    return items.sortedWith(
        compareBy<T>(
            { item ->
                val ingredient = ingredientsById[ingredientId(item)]
                categoriesById[ingredient?.shoppingCategoryId]
                    ?.position
                    ?: Int.MAX_VALUE
            },
            { item ->
                val ingredient = ingredientsById[ingredientId(item)]
                categoriesById[ingredient?.shoppingCategoryId]
                    ?.name
                    ?.lowercase()
                    ?: "andet"
            },
            { item ->
                ingredientsById[ingredientId(item)]
                    ?.name
                    ?.lowercase()
                    ?: ""
            }
        )
    )
}
