package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSnapshotCodecTest {

    @Test
    fun fullSnapshotRoundTrips() {
        val snapshot = CloudSnapshot(
            ingredients = listOf(
                Ingredient(
                    id = "ingredient",
                    name = "Kyllingebryst",
                    quantityType = QuantityType.MASS,
                    preferredUnit = MeasurementUnit.GRAM,
                    shoppingCategoryId = "meat",
                    isProtein = true,
                    imageUri = "https://example.invalid/signed",
                    imagePath = "ingredients/image.jpg"
                )
            ),
            ingredientCategories = listOf(
                IngredientCategory(
                    id = "meat",
                    name = "Kød og fisk",
                    position = 2
                )
            ),
            recipeCategories = listOf(
                RecipeCategoryOption(
                    id = "dinner",
                    name = "Aftensmad",
                    position = 1
                )
            ),
            recipes = listOf(
                Recipe(
                    id = "recipe",
                    name = "Kylling",
                    mealType = MealType.DINNER,
                    autoRotationEnabled = true,
                    prepMinutes = 30,
                    categoryId = "dinner",
                    notes = "Test",
                    imageUri = "https://example.invalid/recipe",
                    imagePath = "recipes/image.jpg",
                    ingredients = listOf(
                        RecipeIngredient(
                            ingredientId = "ingredient",
                            amountBase = 400.0,
                            displayAmount = 2.0,
                            displayUnitCode = "PACK",
                            basePerDisplayUnit = 200.0,
                            conversionApproximate = true
                        )
                    ),
                    pantryPrerequisites = listOf(
                        RecipePantryPrerequisite(
                            ingredientId = "ingredient",
                            minimumAmountBase = 200.0
                        )
                    )
                )
            ),
            pantry = mapOf("ingredient" to 250.0),
            dayModes = orderedWeekDays.associateWith {
                if (it == DayOfWeek.WEDNESDAY) {
                    DayMode.AWAY
                } else {
                    DayMode.AUTO
                }
            },
            dayMaxPrepMinutes = orderedWeekDays.associateWith {
                if (it == DayOfWeek.MONDAY) 30 else null
            },
            plan = orderedWeekDays.associateWith {
                if (it == DayOfWeek.MONDAY) "recipe" else null
            },
            lockedDays = setOf(DayOfWeek.MONDAY),
            shoppingChecked = mapOf("ingredient" to true),
            shoppingIncluded = mapOf("ingredient" to true),
            selectedLunchRecipeIds = listOf("lunch"),
            weeklyPlanId = "week",
            weeklySandwichRecipeId = "recipe",
            sandwichDay = DayOfWeek.FRIDAY,
            history = listOf(
                MealHistoryEntry(
                    recipeId = "recipe",
                    eatenOn = LocalDate.now().minusDays(2)
                )
            )
        )

        val decoded = RoomSnapshotCodec.decode(
            RoomSnapshotCodec.encode(snapshot)
        )

        assertNotNull(decoded)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun nextWeekRetainsInventoryWithoutReusingPriorWeekPlan() {
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        val original = CloudSnapshot.EMPTY.copy(
            ingredients = listOf(
                Ingredient(
                    id = "rice",
                    name = "Ris",
                    quantityType = QuantityType.MASS,
                    preferredUnit = MeasurementUnit.GRAM
                )
            ),
            pantry = mapOf("rice" to 700.0),
            recipes = listOf(
                Recipe(id = "risret", name = "Risret", ingredients = emptyList())
            ),
            plan = orderedWeekDays.associateWith {
                if (it == DayOfWeek.MONDAY) "risret" else null
            },
            shoppingChecked = mapOf("rice" to true),
            weeklyPlanId = "last-week-uuid",
            history = listOf(MealHistoryEntry("risret", monday))
        )

        val nextWeek = RoomSnapshotCodec.decode(
            RoomSnapshotCodec.encode(original),
            today = monday.plusWeeks(1)
        )!!

        assertEquals(original.ingredients, nextWeek.ingredients)
        assertEquals(original.recipes, nextWeek.recipes)
        assertEquals(original.pantry, nextWeek.pantry)
        assertEquals(original.history, nextWeek.history)
        assertEquals("offline-week-${monday.plusWeeks(1)}", nextWeek.weeklyPlanId)
        assertEquals(null, nextWeek.plan[DayOfWeek.MONDAY])
        assertEquals(emptyMap<String, Boolean>(), nextWeek.shoppingChecked)
    }

    @Test
    fun malformedPayloadIsIgnored() {
        assertNull(RoomSnapshotCodec.decode("{not-json"))
    }
}
