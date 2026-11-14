package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Bilka product is measured in grams ("Baguette 280 g") while the ingredient
 * is counted in pieces or packs. One household conversion must bridge them.
 */
class ConversionBridgeTest {
    private fun ingredient(
        type: QuantityType,
        unit: MeasurementUnit,
        rules: List<IngredientUnitConversion> = emptyList()
    ) = Ingredient(
        id = "baguette", name = "Baguette",
        quantityType = type, preferredUnit = unit, unitConversions = rules
    )

    private val baguette280g = BilkaProduct(
        id = "p1", name = "Baguette", brand = "Bilka",
        packageQuantity = 280.0, packageUnit = "g",
        packageBaseAmount = 280.0, quantityKind = BilkaQuantityKind.MASS,
        priceOre = 1500
    )

    // --- phrasing ---------------------------------------------------------

    @Test
    fun countedIngredientsAreEnteredAsOneStkEqualsGrams() {
        assertTrue(ConversionPhrasing.inverted(QuantityType.COUNT, QuantityUnit.GRAM))
        assertTrue(ConversionPhrasing.inverted(QuantityType.COUNT, QuantityUnit.MILLILITRE))
        assertTrue(ConversionPhrasing.inverted(QuantityType.PACKAGE, QuantityUnit.GRAM))
        assertTrue(ConversionPhrasing.inverted(QuantityType.PACKAGE, QuantityUnit.PIECE))
        // "1 pakke = 6 stk" stays the natural way round for a counted ingredient.
        assertFalse(ConversionPhrasing.inverted(QuantityType.COUNT, QuantityUnit.PACK))
        assertFalse(ConversionPhrasing.inverted(QuantityType.MASS, QuantityUnit.PIECE))
        assertFalse(ConversionPhrasing.inverted(QuantityType.VOLUME, QuantityUnit.PACK))
    }

    @Test
    fun typedNumberRoundTripsWithoutFloatingPointDust() {
        val stored = ConversionPhrasing.stored(QuantityType.COUNT, QuantityUnit.GRAM, 280.0)!!
        assertEquals(1.0 / 280.0, stored, 0.0)
        assertEquals(280.0, ConversionPhrasing.shown(QuantityType.COUNT, QuantityUnit.GRAM, stored), 0.0)
        assertEquals(
            "1 stk. = 280 g",
            ConversionPhrasing.describe(
                QuantityType.COUNT, IngredientUnitConversion(QuantityUnit.GRAM, stored)
            )
        )
        assertEquals(
            "1 pakke = 200 g",
            ConversionPhrasing.describe(
                QuantityType.MASS, IngredientUnitConversion(QuantityUnit.PACK, 200.0)
            )
        )
        assertNull(ConversionPhrasing.stored(QuantityType.COUNT, QuantityUnit.GRAM, 0.0))
        assertNull(ConversionPhrasing.stored(QuantityType.COUNT, QuantityUnit.GRAM, Double.NaN))
    }

    // --- the bridge ---------------------------------------------------------

    @Test
    fun aSavedConversionTurnsA280gProductIntoOnePiece() {
        val rule = IngredientUnitConversion(
            QuantityUnit.GRAM, ConversionPhrasing.stored(QuantityType.COUNT, QuantityUnit.GRAM, 280.0)!!
        )
        val piece = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE, listOf(rule))

        val packBase = BilkaPackageMath.applicablePackBase(piece, baguette280g)
        assertEquals(1.0, packBase!!, 1e-9)
        // The recipes need 1 stk and the pantry is empty: buy exactly 1 pack.
        assertEquals(1, BilkaPackageMath.packageCount(1.0, 0.0, packBase))
        assertEquals(1500L, BilkaPackageMath.estimatedCostOre(baguette280g.priceOre, 1))
    }

    @Test
    fun theSameConversionWorksForAnIngredientCountedInPackages() {
        val rule = IngredientUnitConversion(
            QuantityUnit.GRAM, ConversionPhrasing.stored(QuantityType.PACKAGE, QuantityUnit.GRAM, 280.0)!!
        )
        val pack = ingredient(QuantityType.PACKAGE, MeasurementUnit.PACK, listOf(rule))
        assertEquals(1.0, BilkaPackageMath.applicablePackBase(pack, baguette280g)!!, 1e-9)
        // A 560 g product is two of them.
        val big = baguette280g.copy(packageBaseAmount = 560.0)
        assertEquals(2.0, BilkaPackageMath.applicablePackBase(pack, big)!!, 1e-9)
    }

    @Test
    fun withoutAConversionNothingIsGuessed() {
        val piece = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE)
        assertNull(BilkaPackageMath.applicablePackBase(piece, baguette280g))
    }

    @Test
    fun anExplicitlyConfirmedSizeWinsEvenWhenTheUnitKindDiffers() {
        val piece = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE)
        assertEquals(2.0, BilkaPackageMath.applicablePackBase(piece, baguette280g, 2.0)!!, 0.0)
        // ... including a product whose size the server could not parse at all.
        val unknown = baguette280g.copy(quantityKind = null, packageBaseAmount = null)
        assertEquals(1.0, BilkaPackageMath.applicablePackBase(piece, unknown, 1.0)!!, 0.0)
    }

    // --- the shopping line and the estimate -----------------------------------

    @Test
    fun theBaguetteLineIsPricedOnceTheSizeIsConfirmed() {
        val catalog = BilkaProductCatalog(
            candidates = emptyList(),
            defaults = listOf(BilkaListDefault("baguette", "p1")),
            products = listOf(baguette280g)
        )
        val context = BilkaShoppingContext(
            defaults = listOf(BilkaShoppingDefault("baguette", "p1")),
            purchases = emptyList()
        )
        val before = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE)
        val line = ShoppingItem(before, requiredBase = 1.0, pantryBase = 0.0)

        // Before: stuck, but the app knows the fix is available.
        assertNotNull(bilkaProductNeedingSize(line, context, catalog))
        assertEquals(0, estimateShoppingPrice(listOf(line), context, catalog).pricedLines)

        val rule = IngredientUnitConversion(
            QuantityUnit.GRAM, ConversionPhrasing.stored(QuantityType.COUNT, QuantityUnit.GRAM, 280.0)!!
        )
        val after = line.copy(ingredient = before.copy(unitConversions = listOf(rule)))
        assertNull(bilkaProductNeedingSize(after, context, catalog))
        val estimate = estimateShoppingPrice(listOf(after), context, catalog)
        assertEquals(1500L, estimate.totalOre)
        assertEquals(1, estimate.pricedLines)
        assertEquals(0, estimate.unpricedLines)
    }

    @Test
    fun aProductWithNoReadableSizeIsNotOfferedForConfirmation() {
        val unknown = baguette280g.copy(quantityKind = null, packageBaseAmount = null)
        val catalog = BilkaProductCatalog(emptyList(), listOf(BilkaListDefault("baguette", "p1")), listOf(unknown))
        val context = BilkaShoppingContext(listOf(BilkaShoppingDefault("baguette", "p1")), emptyList())
        val line = ShoppingItem(ingredient(QuantityType.COUNT, MeasurementUnit.PIECE), 1.0, 0.0)
        assertNull(bilkaProductNeedingSize(line, context, catalog))
    }
}
