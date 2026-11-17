package com.example.mealplanner

/**
 * A recipe ingredient is uniquely identified by (recipeId, ingredientId) in
 * Supabase. Duplicate taps or an older offline draft must never send the same
 * ingredient twice in one upsert request.
 */
internal object RecipeRequirementRules {
    fun combineIngredients(rows: List<RecipeIngredient>): List<RecipeIngredient> {
        val groups = linkedMapOf<String, MutableList<RecipeIngredient>>()
        rows.forEach { row ->
            groups.getOrPut(row.ingredientId) { mutableListOf() }.add(row)
        }
        return groups.map { (id, group) ->
            if (group.size == 1) return@map group.single()
            val sumBase = group.sumOf { it.amountBase }
            // Only preserve a human-readable unit when all duplicate rows use
            // the same validated frozen conversion. Otherwise retaining one
            // display label would misrepresent the physical sum.
            val first = group.first()
            val canCombineDisplay = group.all {
                RecipeQuantityRules.hasValidSnapshot(it) &&
                    it.displayUnitCode == first.displayUnitCode &&
                    it.basePerDisplayUnit == first.basePerDisplayUnit
            }
            if (canCombineDisplay) {
                first.copy(
                    amountBase = sumBase,
                    displayAmount = group.sumOf { it.displayAmount!! },
                    // If any part used an estimated conversion, the combined
                    // physical amount must remain marked as an estimate.
                    conversionApproximate = group.any { it.conversionApproximate }
                )
            } else {
                RecipeIngredient(id, sumBase)
            }
        }
    }

    fun combinePrerequisites(rows: List<RecipePantryPrerequisite>): List<RecipePantryPrerequisite> {
        val minimums = linkedMapOf<String, Double>()
        for ((ingredientId, minimumAmountBase) in rows) {
            minimums[ingredientId] =
                maxOf(minimums[ingredientId] ?: 0.0, minimumAmountBase)
        }
        return minimums.map { (id, amount) -> RecipePantryPrerequisite(id, amount) }
    }
}
