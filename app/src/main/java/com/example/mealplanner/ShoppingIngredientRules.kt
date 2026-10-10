package com.example.mealplanner

/** Purchase exclusion is independent of a recipe's ingredients and pantry prerequisites. */
internal object ShoppingIngredientRules {
    fun isAutomaticallyPurchased(ingredient: Ingredient): Boolean = !ingredient.neverBuy
}
