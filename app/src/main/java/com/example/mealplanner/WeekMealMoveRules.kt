package com.example.mealplanner

import java.time.DayOfWeek

/**
 * Moving dinner changes its assigned day, not its recipe or ingredients.
 * Swapping assignments keeps the weekly shopping totals unchanged.
 */
internal object WeekMealMoveRules {
    fun allowed(
        source: DayOfWeek,
        destination: DayOfWeek,
        plan: Map<DayOfWeek, String?>,
        dayModes: Map<DayOfWeek, DayMode>,
        lockedDays: Set<DayOfWeek>,
        sandwichDay: DayOfWeek?,
        sandwichRecipeId: String?
    ): Boolean {
        if (source == destination || source in lockedDays || destination in lockedDays) return false
        if (dayModes[source] == DayMode.AWAY || dayModes[destination] == DayMode.AWAY) return false
        val sourceRecipe = if (source == sandwichDay && sandwichRecipeId != null) {
            sandwichRecipeId
        } else plan[source]
        return sourceRecipe != null
    }

    fun swappedPlan(
        source: DayOfWeek,
        destination: DayOfWeek,
        plan: Map<DayOfWeek, String?>
    ): Map<DayOfWeek, String?> = plan.toMutableMap().apply {
        this[source] = plan[destination]
        this[destination] = plan[source]
    }

    fun movedSandwichDay(
        source: DayOfWeek,
        destination: DayOfWeek,
        sandwichDay: DayOfWeek?
    ): DayOfWeek? = when (sandwichDay) {
        source -> destination
        destination -> source
        else -> sandwichDay
    }
}
