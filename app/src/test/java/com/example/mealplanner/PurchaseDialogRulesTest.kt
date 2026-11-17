package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseDialogRulesTest {
    private fun ingredient(
        type: QuantityType = QuantityType.MASS,
        unit: MeasurementUnit = MeasurementUnit.GRAM
    ) = Ingredient(name = "Testvare", quantityType = type, preferredUnit = unit)

    private fun product(id: String, kind: BilkaQuantityKind? = BilkaQuantityKind.MASS) =
        BilkaProduct(id = id, name = id, quantityKind = kind)

    @Test
    fun staleSelectedProductFallsBackToARealCompatibleProduct() {
        val products = listOf(product("valid"))
        assertEquals(
            "valid",
            PurchaseDialogRules.selectedProductId(products, "removed", "missing")
        )
        assertNull(PurchaseDialogRules.selectedProductId(emptyList(), "removed", "missing"))
    }

    @Test
    fun savedDefaultIsUsedWhenItStillExists() {
        assertEquals(
            "default",
            PurchaseDialogRules.selectedProductId(
                listOf(product("candidate"), product("default")),
                previousProductId = "deleted",
                defaultProductId = "default"
            )
        )
    }

    @Test
    fun mismatchedQuantityKindsAreNotSilentlySelected() {
        val products = listOf(
            product("wrong", BilkaQuantityKind.VOLUME),
            product("ok", BilkaQuantityKind.MASS),
            product("unknown", null)
        )
        val filtered = PurchaseDialogRules.compatibleProducts(ingredient(), products)
        assertEquals(listOf("ok", "unknown"), filtered.map { it.id })
        assertEquals(
            listOf("count"),
            PurchaseDialogRules.compatibleProducts(
                ingredient(QuantityType.COUNT, MeasurementUnit.PIECE),
                listOf(product("count", BilkaQuantityKind.COUNT),
                    product("mass", BilkaQuantityKind.MASS))
            ).map { it.id }
        )
    }

    @Test
    fun aProductInAnotherUnitKindStaysSelectableThroughAHouseholdConversion() {
        val baguette = BilkaProduct(
            id = "baguette", name = "Baguette",
            quantityKind = BilkaQuantityKind.MASS, packageBaseAmount = 280.0
        )
        val counted = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE)
        // "1 stk = 280 g" is stored as base (pieces) per gram.
        val bridged = counted.copy(
            unitConversions = listOf(IngredientUnitConversion(QuantityUnit.GRAM, 1.0 / 280.0))
        )

        assertEquals(
            listOf("baguette"),
            PurchaseDialogRules.compatibleProducts(bridged, listOf(baguette)).map { it.id }
        )
        assertTrue(PurchaseDialogRules.compatibleProducts(counted, listOf(baguette)).isEmpty())
    }

    @Test
    fun aConfirmedPackSizeMakesADifferentKindSelectable() {
        val baguette = BilkaProduct(
            id = "baguette", name = "Baguette",
            quantityKind = BilkaQuantityKind.MASS, packageBaseAmount = 280.0
        )
        val counted = ingredient(QuantityType.COUNT, MeasurementUnit.PIECE)

        val kept = PurchaseDialogRules.compatibleProducts(counted, listOf(baguette)) { 1.0 }

        assertEquals(listOf("baguette"), kept.map { it.id })
    }

    @Test
    fun zeroMissingAmountPrefillsPositiveQuantityInUsersOwnUnit() {
        assertEquals(
            400.0,
            PurchaseDialogRules.suggestedPurchasedBase(400.0, 400.0, MeasurementUnit.GRAM),
            0.00001
        )
        assertEquals(
            1000.0,
            PurchaseDialogRules.suggestedPurchasedBase(0.0, 0.0, MeasurementUnit.KILOGRAM),
            0.00001
        )
        assertEquals(
            1.0,
            PurchaseDialogRules.suggestedPurchasedBase(0.0, 0.0, MeasurementUnit.PIECE),
            0.00001
        )
        assertEquals(
            50.0,
            PurchaseDialogRules.suggestedPurchasedBase(100.0, 50.0, MeasurementUnit.GRAM),
            0.00001
        )
    }

    @Test
    fun amountIsValidatedAfterConversionToBaseUnits() {
        assertEquals(
            1500.0,
            PurchaseDialogRules.validPurchasedAmount("1,5", MeasurementUnit.KILOGRAM)!!,
            0.00001
        )
        assertNull(PurchaseDialogRules.validPurchasedAmount("0", MeasurementUnit.GRAM))
        assertNull(PurchaseDialogRules.validPurchasedAmount("-1", MeasurementUnit.GRAM))
        assertNull(PurchaseDialogRules.validPurchasedAmount("20.000.000", MeasurementUnit.GRAM))
        assertNull(PurchaseDialogRules.validPurchasedAmount("12,3,4", MeasurementUnit.GRAM))
        assertNull(PurchaseDialogRules.validPurchasedAmount("Infinity", MeasurementUnit.GRAM))
        assertTrue(PurchaseDialogRules.validPurchasedAmount("0,001", MeasurementUnit.GRAM)!! > 0.0)
    }
}
