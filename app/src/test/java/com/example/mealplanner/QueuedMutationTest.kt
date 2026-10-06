package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedMutationTest {

    @Test
    fun recipeMutationRoundTripsWithPrerequisites() {
        val recipe = Recipe(
            id = "recipe",
            name = "Pulled pork",
            categoryId = "slow-food",
            notes = "Test",
            ingredients = listOf(
                RecipeIngredient(
                    ingredientId = "bun",
                    amountBase = 2.0
                )
            ),
            pantryPrerequisites = listOf(
                RecipePantryPrerequisite(
                    ingredientId = "pork",
                    minimumAmountBase = 500.0
                )
            )
        )
        val original = QueuedMutation.UpsertRecipe(recipe)

        val decoded = QueuedMutationCodec.decode(
            QueuedMutationCodec.encode(original)
        )

        assertEquals(original, decoded)
    }

    @Test
    fun latestLifecycleMutationCoalescesByEntityKey() {
        val ingredient = Ingredient(
            id = "ingredient",
            name = "Tomat",
            quantityType = QuantityType.COUNT,
            preferredUnit = MeasurementUnit.PIECE
        )

        assertEquals(
            QueuedMutation.UpsertIngredient(ingredient).key(),
            QueuedMutation.DeleteIngredient(
                ingredientId = ingredient.id,
                imagePath = null
            ).key()
        )
    }

    @Test
    fun dependencyPrioritiesPutReferencesAfterTheirParents() {
        val category = IngredientCategory(
            id = "category",
            name = "Grønt"
        )
        val ingredient = Ingredient(
            id = "ingredient",
            name = "Tomat",
            quantityType = QuantityType.COUNT,
            preferredUnit = MeasurementUnit.PIECE,
            shoppingCategoryId = category.id
        )
        val recipe = Recipe(
            id = "recipe",
            name = "Salat",
            ingredients = listOf(
                RecipeIngredient(
                    ingredientId = ingredient.id,
                    amountBase = 1.0
                )
            )
        )
        val week = QueuedMutation.SetWeekState(
            weeklyPlanId = "week",
            plan = emptyMap(),
            dayModes = emptyMap(),
            lockedDays = emptyList(),
            sandwichRecipeId = null,
            sandwichDay = null
        )
        val shopping = QueuedMutation.SetShoppingChecked(
            weeklyPlanId = "week",
            ingredientId = ingredient.id,
            checked = true
        )

        assertTrue(
            QueuedMutation.UpsertIngredientCategory(category).priority() <
                QueuedMutation.UpsertIngredient(ingredient).priority()
        )
        assertTrue(
            QueuedMutation.UpsertIngredient(ingredient).priority() <
                QueuedMutation.UpsertRecipe(recipe).priority()
        )
        assertTrue(
            QueuedMutation.UpsertRecipe(recipe).priority() <
                week.priority()
        )
        assertTrue(week.priority() < shopping.priority())
    }

    @Test
    fun ingredientDeleteProtectsDependentRealtimeDomains() {
        val domains = QueuedMutation.DeleteIngredient(
            ingredientId = "ingredient",
            imagePath = null
        ).domains()

        assertEquals(
            setOf(
                CloudSyncDomain.INGREDIENTS,
                CloudSyncDomain.PANTRY,
                CloudSyncDomain.SHOPPING
            ),
            domains
        )
    }

    @Test
    fun householdSandwichPreferenceIsSeparateFromWeeklyState() {
        val weekly = QueuedMutation.SetWeekState(
            weeklyPlanId = "week",
            plan = emptyMap(),
            dayModes = emptyMap(),
            lockedDays = emptyList(),
            sandwichRecipeId = null,
            sandwichDay = null
        )
        val household =
            QueuedMutation.SetHouseholdSandwich("recipe")

        assertTrue(weekly.key() != household.key())
    }
}
