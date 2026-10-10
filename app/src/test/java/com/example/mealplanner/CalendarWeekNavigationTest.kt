package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarWeekNavigationTest {
    @Test
    fun aFutureWeekSnapshotIsNotMistakenForCurrentWeekWhenOffline() {
        val thisWeek = LocalDate.of(2026, 10, 5)
        val nextWeek = thisWeek.plusWeeks(1)
        val future = CloudSnapshot.EMPTY.copy(
            viewWeekStart = nextWeek,
            weeklyPlanId = "future-plan",
            plan = orderedWeekDays.associateWith { "future-meal" },
            shoppingChecked = mapOf("flour" to true),
            lockedDays = setOf(DayOfWeek.MONDAY)
        )

        val current = RoomSnapshotCodec.decode(
            RoomSnapshotCodec.encode(future),
            today = thisWeek.plusDays(2)
        )!!

        assertEquals(thisWeek, current.viewWeekStart)
        assertEquals("offline-week-$thisWeek", current.weeklyPlanId)
        assertNull(current.plan[DayOfWeek.MONDAY])
        assertEquals(emptySet<DayOfWeek>(), current.lockedDays)
        assertEquals(emptyMap<String, Boolean>(), current.shoppingChecked)
    }

    @Test
    fun selectedWeekSurvivesCacheRoundTripWithinSameWeek() {
        val selected = LocalDate.of(2026, 12, 28)
        val snapshot = CloudSnapshot.EMPTY.copy(
            viewWeekStart = selected,
            weeklyPlanId = "new-year-plan",
            plan = mapOf(DayOfWeek.TUESDAY to "recipe"),
            lockedDays = setOf(DayOfWeek.TUESDAY),
            shoppingIncluded = mapOf("salt" to false)
        )
        val restored = RoomSnapshotCodec.decode(
            RoomSnapshotCodec.encode(snapshot),
            today = selected.plusDays(1)
        )!!
        assertEquals(snapshot, restored)
    }

    @Test
    fun isoWeekYearCanDifferFromCalendarYear() {
        val monday = LocalDate.of(2025, 12, 29)
        assertEquals(1, monday.get(WeekFields.ISO.weekOfWeekBasedYear()))
        assertEquals(2026, monday.get(WeekFields.ISO.weekBasedYear()))
    }
}
