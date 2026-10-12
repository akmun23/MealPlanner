package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityPlanningRulesTest {
    private fun ingredient(id: String, type: QuantityType) = Ingredient(
        id = id,
        name = id,
        quantityType = type,
        preferredUnit = when (type) {
            QuantityType.MASS -> MeasurementUnit.GRAM
            QuantityType.VOLUME -> MeasurementUnit.MILLILITRE
            QuantityType.COUNT -> MeasurementUnit.PIECE
            QuantityType.PACKAGE -> MeasurementUnit.PACK
        }
    )

    @Test
    fun switchingUnitDoesNotChangePhysicalRequirement() {
        val pasta = ingredient("pasta", QuantityType.MASS)
        val grams = RecipeQuantityRules.freeze("pasta", 300.0, MeasurementUnit.GRAM)!!
        val units = listOf(IngredientUnitConversion(QuantityUnit.PACK, 500.0))
        val packView = QuantityPlanningRules.changeDisplayUnit(grams, pasta, QuantityUnit.PACK, units)!!
        assertEquals(grams.amountBase, packView.amountBase, 0.000001)
        assertEquals(0.6, packView.displayAmount!!, 0.000001)
        assertEquals("PACK", packView.displayUnitCode)

        val gramsAgain = QuantityPlanningRules.changeDisplayUnit(
            packView, pasta, QuantityUnit.GRAM, units
        )!!
        assertEquals(300.0, gramsAgain.amountBase, 0.000001)
        assertEquals(300.0, gramsAgain.displayAmount!!, 0.000001)
        assertEquals("300 g", RecipeQuantityRules.amountAndUnit(gramsAgain, pasta))
    }

    @Test
    fun noCrossUnitChoicesWithoutApprovedRule() {
        val paprika = ingredient("paprika", QuantityType.MASS)
        val units = QuantityPlanningRules.selectableUnits(paprika, emptyList())
        assertTrue(QuantityUnit.GRAM in units)
        assertTrue(QuantityUnit.KILOGRAM in units)
        assertFalse(QuantityUnit.TABLESPOON in units)
        assertFalse(QuantityUnit.PACK in units)

        val grams = RecipeQuantityRules.freeze("paprika", 12.0, MeasurementUnit.GRAM)!!
        assertNull(QuantityPlanningRules.changeDisplayUnit(
            grams, paprika, QuantityUnit.TABLESPOON, emptyList()
        ))
    }

    @Test
    fun convertingEstimatedPaprikaToGramsKeepsUncertainty() {
        val paprika = ingredient("paprika", QuantityType.MASS)
        val rule = listOf(IngredientUnitConversion(
            QuantityUnit.TABLESPOON, 6.0, approximate = true
        ))
        val spoons = RecipeQuantityRules.freeze(paprika, 2.0, QuantityUnit.TABLESPOON, rule)!!
        val grams = QuantityPlanningRules.changeDisplayUnit(
            spoons, paprika, QuantityUnit.GRAM, rule
        )!!
        assertEquals(12.0, grams.amountBase, 0.000001)
        assertEquals(12.0, grams.displayAmount!!, 0.000001)
        assertTrue(grams.conversionApproximate)
        assertTrue(RecipeQuantityRules.hasValidSnapshot(grams))
    }

    @Test
    fun newerSmallPacksOnlyChangePurchaseCountNotOldRecipe() {
        val ham = ingredient("ham", QuantityType.MASS)
        val historical = RecipeQuantityRules.freeze(
            ham, 1.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0))
        )!!
        val current = listOf(IngredientUnitConversion(QuantityUnit.PACK, 150.0))
        val plan = QuantityPlanningRules.planPackages(
            ham, historical.amountBase, 0.0, QuantityUnit.PACK, current
        )!!
        assertEquals(200.0, historical.amountBase, 0.000001)
        assertEquals("1 pakke", RecipeQuantityRules.amountAndUnit(historical, ham))
        assertEquals(2, plan.packageCount)
        assertEquals(300.0, plan.purchasedBase, 0.000001)
        assertEquals(100.0, plan.surplusBase, 0.000001)

        val withStock = QuantityPlanningRules.planPackages(
            ham, historical.amountBase, 50.0, QuantityUnit.PACK, current
        )!!
        assertEquals(1, withStock.packageCount)
        assertEquals(0.0, withStock.surplusBase, 0.000001)
    }

    @Test
    fun unknownPacksAndOldPackageDimensionAreNeverGuessed() {
        val ham = ingredient("ham", QuantityType.MASS)
        val oldPackageIngredient = ingredient("old-pack", QuantityType.PACKAGE)
        assertNull(QuantityPlanningRules.planPackages(
            ham, 200.0, 0.0, QuantityUnit.PACK, emptyList()
        ))
        assertNull(QuantityPlanningRules.planPackages(
            oldPackageIngredient, 2.0, 0.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0))
        ))
        assertNull(QuantityPlanningRules.planPackages(
            ham, Double.NaN, 0.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 200.0))
        ))
    }

    @Test
    fun alreadyCoveredNeedBuysZeroPacks() {
        val pasta = ingredient("pasta", QuantityType.MASS)
        val plan = QuantityPlanningRules.planPackages(
            pasta, 300.0, 400.0, QuantityUnit.PACK,
            listOf(IngredientUnitConversion(QuantityUnit.PACK, 500.0))
        )!!
        assertEquals(0, plan.packageCount)
        assertEquals(0.0, plan.purchasedBase, 0.000001)
        assertEquals(0.0, plan.surplusBase, 0.000001)
    }
    @Test
    fun conflictingOfflineRulesMustFailClosed() {
        val ham = ingredient("ham", QuantityType.MASS)
        val duplicatePacks = listOf(
            IngredientUnitConversion(QuantityUnit.PACK, 200.0),
            IngredientUnitConversion(QuantityUnit.PACK, 150.0)
        )
        assertNull(QuantityConversionEngine.toBase(
            ham, 1.0, QuantityUnit.PACK, duplicatePacks
        ))
        assertNull(QuantityPlanningRules.planPackages(
            ham, 200.0, 0.0, QuantityUnit.PACK, duplicatePacks
        ))
        assertNull(QuantityPlanningRules.changeDisplayUnit(
            RecipeQuantityRules.freeze("ham", 200.0, MeasurementUnit.GRAM)!!,
            ham, QuantityUnit.PACK, duplicatePacks
        ))
    }

    @Test
    fun floatingPointAtPackageBoundaryDoesNotBuyPhantomFourthPack() {
        val ingredient = ingredient("spice", QuantityType.MASS)
        val rules = listOf(IngredientUnitConversion(QuantityUnit.PACK, 0.1))

        // 0.1 + 0.2 is not exactly 0.3 in binary floating point.
        val exactThreePacks = QuantityPlanningRules.planPackages(
            ingredient, 0.1 + 0.2, 0.0, QuantityUnit.PACK, rules
        )!!
        assertEquals(3, exactThreePacks.packageCount)

        // A genuine shortfall is still rounded UP, never silently ignored.
        val actuallyOverThree = QuantityPlanningRules.planPackages(
            ingredient, 0.30000001, 0.0, QuantityUnit.PACK, rules
        )!!
        assertEquals(4, actuallyOverThree.packageCount)
    }

    @Test
    fun litrePreferredDisplayDoesNotChangeMillilitreCanonicalBase() {
        val juice = ingredient("juice", QuantityType.VOLUME)
        assertEquals(MeasurementUnit.MILLILITRE,
            juice.quantityType.baseMeasurementUnit())
        val confirmedCan = listOf(
            IngredientUnitConversion(QuantityUnit.CAN, 330.0)
        )
        val recipe = RecipeQuantityRules.freeze(
            juice, 2.0, QuantityUnit.CAN, confirmedCan
        )!!
        assertEquals(660.0, recipe.amountBase, 0.000001)
        val plan = QuantityPlanningRules.planPackages(
            juice, 660.0, 0.0, QuantityUnit.CAN, confirmedCan
        )!!
        assertEquals(2, plan.packageCount)
    }

}
