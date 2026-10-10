package com.example.mealplanner

/**
 * Preview inventory changes before completing a dinner. An unrecorded dinner
 * may consume zero stock (e.g. when ingredients were bought for that meal).
 * Reject invalid/oversized deductions instead of silently clamping them.
 */
internal object MealCompletionRules {
    fun remainingAfterMeal(
        recipe: Recipe,
        pantry: Map<String, Double>,
        deductions: Map<String, Double>
    ): Map<String, Double>? {
        val ingredientIds = recipe.ingredients.map { it.ingredientId }.toSet()
        val remaining = linkedMapOf<String, Double>()
        for ((id, amount) in deductions) {
            val available = pantry[id] ?: 0.0
            if (id !in ingredientIds || !amount.isFinite() || amount < 0.0 ||
                !available.isFinite() || available < 0.0 || amount > available
            ) return null
            if (amount > 0.0) remaining[id] = available - amount
        }
        return remaining
    }

    fun mayFinish(
        date: java.time.LocalDate,
        today: java.time.LocalDate,
        history: List<MealHistoryEntry>,
    ): Boolean = !date.isAfter(today) &&
        !date.isBefore(today.minusDays(89)) &&
        history.none { it.eatenOn == date }
}
