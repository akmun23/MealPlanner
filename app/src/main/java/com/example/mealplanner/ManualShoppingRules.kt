package com.example.mealplanner

/**
 * A household's explicit shopping_inclusions contain both recipe exclusions
 * and ingredients selected outside any recipe. Only explicit true rows may
 * introduce a new list item; never show the same ingredient twice.
 */
internal object ManualShoppingRules {
    fun additionalItems(
        planned: List<ShoppingItem>,
        ingredients: List<Ingredient>,
        pantry: Map<String, Double>,
        inclusion: Map<String, Boolean>
    ): List<ShoppingItem> {
        val plannedIds = planned.mapTo(HashSet<String>()) { it.ingredient.id }
        val ingredientsById = ingredients.associateBy { it.id }
        return inclusion.asSequence()
            .filter { (id, selected) -> selected && id !in plannedIds }
            .mapNotNull { (id, _) ->
                ingredientsById[id]?.let { ingredient ->
                    ShoppingItem(
                        ingredient = ingredient,
                        requiredBase = 0.0,
                        pantryBase = pantry[id] ?: 0.0,
                        manualOnly = true
                    )
                }
            }
            .toList()
    }
}
