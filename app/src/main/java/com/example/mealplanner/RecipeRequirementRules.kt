package com.example.mealplanner

/**
 * A recipe ingredient is uniquely identified by (recipeId, ingredientId) in
 * Supabase. Duplicate taps or an older offline draft must never send the same
 * ingredient twice in one upsert request.
 */
internal object RecipeRequirementRules {
    fun combineIngredients(rows: List<RecipeIngredient>): List<RecipeIngredient> {
        val amounts = linkedMapOf<String, Double>()
        for ((ingredientId, amountBase) in rows) {
            amounts[ingredientId] = (amounts[ingredientId] ?: 0.0) + amountBase
        }
        return amounts.map { (id, amount) -> RecipeIngredient(id, amount) }
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
