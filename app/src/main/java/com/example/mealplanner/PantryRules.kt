package com.example.mealplanner

object PantryRules {

    private fun requiredByIngredient(recipe: Recipe): Map<String, Double> =
        recipe.ingredients
            .groupBy { it.ingredientId }
            .mapValues { (_, requirements) ->
                requirements.sumOf { it.amountBase }
            }

    fun canMake(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Boolean {
        if (recipe.ingredients.isEmpty()) return false

        return requiredByIngredient(recipe).all { (ingredientId, requiredAmount) ->
            val available = pantry[ingredientId] ?: 0.0
            available + 0.00001 >= requiredAmount
        }
    }

    fun coverage(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Double {
        val requirements = requiredByIngredient(recipe)
        if (requirements.isEmpty()) return 0.0

        val total = requirements.entries.sumOf { (ingredientId, requiredAmount) ->
            if (requiredAmount <= 0.0) {
                1.0
            } else {
                val available = pantry[ingredientId] ?: 0.0
                (available / requiredAmount).coerceIn(0.0, 1.0)
            }
        }

        return total / requirements.size.toDouble()
    }


    // Each distinct ingredient contributes at most one unit to planner scoring.
    fun coveredIngredientUnits(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Double =
        requiredByIngredient(recipe).entries.sumOf { (ingredientId, requiredAmount) ->
            if (requiredAmount <= 0.0) {
                0.0
            } else {
                val available = pantry[ingredientId] ?: 0.0
                (available / requiredAmount).coerceIn(0.0, 1.0)
            }
        }

    fun remainingAfterCooking(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Map<String, Double> =
        requiredByIngredient(recipe)
            .filterValues { it > 0.0 }
            .mapValues { (ingredientId, requiredAmount) ->
                ((pantry[ingredientId] ?: 0.0) - requiredAmount).coerceAtLeast(0.0)
            }
}
