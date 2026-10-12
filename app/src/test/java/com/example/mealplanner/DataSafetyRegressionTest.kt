package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSafetyRegressionTest {
    private val gram = Ingredient(
        id = "flour",
        name = "Mel",
        quantityType = QuantityType.MASS,
        preferredUnit = MeasurementUnit.GRAM
    )

    private fun recipe(
        requirements: List<RecipeIngredient> = emptyList(),
        prerequisites: List<RecipePantryPrerequisite> = emptyList()
    ) = Recipe(
        name = "Rugbrød",
        ingredients = requirements,
        pantryPrerequisites = prerequisites
    )

    @Test
    fun subGramUnitsRoundTripWithoutBecomingZero() {
        val converted = MeasurementUnit.GRAM.convertAmountTo(
            1.0, MeasurementUnit.KILOGRAM
        )!!
        val display = formatDisplayNumber(converted)
        assertEquals("0,001", display)
        assertEquals(1.0, MeasurementUnit.KILOGRAM.toBase(parseDanishDecimal(display)!!), 1e-12)
        val small = formatDisplayNumber(0.00000001)
        assertEquals(0.00000001, parseDanishDecimal(small)!!, 1e-17)
        assertEquals("1250", formatDisplayNumber(1250.0))
    }

    @Test
    fun ingredientTypeChangeRequiresNoStockOrRecipeReferences() {
        val switched = QuantityType.VOLUME
        assertTrue(
            canChangeIngredientQuantityType(
                gram, QuantityType.MASS, 42.0,
                listOf(recipe(requirements = listOf(RecipeIngredient("flour", 20.0))))
            )
        )
        assertTrue(canChangeIngredientQuantityType(gram, switched, 0.0, emptyList()))
        assertFalse(canChangeIngredientQuantityType(gram, switched, 12.0, emptyList()))
        assertFalse(
            canChangeIngredientQuantityType(
                gram, switched, 0.0,
                listOf(recipe(requirements = listOf(RecipeIngredient("flour", 20.0))))
            )
        )
        assertFalse(
            canChangeIngredientQuantityType(
                gram, switched, 0.0,
                listOf(recipe(prerequisites = listOf(RecipePantryPrerequisite("flour", 20.0))))
            )
        )
        assertTrue(
            canChangeIngredientQuantityType(
                gram, switched, 0.0,
                listOf(recipe(requirements = listOf(RecipeIngredient("other", 20.0))))
            )
        )
    }

    @Test
    fun shoppingChoicesSurviveOnlyUnchangedRequirementsAndStock() {
        val eggs = Ingredient(
            id = "eggs",
            name = "Æg",
            quantityType = QuantityType.COUNT,
            preferredUnit = MeasurementUnit.PIECE
        )
        val before = listOf(
            ShoppingItem(gram, 500.0, 25.0),
            ShoppingItem(eggs, 8.0, 0.0)
        )
        val changed = listOf(
            ShoppingItem(gram, 500.0, 25.0),
            ShoppingItem(eggs, 9.0, 0.0)
        )
        assertEquals(
            setOf("flour"),
            ShoppingRetentionRules.unchangedIngredientIds(before, changed)
        )
        assertEquals(
            emptySet<String>(),
            ShoppingRetentionRules.unchangedIngredientIds(
                before, listOf(ShoppingItem(gram, 500.0, 30.0))
            )
        )
        assertEquals(
            setOf("flour", "eggs"),
            ShoppingRetentionRules.unchangedIngredientIds(before, before)
        )
    }

    @Test
    fun plannerPresetsAreDistinctAndResettable() {
        assertEquals(PlannerWeights(), PlannerPreset.STANDARD.weights())
        assertTrue(
            PlannerPreset.VARIETY.weights().duplicateRecipe >
                    PlannerPreset.STANDARD.weights().duplicateRecipe
        )
        assertTrue(
            PlannerPreset.PANTRY.weights().pantryCoverage >
                    PlannerPreset.STANDARD.weights().pantryCoverage
        )
    }
}
