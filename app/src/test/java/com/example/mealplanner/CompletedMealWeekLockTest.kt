package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedMealWeekLockTest {
    private val monday = LocalDate.of(2026, 10, 5)
    private val sunday = monday.plusDays(6)
    private val nextMonday = monday.plusWeeks(1)

    @Test
    fun sundayCompletionLocksOnlyItsOwnWeek() {
        val history = listOf(MealHistoryEntry("sunday-recipe", sunday))

        assertTrue(WeekRules.completedOn(sunday, history))
        assertEquals(setOf(DayOfWeek.SUNDAY), WeekRules.completedDaysForWeek(monday, history))
        assertTrue(WeekRules.completedDaysForWeek(nextMonday, history).isEmpty())
        assertTrue(WeekRules.requiresRegenerationUnlock(monday, history, emptySet()))
        assertFalse(WeekRules.requiresRegenerationUnlock(nextMonday, history, emptySet()))
    }

    @Test
    fun unlockingAWeekLeavesCompletedDaysLocked() {
        val history = listOf(MealHistoryEntry("recipe", monday.plusDays(2)))
        assertFalse(WeekRules.requiresRegenerationUnlock(monday, history, setOf(monday)))
        assertTrue(WeekRules.completedDayLocked(monday.plusDays(2), history, emptySet()))
        assertFalse(WeekRules.completedDayLocked(monday.plusDays(2), history, setOf(monday.plusDays(2))))
        assertFalse(WeekRules.completedDayLocked(monday.plusDays(3), history, emptySet()))
    }

    @Test
    fun weekBoundariesExcludeOtherWeeksAndIgnoreHistoryRecipeIds() {
        val history = listOf(
            MealHistoryEntry("same-recipe", monday.minusDays(1)),
            MealHistoryEntry("same-recipe", monday),
            MealHistoryEntry("same-recipe", nextMonday)
        )
        assertEquals(setOf(DayOfWeek.MONDAY), WeekRules.completedDaysForWeek(monday, history))
        assertEquals(setOf(DayOfWeek.MONDAY), WeekRules.completedDaysForWeek(nextMonday, history))
        assertFalse(WeekRules.completedOn(monday.plusDays(5), history))
    }

    @Test
    fun aFreshWeekWithoutRegistrationsRemainsEditable() {
        val history = emptyList<MealHistoryEntry>()
        assertFalse(WeekRules.requiresRegenerationUnlock(monday, history, emptySet()))
        assertTrue(WeekRules.completedDaysForWeek(monday, history).isEmpty())
    }
    @Test
    fun regeneratedWeekPreservesRecordedDinner() {
        val completed = Recipe(id = "completed", name = "Completed", ingredients = emptyList())
        val candidate = Recipe(id = "candidate", name = "Candidate", ingredients = emptyList())
        val history = listOf(MealHistoryEntry(completed.id, monday))
        val result = WeeklyPlanner.generate(
            recipes = listOf(completed, candidate),
            pantry = emptyMap(),
            dayModes = orderedWeekDays.associateWith { DayMode.AUTO },
            weeklySandwichRecipeId = null,
            currentPlan = mapOf(DayOfWeek.MONDAY to completed.id),
            currentSandwichDay = null,
            lockedDays = WeekRules.completedDaysForWeek(monday, history),
            history = history,
            weekStart = monday,
            random = kotlin.random.Random(9)
        )
        assertEquals(completed.id, result.plan[DayOfWeek.MONDAY])
    }

}
