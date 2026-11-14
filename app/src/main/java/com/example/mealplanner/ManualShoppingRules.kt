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
        inclusion: Map<String, Boolean>,
        extras: Map<String, Double> = emptyMap()
    ): List<ShoppingItem> {
        val plannedIds = planned.mapTo(HashSet<String>()) { it.ingredient.id }
        val ingredientsById = ingredients.associateBy { it.id }
        // An extra keeps its ingredient on the list even when the recipes that
        // needed it are gone, unless the household explicitly excluded it.
        val ids = LinkedHashSet<String>().apply {
            inclusion.forEach { (id, selected) -> if (selected) add(id) }
            extras.forEach { (id, amount) ->
                if (amount > 0.0 && inclusion[id] != false) add(id)
            }
        }
        return ids.asSequence()
            .filter { it !in plannedIds }
            .mapNotNull { id ->
                ingredientsById[id]?.let { ingredient ->
                    val extra = extras[id]?.takeIf { it > 0.0 } ?: 0.0
                    ShoppingItem(
                        ingredient = ingredient,
                        requiredBase = extra,
                        pantryBase = pantry[id] ?: 0.0,
                        manualOnly = true,
                        extraBase = extra
                    )
                }
            }
            .toList()
    }
}
