package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate

data class DayToggleResult(
    val modes: Map<DayOfWeek, DayMode>,
    val sandwichDay: DayOfWeek?
)

object WeekRules {

    /** A meal is completed on its actual date, independent of the day card's recipe. */
    fun completedOn(date: LocalDate, history: List<MealHistoryEntry>): Boolean =
        history.any { it.eatenOn == date }

    fun completedDaysForWeek(
        weekStart: LocalDate,
        history: List<MealHistoryEntry>
    ): Set<DayOfWeek> = history.asSequence()
        .filter { !it.eatenOn.isBefore(weekStart) && it.eatenOn.isBefore(weekStart.plusWeeks(1)) }
        .map { it.eatenOn.dayOfWeek }
        .toSet()

    /** Every week is independently editable until one of its dinners is completed. */
    fun requiresRegenerationUnlock(
        weekStart: LocalDate,
        history: List<MealHistoryEntry>,
        unlockedWeeks: Set<LocalDate>
    ): Boolean = weekStart !in unlockedWeeks && completedDaysForWeek(weekStart, history).isNotEmpty()

    fun completedDayLocked(
        date: LocalDate,
        history: List<MealHistoryEntry>,
        unlockedDates: Set<LocalDate>
    ): Boolean = date !in unlockedDates && completedOn(date, history)

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
