package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilkaPackageMathTest {
    private val ingredient = Ingredient(
        id = "milk", name = "Mælk",
        quantityType = QuantityType.VOLUME,
        preferredUnit = MeasurementUnit.LITRE
    )
    private val oneLitre = BilkaProduct(
        id = "10", name = "Letmælk 1 liter",
        brand = "Arla",
        packageQuantity = 1.0,
        packageUnit = "l",
        packageBaseAmount = 1000.0,
        quantityKind = BilkaQuantityKind.VOLUME,
        priceOre = 1350
    )

    @Test
    fun buysTwoWholeOneLitrePacksFor1200MlShortfall() {
        val packSize = BilkaPackageMath.applicablePackBase(ingredient, oneLitre)
        assertEquals(1000.0, packSize!!, 0.001)
        assertEquals(2, BilkaPackageMath.packageCount(1500.0, 300.0, packSize))
        assertEquals(2000.0, BilkaPackageMath.purchasedBase(packSize, 2)!!, 0.001)
        assertEquals(2700L, BilkaPackageMath.estimatedCostOre(oneLitre.priceOre, 2))
    }

    @Test
    fun noPackagesNeededWhenPantryCoversRequirement() {
        assertEquals(0, BilkaPackageMath.packageCount(1000.0, 1100.0, 1000.0))
    }

    @Test
    fun oneOffAlternatePackDoesNotChangeSavedDefault() {
        val other = oneLitre.copy(
            id = "11", packageBaseAmount = 1500.0, packageQuantity = 1.5
        )
        assertEquals(1, BilkaPackageMath.packageCount(1300.0, 0.0, other.packageBaseAmount))
        assertEquals("10", oneLitre.id)
        assertEquals(1500.0, BilkaPackageMath.purchasedBase(1500.0, 1)!!, 0.01)
    }

    @Test
    fun mismatchedUnitsDoNotConvertPackagingSilently() {
        val flour = oneLitre.copy(quantityKind = BilkaQuantityKind.MASS)
        assertNull(BilkaPackageMath.applicablePackBase(ingredient, flour))
        assertNull(BilkaPackageMath.packageCount(500.0, 0.0, null))
    }

    @Test
    fun manualCorrectionAndInvalidSizes() {
        assertEquals(750.0, BilkaPackageMath.applicablePackBase(ingredient, oneLitre, 750.0)!!, 0.01)
        assertNull(BilkaPackageMath.purchasedBase(0.0, 2))
        assertNull(BilkaPackageMath.purchasedBase(1000.0, 0))
        assertNull(BilkaPackageMath.packageCount(1200.0, 0.0, -1.0))
    }

    @Test
    fun priceOverflowDoesNotWrapNegative() {
        assertNull(BilkaPackageMath.estimatedCostOre(Long.MAX_VALUE, 2))
    }
}
