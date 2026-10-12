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
    fun confirmingAndRemovingMealShareAKeyAndSyncAsHistory() {
        val eaten = QueuedMutation.SetMealEaten(
            eatenOn = "2026-10-09",
            recipeId = "recipe"
        )
        val cleared = eaten.copy(recipeId = null)

        assertEquals(eaten, QueuedMutationCodec.decode(QueuedMutationCodec.encode(eaten)))
        assertEquals(cleared, QueuedMutationCodec.decode(QueuedMutationCodec.encode(cleared)))
        assertEquals(eaten.key(), cleared.key())
        assertEquals("meal-eaten:2026-10-09", eaten.key())
        assertEquals(setOf(CloudSyncDomain.HISTORY), eaten.domains())
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
    @Test
    fun householdConversionChangesAreDurableAndDoNotOverwriteMetadata() {
        val profile = IngredientUnitConversion(
            QuantityUnit.PACK, 150.0, approximate = false
        )
        val mutation = QueuedMutation.SaveIngredientConversions(
            ingredientId = "ham",
            conversions = listOf(profile),
            expectedRevision = 4L,
            transactionId = "fixed-conversion-event"
        )
        assertEquals(
            mutation,
            QueuedMutationCodec.decode(QueuedMutationCodec.encode(mutation))
        )
        assertEquals("ingredient-conversion:fixed-conversion-event", mutation.key())
        assertEquals(setOf(CloudSyncDomain.INGREDIENTS), mutation.domains())
        val parent = QueuedMutation.UpsertIngredient(Ingredient(
            id = "ham", name = "Skinke", quantityType = QuantityType.MASS,
            preferredUnit = MeasurementUnit.GRAM
        ))
        assertTrue(parent.priority() < mutation.priority())
        assertTrue(mutation.priority() <
            QueuedMutation.UpsertRecipe(Recipe(id = "recipe", name = "Ret")).priority())
    }

    @Test
    fun lotAndConsumptionMutationsSurviveOfflineRestartWithSameIdempotencyKey() {
        val lot = PantryLot(
            id = "package-1", ingredientId = "pasta",
            originalBase = 500.0, remainingBase = 300.0, opened = true,
            version = 2L
        )
        val edit = QueuedMutation.UpsertPantryLot(
            lot = lot, expectedVersion = 1L, transactionId = "fixed-lot-event"
        )
        val consume = QueuedMutation.ConsumePantryLots(
            ingredientId = "pasta", amountBase = 200.0,
            transactionId = "fixed-meal-event"
        )
        assertEquals(edit, QueuedMutationCodec.decode(QueuedMutationCodec.encode(edit)))
        assertEquals(consume, QueuedMutationCodec.decode(QueuedMutationCodec.encode(consume)))
        assertEquals("pantry-lot:fixed-lot-event", edit.key())
        assertEquals("pantry-consumption:fixed-meal-event:pasta", consume.key())
        assertEquals(setOf(CloudSyncDomain.PANTRY), edit.domains())
        assertEquals(setOf(CloudSyncDomain.PANTRY), consume.domains())
    }

}
