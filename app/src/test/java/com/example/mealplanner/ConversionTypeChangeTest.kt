package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionTypeChangeTest {
    private val drafts = listOf(
        IngredientUnitConversion(QuantityUnit.PACK, 200.0),
        IngredientUnitConversion(QuantityUnit.MILLILITRE, 1.0),
        IngredientUnitConversion(QuantityUnit.LITRE, 1000.0),
        IngredientUnitConversion(QuantityUnit.GARLIC_CLOVE, 5.0)
    )

    @Test
    fun switchingToVolumeKeepsConversionsThatStillBridgeTheType() {
        val kept = drafts.filter { it.unit.canBridge(QuantityType.VOLUME) }

        // ml and l are the ingredient's own dimension now, nothing to bridge.
        assertEquals(
            listOf(QuantityUnit.PACK, QuantityUnit.GARLIC_CLOVE),
            kept.map { it.unit }
        )
        assertEquals(200.0, kept.first().baseAmountPerUnit, 0.0)
    }

    @Test
    fun theTypeAnIngredientAlreadyHasKeepsEveryConversion() {
        // The editor skips the filter entirely when the same chip is tapped;
        // this is the rule it would otherwise apply to a weighed ingredient.
        val kept = drafts.filter { it.unit.canBridge(QuantityType.MASS) }

        assertEquals(drafts, kept)
    }

    @Test
    fun ownUnitsNeverBridgeTheirOwnType() {
        assertFalse(QuantityUnit.GRAM.canBridge(QuantityType.MASS))
        assertFalse(QuantityUnit.MILLILITRE.canBridge(QuantityType.VOLUME))
        assertFalse(QuantityUnit.PIECE.canBridge(QuantityType.COUNT))
        assertFalse(QuantityUnit.PACK.canBridge(QuantityType.PACKAGE))
        assertTrue(QuantityUnit.GRAM.canBridge(QuantityType.COUNT))
        assertTrue(QuantityUnit.GARLIC_HEAD.canBridge(QuantityType.MASS))
    }
}
