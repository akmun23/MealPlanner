package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryLotRulesTest {
    @Test
    fun openedPackageIsConsumedBeforeSealedPackage() {
        val opened = PantryLot(id = "open", ingredientId = "pasta",
            originalBase = 500.0, remainingBase = 150.0, opened = true)
        val sealed = PantryLot(id = "sealed", ingredientId = "pasta",
            originalBase = 500.0, remainingBase = 500.0)
        val other = PantryLot(id = "other", ingredientId = "sugar",
            originalBase = 1000.0, remainingBase = 1000.0)
        val result = PantryLotRules.consume(
            listOf(sealed, opened, other), "pasta", 300.0
        )!!
        assertEquals(350.0, PantryLotRules.total(result, "pasta"), 0.001)
        assertEquals(0.0, result.first { it.id == "open" }.remainingBase, 0.001)
        assertEquals(350.0, result.first { it.id == "sealed" }.remainingBase, 0.001)
        assertTrue(result.first { it.id == "sealed" }.opened)
        assertEquals(other, result.first { it.id == "other" })
    }

    @Test
    fun earlierExpiryWinsBetweenTwoSealedPackages() {
        val later = PantryLot(id = "later", ingredientId = "ham",
            originalBase = 100.0, remainingBase = 100.0, bestBefore = "2026-12-01")
        val earlier = PantryLot(id = "earlier", ingredientId = "ham",
            originalBase = 100.0, remainingBase = 100.0, bestBefore = "2026-10-25")
        val result = PantryLotRules.consume(listOf(later, earlier), "ham", 50.0)!!
        assertEquals(50.0, result.first { it.id == "earlier" }.remainingBase, 0.001)
        assertEquals(100.0, result.first { it.id == "later" }.remainingBase, 0.001)
    }

    @Test
    fun neverSpendMoreThanOnHand() {
        val pack = PantryLot(id = "one", ingredientId = "pasta",
            originalBase = 150.0, remainingBase = 150.0)
        assertNull(PantryLotRules.consume(listOf(pack), "pasta", 200.0))
        assertNull(PantryLotRules.consume(listOf(pack), "pasta", Double.NaN))
        assertNull(PantryLotRules.consume(listOf(pack), "pasta", -1.0))
        assertEquals(150.0, pack.remainingBase, 0.001)
    }

    @Test
    fun legacyUnknownInventoryIsNotCountedTwice() {
        val legacy = PantryLot(id = "legacy", ingredientId = "pasta",
            originalBase = 300.0, remainingBase = 300.0, legacyUnknown = true, opened = true)
        val concrete = PantryLot(id = "bag", ingredientId = "pasta",
            originalBase = 500.0, remainingBase = 500.0)
        assertEquals(800.0, PantryLotRules.total(listOf(legacy, concrete), "pasta"), 0.001)
        val result = PantryLotRules.consume(listOf(legacy, concrete), "pasta", 400.0)!!
        assertEquals(400.0, PantryLotRules.total(result, "pasta"), 0.001)
    }
}
