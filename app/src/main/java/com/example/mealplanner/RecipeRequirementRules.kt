package com.example.mealplanner

/**
 * A recipe ingredient is uniquely identified by (recipeId, ingredientId) in
 * Supabase. Duplicate taps or an older offline draft must never send the same
 * ingredient twice in one upsert request.
 */
internal object RecipeRequirementRules {
    fun combineIngredients(rows: List<RecipeIngredient>): List<RecipeIngredient> {
        val amounts = linkedMapOf<String, Double>()
        for (row in rows) {
            amounts[row.ingredientId] = (amounts[row.ingredientId] ?: 0.0) + row.amountBase
        }
        return amounts.map { (id, amount) -> RecipeIngredient(id, amount) }
    }

    fun combinePrerequisites(rows: List<RecipePantryPrerequisite>): List<RecipePantryPrerequisite> {
        val minimums = linkedMapOf<String, Double>()
        for (row in rows) {
            minimums[row.ingredientId] =
                maxOf(minimums[row.ingredientId] ?: 0.0, row.minimumAmountBase)
        }
        return minimums.map { (id, amount) -> RecipePantryPrerequisite(id, amount) }
    }
}
