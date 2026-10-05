package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPlannerTest {

    private fun recipe(id: String, protein: String = "") = Recipe(
        id = id,
        name = id,
        proteinTag = protein,
        ingredients = emptyList()
    )

    private fun allHome() =
        orderedWeekDays.associateWith { DayMode.AUTO }

    @Test
    fun fridayIsPreferenceNotRequirement() {
        val modes = allHome().toMutableMap().apply {
            this[DayOfWeek.FRIDAY] = DayMode.AWAY
        }

        assertEquals(
            DayOfWeek.THURSDAY,
            WeeklyPlanner.preferredSandwichDay(modes)
        )
    }

    @Test
    fun awayDayKeepsUnderlyingRecipeWhenWeekIsGenerated() {
        val monday = recipe("monday")
        val tuesday = recipe("tuesday")
        val modes = allHome().toMutableMap().apply {
            this[DayOfWeek.TUESDAY] = DayMode.AWAY
        }
        val current = orderedWeekDays.associateWith { null as String? }
            .toMutableMap()
            .apply {
                this[DayOfWeek.TUESDAY] = tuesday.id
            }

        val result = WeeklyPlanner.generate(
            recipes = listOf(monday, tuesday),
            pantry = emptyMap(),
            dayModes = modes,
            weeklySandwichRecipeId = null,
            currentPlan = current,
            currentSandwichDay = null,
            random = Random(7)
        )

        assertEquals(tuesday.id, result.plan[DayOfWeek.TUESDAY])
    }

    @Test
    fun turningSandwichDayAwayMovesSandwichButReturningDoesNotSnapItBack() {
        val start = allHome()

        val away = WeekRules.toggleHome(
            day = DayOfWeek.FRIDAY,
            modes = start,
            currentSandwichDay = DayOfWeek.FRIDAY,
            hasSandwichRecipe = true
        )
        assertEquals(DayOfWeek.THURSDAY, away.sandwichDay)

        val backHome = WeekRules.toggleHome(
            day = DayOfWeek.FRIDAY,
            modes = away.modes,
            currentSandwichDay = away.sandwichDay,
            hasSandwichRecipe = true
        )

        assertEquals(DayOfWeek.THURSDAY, backHome.sandwichDay)
        assertEquals(DayMode.AUTO, backHome.modes[DayOfWeek.FRIDAY])
    }

    @Test
    fun manualSandwichMoveCyclesHomeDaysWithoutFridaySnap() {
        val modes = allHome()

        assertEquals(
            DayOfWeek.WEDNESDAY,
            WeekRules.nextSandwichDay(
                current = DayOfWeek.TUESDAY,
                modes = modes
            )
        )
    }

    @Test
    fun rerollChangesOnlyTargetDay() {
        val a = recipe("a")
        val b = recipe("b")
        val c = recipe("c")
        val current = orderedWeekDays.associateWith { null as String? }
            .toMutableMap()
            .apply {
                this[DayOfWeek.MONDAY] = a.id
                this[DayOfWeek.TUESDAY] = b.id
            }

        val result = WeeklyPlanner.rerollDay(
            day = DayOfWeek.MONDAY,
            recipes = listOf(a, b, c),
            pantry = emptyMap(),
            dayModes = allHome(),
            weeklySandwichRecipeId = null,
            sandwichDay = null,
            currentPlan = current,
            random = Random(3)
        )

        assertNotEquals(a.id, result[DayOfWeek.MONDAY])
        assertEquals(b.id, result[DayOfWeek.TUESDAY])
    }

    @Test
    fun lunchRecipesAreNeverRandomizedIntoDinnerPlan() {
        val dinner = Recipe(
            id = "dinner",
            name = "Dinner",
            mealType = MealType.DINNER,
            ingredients = emptyList()
        )
        val lunch = Recipe(
            id = "lunch",
            name = "Lunch",
            mealType = MealType.LUNCH,
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        repeat(20) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(dinner, lunch),
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                random = Random(seed)
            )

            assertEquals(
                dinner.id,
                result.plan[DayOfWeek.MONDAY]
            )
        }
    }

    @Test
    fun legacyCategoryNamesNormalizeToStructuredCategories() {
        assertEquals(
            RecipeCategory.PASTA,
            "pasta".toRecipeCategory()
        )
        assertEquals(
            RecipeCategory.SANDWICH_WRAP,
            "sandwich".toRecipeCategory()
        )
        assertEquals(
            RecipeCategory.OTHER,
            "ukendt kategori".toRecipeCategory()
        )
    }

    @Test
    fun teaspoonAndTablespoonUseMillilitreBaseUnits() {
        assertEquals(
            5.0,
            MeasurementUnit.TEASPOON.toBase(1.0),
            0.0001
        )
        assertEquals(
            15.0,
            MeasurementUnit.TABLESPOON.toBase(1.0),
            0.0001
        )
        assertEquals(
            3.0,
            MeasurementUnit.TABLESPOON.fromBase(45.0),
            0.0001
        )
    }

    @Test
    fun packageTypeSupportsPackAndCan() {
        val compatible = MeasurementUnit.compatibleWith(
            QuantityType.PACKAGE
        )

        assertTrue(MeasurementUnit.PACK in compatible)
        assertTrue(MeasurementUnit.CAN in compatible)
        assertEquals(
            MeasurementUnit.PACK,
            MeasurementUnit.defaultFor(QuantityType.PACKAGE)
        )
        assertEquals("pakke", MeasurementUnit.PACK.labelFor(1.0))
        assertEquals("pakker", MeasurementUnit.PACK.labelFor(2.0))
    }

    @Test
    fun weekCanGenerateWithoutSandwich() {
        val dinner = Recipe(
            id = "dinner",
            name = "Dinner",
            mealType = MealType.DINNER,
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        val result = WeeklyPlanner.generate(
            recipes = listOf(dinner),
            pantry = emptyMap(),
            dayModes = modes,
            weeklySandwichRecipeId = null,
            currentPlan = orderedWeekDays.associateWith { null },
            currentSandwichDay = null,
            random = Random(1)
        )

        assertEquals(null, result.sandwichDay)
        assertEquals(dinner.id, result.plan[DayOfWeek.MONDAY])
    }

    @Test
    fun manualOnlyDinnerIsExcludedFromAutomaticGeneration() {
        val automatic = Recipe(
            id = "automatic",
            name = "Automatic",
            mealType = MealType.DINNER,
            autoRotationEnabled = true,
            ingredients = emptyList()
        )
        val manualOnly = Recipe(
            id = "manual",
            name = "Manual",
            mealType = MealType.DINNER,
            autoRotationEnabled = false,
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        repeat(20) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(automatic, manualOnly),
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                random = Random(seed)
            )

            assertEquals(
                automatic.id,
                result.plan[DayOfWeek.MONDAY]
            )
        }
    }

    @Test
    fun manualOnlyDinnerIsExcludedFromDayReroll() {
        val current = Recipe(
            id = "current",
            name = "Current",
            ingredients = emptyList()
        )
        val automatic = Recipe(
            id = "automatic",
            name = "Automatic",
            ingredients = emptyList()
        )
        val manualOnly = Recipe(
            id = "manual",
            name = "Manual",
            autoRotationEnabled = false,
            ingredients = emptyList()
        )

        val plan = orderedWeekDays.associateWith { null as String? }
            .toMutableMap()
            .apply {
                this[DayOfWeek.MONDAY] = current.id
            }

        val result = WeeklyPlanner.rerollDay(
            day = DayOfWeek.MONDAY,
            recipes = listOf(current, automatic, manualOnly),
            pantry = emptyMap(),
            dayModes = allHome(),
            weeklySandwichRecipeId = null,
            sandwichDay = null,
            currentPlan = plan,
            random = Random(2)
        )

        assertEquals(
            automatic.id,
            result[DayOfWeek.MONDAY]
        )
    }

    @Test
    fun proteinRepeatPenaltyIsASecondaryPreference() {
        val weights = PlannerWeights()

        assertEquals(3.0, weights.repeatedProtein, 0.0001)
        assertEquals(6.0, weights.adjacentProtein, 0.0001)
        assertTrue(weights.repeatedProtein < weights.repeatedCategory)
        assertTrue(weights.adjacentProtein < weights.adjacentCategory)
    }

    @Test
    fun timeLimitedDayOnlyUsesRecipesWithinLimit() {
        val quick = Recipe(
            id = "quick",
            name = "Quick",
            prepMinutes = 20,
            ingredients = emptyList()
        )
        val slow = Recipe(
            id = "slow",
            name = "Slow",
            prepMinutes = 90,
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        repeat(20) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(quick, slow),
                pantry = emptyMap(),
                dayModes = modes,
                maxPrepMinutesByDay = mapOf(
                    DayOfWeek.MONDAY to 20
                ),
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                random = Random(seed)
            )

            assertEquals(
                quick.id,
                result.plan[DayOfWeek.MONDAY]
            )
        }
    }

    @Test
    fun unknownPrepTimeIsExcludedFromTimeLimitedDay() {
        val known = Recipe(
            id = "known",
            name = "Known",
            prepMinutes = 30,
            ingredients = emptyList()
        )
        val unknown = Recipe(
            id = "unknown",
            name = "Unknown",
            prepMinutes = null,
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        val result = WeeklyPlanner.generate(
            recipes = listOf(known, unknown),
            pantry = emptyMap(),
            dayModes = modes,
            maxPrepMinutesByDay = mapOf(
                DayOfWeek.MONDAY to 30
            ),
            weeklySandwichRecipeId = null,
            currentPlan = orderedWeekDays.associateWith { null },
            currentSandwichDay = null,
            random = Random(4)
        )

        assertEquals(
            known.id,
            result.plan[DayOfWeek.MONDAY]
        )
    }

    @Test
    fun dayRerollRespectsPreparationTimeLimit() {
        val current = Recipe(
            id = "current",
            name = "Current",
            prepMinutes = 20,
            ingredients = emptyList()
        )
        val quick = Recipe(
            id = "quick",
            name = "Quick",
            prepMinutes = 25,
            ingredients = emptyList()
        )
        val slow = Recipe(
            id = "slow",
            name = "Slow",
            prepMinutes = 120,
            ingredients = emptyList()
        )

        val plan = orderedWeekDays.associateWith { null as String? }
            .toMutableMap()
            .apply {
                this[DayOfWeek.MONDAY] = current.id
            }

        val result = WeeklyPlanner.rerollDay(
            day = DayOfWeek.MONDAY,
            recipes = listOf(current, quick, slow),
            pantry = emptyMap(),
            dayModes = allHome(),
            maxPrepMinutesByDay = mapOf(
                DayOfWeek.MONDAY to 30
            ),
            weeklySandwichRecipeId = null,
            sandwichDay = null,
            currentPlan = plan,
            random = Random(5)
        )

        assertEquals(
            quick.id,
            result[DayOfWeek.MONDAY]
        )
    }

    @Test
    fun recentlyEatenRecipeIsStronglyDisfavoured() {
        val recent = recipe("recent")
        val fresh = recipe("fresh")
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        var freshWins = 0
        repeat(30) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(recent, fresh),
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                history = listOf(
                    MealHistoryEntry(
                        recipeId = recent.id,
                        eatenOn = LocalDate.of(2026, 10, 4)
                    )
                ),
                weekStart = LocalDate.of(2026, 10, 5),
                random = Random(seed)
            )

            if (result.plan[DayOfWeek.MONDAY] == fresh.id) freshWins++
        }

        assertTrue("fresh recipe should dominate selection", freshWins >= 27)
    }
}
