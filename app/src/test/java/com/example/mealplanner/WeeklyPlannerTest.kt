package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPlannerTest {

    private fun recipe(id: String) = Recipe(
        id = id,
        name = id,
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
    fun pantryPrerequisiteExcludesRecipeFromAutomaticGenerationWhenMissing() {
        val blocked = Recipe(
            id = "blocked",
            name = "Blocked",
            ingredients = emptyList(),
            pantryPrerequisites = listOf(
                RecipePantryPrerequisite(
                    ingredientId = "pulled-pork",
                    minimumAmountBase = 1.0
                )
            )
        )
        val available = Recipe(
            id = "available",
            name = "Available",
            ingredients = emptyList()
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }

        repeat(10) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(blocked, available),
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                random = Random(seed)
            )

            assertEquals(available.id, result.plan[DayOfWeek.MONDAY])
        }
    }

    @Test
    fun pantryPrerequisiteAllowsRecipeWhenMinimumStockIsPresent() {
        val recipe = Recipe(
            id = "freezer",
            name = "Freezer meal",
            ingredients = emptyList(),
            pantryPrerequisites = listOf(
                RecipePantryPrerequisite(
                    ingredientId = "pulled-pork",
                    minimumAmountBase = 2.0
                )
            )
        )

        assertTrue(
            WeeklyPlanner.hasRequiredPantryStock(
                recipe = recipe,
                pantry = mapOf("pulled-pork" to 2.0)
            )
        )
    }

    @Test
    fun pantryPrerequisiteIsExcludedFromDayRerollWhenMissing() {
        val current = Recipe(
            id = "current",
            name = "Current",
            ingredients = emptyList()
        )
        val blocked = Recipe(
            id = "blocked",
            name = "Blocked",
            ingredients = emptyList(),
            pantryPrerequisites = listOf(
                RecipePantryPrerequisite(
                    ingredientId = "special",
                    minimumAmountBase = 1.0
                )
            )
        )
        val available = Recipe(
            id = "available",
            name = "Available",
            ingredients = emptyList()
        )
        val plan = orderedWeekDays.associateWith { null as String? }
            .toMutableMap()
            .apply { this[DayOfWeek.MONDAY] = current.id }

        val result = WeeklyPlanner.rerollDay(
            day = DayOfWeek.MONDAY,
            recipes = listOf(current, blocked, available),
            pantry = emptyMap(),
            dayModes = allHome(),
            weeklySandwichRecipeId = null,
            sandwichDay = null,
            currentPlan = plan,
            random = Random(5)
        )

        assertEquals(available.id, result[DayOfWeek.MONDAY])
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
    fun proteinVarietyUsesTaggedIngredientIds() {
        val chickenA = Recipe(
            id = "chicken-a",
            name = "Chicken A",
            categoryId = "category-a",
            ingredients = listOf(
                RecipeIngredient("chicken", 100.0)
            )
        )
        val chickenB = Recipe(
            id = "chicken-b",
            name = "Chicken B",
            categoryId = "category-b",
            ingredients = listOf(
                RecipeIngredient("chicken", 100.0)
            )
        )
        val beef = Recipe(
            id = "beef",
            name = "Beef",
            categoryId = "category-c",
            ingredients = listOf(
                RecipeIngredient("beef", 100.0)
            )
        )
        val modes = orderedWeekDays.associateWith { day ->
            if (
                day == DayOfWeek.MONDAY ||
                day == DayOfWeek.TUESDAY
            ) {
                DayMode.AUTO
            } else {
                DayMode.AWAY
            }
        }
        val weights = PlannerWeights(
            pantryCoverage = 0.0,
            ingredientReuse = 0.0,
            duplicateRecipe = 1000.0,
            repeatedProtein = 1000.0,
            adjacentProtein = 1000.0,
            repeatedCategory = 0.0,
            adjacentCategory = 0.0,
            recency7Days = 0.0,
            recency14Days = 0.0,
            recency30Days = 0.0,
            keepSameDay = 0.0,
            jitter = 0.0
        )

        val result = WeeklyPlanner.generate(
            recipes = listOf(chickenA, chickenB, beef),
            pantry = emptyMap(),
            dayModes = modes,
            weeklySandwichRecipeId = null,
            currentPlan = orderedWeekDays.associateWith { null },
            currentSandwichDay = null,
            proteinIngredientIds = setOf("chicken", "beef"),
            weights = weights,
            random = Random(10)
        )

        val selected = listOfNotNull(
            result.plan[DayOfWeek.MONDAY],
            result.plan[DayOfWeek.TUESDAY]
        ).toSet()

        assertTrue(
            "planner should avoid two chicken recipes when beef is available",
            !(selected.contains(chickenA.id) &&
                selected.contains(chickenB.id))
        )
    }

    @Test
    fun generationExploresRecipesBeyondTheHighestScoredFour() {
        val dinners = (0 until 18).map { recipe("dinner-$it") }
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }
        val chosen = (0 until 35).map { seed ->
            WeeklyPlanner.generate(
                recipes = dinners,
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                random = Random(seed)
            ).plan[DayOfWeek.MONDAY]
        }.toSet()

        assertTrue(
            "the generator should explore more than four equally eligible recipes",
            chosen.size >= 9
        )
    }

    @Test
    fun rerollExploresAllEligibleAlternatives() {
        val dinners = (0 until 18).map { recipe("dinner-$it") }
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }
        val current = orderedWeekDays.associateWith { day ->
            if (day == DayOfWeek.MONDAY) dinners.first().id else null
        }
        val chosen = (0 until 35).map { seed ->
            WeeklyPlanner.rerollDay(
                day = DayOfWeek.MONDAY,
                recipes = dinners,
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                sandwichDay = null,
                currentPlan = current,
                random = Random(seed)
            )[DayOfWeek.MONDAY]
        }.toSet()

        assertTrue(
            "a reroll should not be limited to the four highest-ranked meals",
            chosen.size >= 9
        )
        assertTrue(dinners.first().id !in chosen)
    }

    @Test
    fun regenerationPrefersRecipesOutsideTheExistingPlan() {
        val dinners = (0 until 16).map { recipe("dinner-$it") }
        val currentIds = dinners.take(5).map { it.id }.toSet()
        val current = orderedWeekDays.associateWith { day ->
            if (day.value <= 5) dinners[day.value - 1].id else null
        }
        val modes = orderedWeekDays.associateWith { day ->
            if (day.value <= 5) DayMode.AUTO else DayMode.AWAY
        }

        val result = WeeklyPlanner.generate(
            recipes = dinners,
            pantry = emptyMap(),
            dayModes = modes,
            weeklySandwichRecipeId = null,
            currentPlan = current,
            currentSandwichDay = null,
            weights = PlannerWeights(previousPlanReuse = 120.0),
            random = Random(3)
        )

        assertTrue(
            "regenerating should introduce different recipes when possible",
            result.plan.values.filterNotNull().count { it !in currentIds } >= 3
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

    @Test
    fun futureHistoryEntryDoesNotMaskRecentlyEatenRecipe() {
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }
        val history = buildList {
            add(MealHistoryEntry("recent", LocalDate.of(2026, 10, 4)))
            // A future record must not replace the applicable past meal.
            add(MealHistoryEntry("recent", LocalDate.of(2026, 11, 2)))
            repeat(50) { index ->
                add(MealHistoryEntry("unrelated-$index", LocalDate.of(2026, 9, 1)))
            }
        }
        var freshWins = 0
        repeat(30) { seed ->
            val result = WeeklyPlanner.generate(
                recipes = listOf(recipe("recent"), recipe("fresh")),
                pantry = emptyMap(),
                dayModes = modes,
                weeklySandwichRecipeId = null,
                currentPlan = orderedWeekDays.associateWith { null },
                currentSandwichDay = null,
                history = history,
                weekStart = LocalDate.of(2026, 10, 5),
                random = Random(seed)
            )
            if (result.plan[DayOfWeek.MONDAY] == "fresh") freshWins++
        }
        assertTrue("future history must not suppress past recency", freshWins >= 27)
    }

    @Test
    fun plannerDoesNotDoubleCountRepeatedIngredientPantryCoverage() {
        val repeated = Recipe(
            id = "repeated-rice",
            name = "Two rice requirements",
            ingredients = listOf(
                RecipeIngredient("rice", 100.0),
                RecipeIngredient("rice", 100.0)
            )
        )
        val stocked = Recipe(
            id = "stocked-milk",
            name = "Milk",
            ingredients = listOf(RecipeIngredient("milk", 100.0))
        )
        val modes = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) DayMode.AUTO else DayMode.AWAY
        }
        val weights = PlannerWeights(
            pantryCoverage = 100.0,
            ingredientReuse = 0.0,
            duplicateRecipe = 0.0,
            repeatedProtein = 0.0,
            adjacentProtein = 0.0,
            repeatedCategory = 0.0,
            adjacentCategory = 0.0,
            recency7Days = 0.0,
            recency14Days = 0.0,
            recency30Days = 0.0,
            keepSameDay = 0.0,
            jitter = 0.0
        )

        val result = WeeklyPlanner.generate(
            recipes = listOf(repeated, stocked),
            pantry = mapOf("rice" to 100.0, "milk" to 100.0),
            dayModes = modes,
            weeklySandwichRecipeId = null,
            currentPlan = orderedWeekDays.associateWith { null },
            currentSandwichDay = null,
            weights = weights,
            random = Random(12)
        )

        assertEquals(stocked.id, result.plan[DayOfWeek.MONDAY])
    }

}
