package com.example.mealplanner

import java.time.DayOfWeek

data class DayToggleResult(
    val modes: Map<DayOfWeek, DayMode>,
    val sandwichDay: DayOfWeek?
)

object WeekRules {

    /** Only ask for destructive regeneration confirmation when there is a plan to replace. */
    fun needsRegenerationConfirmation(
        currentPlan: Map<DayOfWeek, String?>,
        sandwichRecipeId: String?
    ): Boolean =
        currentPlan.values.any { it != null } || sandwichRecipeId != null


    fun toggleHome(
        day: DayOfWeek,
        modes: Map<DayOfWeek, DayMode>,
        currentSandwichDay: DayOfWeek?,
        hasSandwichRecipe: Boolean
    ): DayToggleResult {
        val updated = modes.toMutableMap()
        val turningAway = updated[day] != DayMode.AWAY
        updated[day] = if (turningAway) DayMode.AWAY else DayMode.AUTO

        val newSandwichDay = when {
            turningAway && currentSandwichDay == day ->
                WeeklyPlanner.preferredSandwichDay(updated)

            !turningAway && currentSandwichDay == null && hasSandwichRecipe ->
                WeeklyPlanner.preferredSandwichDay(updated)

            else -> currentSandwichDay
        }

        return DayToggleResult(
            modes = updated,
            sandwichDay = newSandwichDay
        )
    }

    fun nextSandwichDay(
        current: DayOfWeek?,
        modes: Map<DayOfWeek, DayMode>
    ): DayOfWeek? {
        val homeDays = orderedWeekDays.filter {
            modes[it] != DayMode.AWAY
        }

        if (homeDays.isEmpty()) return null
        if (current == null || current !in homeDays) return homeDays.first()

        val index = homeDays.indexOf(current)
        return homeDays[(index + 1) % homeDays.size]
    }
}
