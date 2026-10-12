package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilkaModelsTest {

    @Test
    fun productRoundTripPreservesBrandPackPriceAndImage() {
        val product = BilkaProduct(
            id = "bilka-hvedemel-2kg",
            name = "Hvedemel",
            brand = "Eksempelbrand",
            packageQuantity = 2.0,
            packageUnit = "kg",
            packageBaseAmount = 2000.0,
            quantityKind = BilkaQuantityKind.MASS,
            priceOre = 1895,
            unitPriceOre = 947.5,
            unitPriceBasis = BilkaUnitPriceBasis.KG,
            imageUrl = "https://example.com/hvedemel.jpg",
            isOffer = false
        )

        val serialized = Json.encodeToString(BilkaProduct.serializer(), product)
        val parsed = Json.decodeFromString(BilkaProduct.serializer(), serialized)

        assertTrue(serialized.contains("\"package_base_amount\""))
        assertTrue(serialized.contains("\"price_ore\""))
        assertEquals(product, parsed)
        assertEquals("bilka", parsed.chainSlug)
        assertEquals("Eksempelbrand", parsed.brandLabel)
    }

    @Test
    fun missingBrandAndImageAreSupported() {
        val product = BilkaProduct(id = "product-1", name = "Kartofler")
        val parsed = Json.decodeFromString(
            BilkaProduct.serializer(),
            Json.encodeToString(BilkaProduct.serializer(), product)
        )

        assertEquals("Ukendt mærke", parsed.brandLabel)
        assertNull(parsed.imageUrl)
        assertNull(parsed.priceOre)
        assertNull(parsed.packageBaseAmount)
    }

    @Test
    fun aShoppingOverrideDoesNotReplaceTheSavedHouseholdDefault() {
        val favorite = IngredientBilkaDefault(
            householdId = "household-1",
            ingredientId = "flour-1",
            productId = "preferred-brand-2kg"
        )
        val boughtInstead = ShoppingProductOverride(
            householdId = "household-1",
            weeklyPlanId = "week-1",
            ingredientId = "flour-1",
            productId = "other-brand-1kg",
            packageCount = 2,
            purchasedAmountBase = 2000.0,
            paidTotalOre = 2100
        )

        assertEquals("preferred-brand-2kg", productIdForShopping(favorite, null))
        assertEquals("other-brand-1kg", productIdForShopping(favorite, boughtInstead))
        assertEquals("preferred-brand-2kg", favorite.productId)
        assertEquals("preferred-brand-2kg", productIdForShopping(favorite, null))
    }

    @Test
    fun manualAlternativeDoesNotSilentlyFallBackToStandardProduct() {
        val favorite = IngredientBilkaDefault(
            householdId = "household-1",
            ingredientId = "flour-1",
            productId = "preferred-brand-2kg"
        )
        val manuallyBought = ShoppingProductOverride(
            householdId = "household-1",
            weeklyPlanId = "week-1",
            ingredientId = "flour-1",
            manualProductName = "Andet hvedemel"
        )

        assertNull(productIdForShopping(favorite, manuallyBought))
        assertEquals("preferred-brand-2kg", favorite.productId)
    }

    @Test
    fun queueStatusesAndPackagingCorrectionSerialize() {
        val job = BilkaDiscoveryJob(
            id = "job-1",
            householdId = "household-1",
            ingredientId = "flour-1",
            status = BilkaDiscoveryStatus.NO_MATCH,
            attempts = 2
        )
        val favorite = IngredientBilkaDefault(
            householdId = "household-1",
            ingredientId = "flour-1",
            productId = "product-1",
            packageBaseAmountOverride = 1000.0
        )

        assertEquals(
            job, Json.decodeFromString(
                BilkaDiscoveryJob.serializer(),
                Json.encodeToString(BilkaDiscoveryJob.serializer(), job)
            )
        )
        assertEquals(
            favorite, Json.decodeFromString(
                IngredientBilkaDefault.serializer(),
                Json.encodeToString(IngredientBilkaDefault.serializer(), favorite)
            )
        )
    }
}
