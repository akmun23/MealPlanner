package com.example.mealplanner

/**
 * A bought/omitted choice is safe to carry forward only if neither the
 * required quantity nor the available stock has changed.
 */
internal object ShoppingRetentionRules {
    fun unchangedIngredientIds(
        before: List<ShoppingItem>,
        after: List<ShoppingItem>
    ): Set<String> {
        val afterById = after.associateBy { it.ingredient.id }
        return before.asSequence()
            .filter { old ->
                val current = afterById[old.ingredient.id]
                current != null &&
                    old.requiredBase == current.requiredBase &&
                    old.pantryBase == current.pantryBase
            }
            .map { it.ingredient.id }
            .toSet()
    }
}
