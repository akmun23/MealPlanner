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
