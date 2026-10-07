package com.example.mealplanner

object PantryRules {

    fun canMake(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Boolean {
        return recipe.ingredients.isNotEmpty() && recipe.ingredients.all { requirement ->
            val available = pantry[requirement.ingredientId] ?: 0.0
            available + 0.00001 >= requirement.amountBase
        }
    }

    fun coverage(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Double {
        if (recipe.ingredients.isEmpty()) return 0.0

        val total = recipe.ingredients.sumOf { requirement ->
            if (requirement.amountBase <= 0.0) {
                1.0
            } else {
                val available = pantry[requirement.ingredientId] ?: 0.0
                minOf(available / requirement.amountBase, 1.0)
            }
        }

        return total / recipe.ingredients.size.toDouble()
    }
}
