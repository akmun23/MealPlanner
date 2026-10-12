package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityConversionFoundationTest {
    private fun ingredient(
        id: String,
        type: QuantityType,
        unit: MeasurementUnit
    ) = Ingredient(id = id, name = id, quantityType = type, preferredUnit = unit)

    @Test
    fun recipeRemembersOnePackEvenIfStandardPackChanges() {
        val ham = ingredient("skinkestrimler", QuantityType.MASS, MeasurementUnit.GRAM)
        val saved = RecipeQuantityRules.freeze(
            ham, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0))
        )!!
        assertEquals(200.0, saved.amountBase, 0.000001)
        assertEquals("1 pakke", RecipeQuantityRules.amountAndUnit(saved, ham))

        // Changing a present-day buying default to 150 g does not alter
        // either historical display or its physical requirement.
        val newBuyingRule = IngredientUnitConversion(QuantityUnit.PACK, 150.0)
        assertEquals(
            150.0,
            QuantityConversionEngine.toBase(
                ham, 1.0, QuantityUnit.PACK, listOf(newBuyingRule)
            )!!,
            0.000001
        )
        assertEquals(200.0, saved.amountBase, 0.000001)
        assertEquals("1 pakke", RecipeQuantityRules.amountAndUnit(saved, ham))
    }

    @Test
    fun recipeMayIndependentlyUse300GramsInsteadOfOne500GramPack() {
        val pasta = ingredient("pasta", QuantityType.MASS, MeasurementUnit.GRAM)
        val packageRule = IngredientUnitConversion(QuantityUnit.PACK, 500.0)
        val packRecipe = RecipeQuantityRules.freeze(
            pasta, 1.0, QuantityUnit.PACK, listOf(packageRule)
        )!!
        val gramRecipe = RecipeQuantityRules.freeze(
            pasta, 300.0, QuantityUnit.GRAM, emptyList()
        )!!
        assertEquals(500.0, packRecipe.amountBase, 0.000001)
        assertEquals(300.0, gramRecipe.amountBase, 0.000001)
        assertEquals("1 pakke", RecipeQuantityRules.amountAndUnit(packRecipe, pasta))
        assertEquals("300 g", RecipeQuantityRules.amountAndUnit(gramRecipe, pasta))
    }

    @Test
    fun spiceTablespoonToGramsRequiresIngredientSpecificDensity() {
        val paprika = ingredient("paprika", QuantityType.MASS, MeasurementUnit.GRAM)
        assertNull(QuantityConversionEngine.toBase(paprika, 2.0, QuantityUnit.TABLESPOON))
        val factor = IngredientUnitConversion(QuantityUnit.TABLESPOON, 6.0, approximate = true)
        val requirement = RecipeQuantityRules.freeze(
            paprika, 2.0, QuantityUnit.TABLESPOON, listOf(factor)
        )!!
        assertEquals(12.0, requirement.amountBase, 0.000001)
        assertTrue(requirement.conversionApproximate)
        assertEquals("2 spsk. (omregning anslået)", RecipeQuantityRules.amountAndUnit(requirement, paprika))
    }

    @Test
    fun packOfChiliAndHeadOfGarlicMustDeclareActualPieceCount() {
        val chili = ingredient("chili", QuantityType.COUNT, MeasurementUnit.PIECE)
        val garlic = ingredient("hvidløg", QuantityType.COUNT, MeasurementUnit.PIECE)
        assertNull(QuantityConversionEngine.toBase(chili, 1.0, QuantityUnit.PACK))
        assertNull(QuantityConversionEngine.toBase(garlic, 1.0, QuantityUnit.GARLIC_HEAD))
        assertEquals(4.0, QuantityConversionEngine.toBase(
            chili, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 4.0))
        )!!, 0.000001)
        assertEquals(8.0, QuantityConversionEngine.toBase(
            garlic, 1.0, QuantityUnit.GARLIC_HEAD,
            listOf(IngredientUnitConversion(QuantityUnit.GARLIC_HEAD, 8.0, approximate = true))
        )!!, 0.000001)
    }

    @Test
    fun exactConversionsAreOnlyWithinTheirPhysicalDimension() {
        val water = ingredient("vand", QuantityType.VOLUME, MeasurementUnit.LITRE)
        val paprika = ingredient("paprika", QuantityType.MASS, MeasurementUnit.GRAM)
        assertEquals(15.0, QuantityConversionEngine.toBase(water, 1.0, QuantityUnit.TABLESPOON)!!, 0.000001)
        assertEquals(1000.0, QuantityConversionEngine.toBase(
            paprika, 1.0, QuantityUnit.KILOGRAM
        )!!, 0.000001)
        assertNull(QuantityConversionEngine.toBase(paprika, 1.0, QuantityUnit.TABLESPOON))
    }

    @Test
    fun invalidFactorsAndStaleSnapshotsAreNeverTrusted() {
        val ham = ingredient("ham", QuantityType.MASS, MeasurementUnit.GRAM)
        assertNull(QuantityConversionEngine.toBase(
            ham, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, Double.NaN))
        ))
        assertNull(QuantityConversionEngine.toBase(ham, -3.0, QuantityUnit.GRAM))
        val saved = RecipeQuantityRules.freeze(ham, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0)))!!
        assertFalse(RecipeQuantityRules.hasValidSnapshot(saved.copy(amountBase = 100.0)))
        assertEquals("100 g", RecipeQuantityRules.amountAndUnit(saved.copy(amountBase = 100.0), ham))
    }

    @Test
    fun combiningRequirementsPreservesEstimatedConversionRegardlessOfRowOrder() {
        val paprika = ingredient("paprika", QuantityType.MASS, MeasurementUnit.GRAM)
        val measured = RecipeQuantityRules.freeze(
            paprika, 1.0, QuantityUnit.TABLESPOON,
            listOf(IngredientUnitConversion(QuantityUnit.TABLESPOON, 6.0))
        )!!
        val estimated = RecipeQuantityRules.freeze(
            paprika, 1.0, QuantityUnit.TABLESPOON,
            listOf(IngredientUnitConversion(QuantityUnit.TABLESPOON, 6.0, approximate = true))
        )!!

        for (rows in listOf(listOf(measured, estimated), listOf(estimated, measured))) {
            val combined = RecipeRequirementRules.combineIngredients(rows).single()
            assertEquals(12.0, combined.amountBase, 0.000001)
            assertEquals(2.0, combined.displayAmount!!, 0.000001)
            assertEquals("TABLESPOON", combined.displayUnitCode)
            assertTrue("Combining a measured and estimated quantity must remain approximate",
                combined.conversionApproximate)
        }
    }

    @Test
    fun editingRecipeMetadataDoesNotRewriteAnUneditablePackSnapshot() {
        val ham = ingredient("ham", QuantityType.MASS, MeasurementUnit.GRAM)
        val original = RecipeQuantityRules.freeze(
            ham, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0))
        )!!
        // The phase-1 editor must temporarily show 200 g, but a name/notes
        // update must not replace the frozen recipe text "1 pakke".
        val unchangedEditorQuantity =
            RecipeQuantityRules.freeze("ham", 200.0, MeasurementUnit.GRAM)!!
        assertEquals(
            original,
            RecipeQuantityRules.retainUneditableSnapshot(original, unchangedEditorQuantity, ham)
        )

        // Explicit quantity edits continue to update the physical requirement.
        val changedQuantity = RecipeQuantityRules.freeze("ham", 250.0, MeasurementUnit.GRAM)!!
        assertEquals(
            changedQuantity,
            RecipeQuantityRules.retainUneditableSnapshot(original, changedQuantity, ham)
        )

        // Explicitly picking a different display unit must win even when
        // the canonical amount has not changed.
        val userPickedKilograms =
            RecipeQuantityRules.freeze("ham", 0.2, MeasurementUnit.KILOGRAM)!!
        assertEquals(
            userPickedKilograms,
            RecipeQuantityRules.retainUneditableSnapshot(
                original, userPickedKilograms, ham, displayExplicitlyEdited = true
            )
        )

        // Normal same-dimension edits may change the visible unit.
        val legacyNative = RecipeQuantityRules.freeze("ham", 0.2, MeasurementUnit.KILOGRAM)!!
        assertEquals(
            unchangedEditorQuantity,
            RecipeQuantityRules.retainUneditableSnapshot(
                legacyNative, unchangedEditorQuantity, ham
            )
        )

        // Inconsistent snapshots from old-client edits are never resurrected.
        val stale = original.copy(amountBase = 100.0)
        assertEquals(
            unchangedEditorQuantity,
            RecipeQuantityRules.retainUneditableSnapshot(stale, unchangedEditorQuantity, ham)
        )
    }

    @Test
    fun legacyRecipeIngredientSerializationRemainsCompatible() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<RecipeIngredient>(
            """{"ingredientId":"pasta","amountBase":300.0}"""
        )
        assertEquals(300.0, legacy.amountBase, 0.000001)
        assertNull(legacy.displayAmount)
        assertFalse(RecipeQuantityRules.hasValidSnapshot(legacy))
    }

    @Test
    fun combiningDuplicateIngredientsNeverLiesAboutOriginalUnit() {
        val grams = RecipeQuantityRules.freeze("pasta", 300.0, MeasurementUnit.GRAM)!!
        val more = RecipeQuantityRules.freeze("pasta", 50.0, MeasurementUnit.GRAM)!!
        val merged = RecipeRequirementRules.combineIngredients(listOf(grams, more)).single()
        assertEquals(350.0, merged.amountBase, 0.000001)
        assertEquals(350.0, merged.displayAmount!!, 0.000001)
        assertEquals("GRAM", merged.displayUnitCode)
        assertEquals(
            null,
            RecipeRequirementRules.combineIngredients(
                listOf(grams, RecipeQuantityRules.freeze("pasta", 1.0, MeasurementUnit.KILOGRAM)!!)
            ).single().displayUnitCode
        )
        assertEquals(grams, RecipeRequirementRules.combineIngredients(listOf(grams)).single())
    }
}
