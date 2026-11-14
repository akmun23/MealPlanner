package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingExtraTest {
    private val milk = Ingredient(
        id = "milk", name = "Mælk",
        quantityType = QuantityType.VOLUME, preferredUnit = MeasurementUnit.LITRE
    )
    private val coffee = Ingredient(
        id = "coffee", name = "Kaffe",
        quantityType = QuantityType.MASS, preferredUnit = MeasurementUnit.GRAM
    )

    private fun planned(ingredient: Ingredient, required: Double, extra: Double = 0.0) =
        ShoppingItem(ingredient, requiredBase = required + extra, pantryBase = 0.0, extraBase = extra)

    // --- manual rules -----------------------------------------------------

    @Test
    fun anExtraForAnIngredientWithoutRecipesBecomesAManualLineWithThatAmount() {
        val items = ManualShoppingRules.additionalItems(
            planned = emptyList(),
            ingredients = listOf(milk),
            pantry = mapOf("milk" to 250.0),
            inclusion = emptyMap(),
            extras = mapOf("milk" to 1000.0)
        )
        val line = items.single()
        assertTrue(line.manualOnly)
        assertEquals(1000.0, line.requiredBase, 0.0)
        assertEquals(1000.0, line.extraBase, 0.0)
        assertEquals(250.0, line.pantryBase, 0.0)
    }

    @Test
    fun anExtraDoesNotDuplicateAnIngredientThatRecipesAlreadyNeed() {
        val items = ManualShoppingRules.additionalItems(
            planned = listOf(planned(milk, 500.0, extra = 1000.0)),
            ingredients = listOf(milk),
            pantry = emptyMap(),
            inclusion = mapOf("milk" to true),
            extras = mapOf("milk" to 1000.0)
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun anExplicitlyExcludedIngredientStaysOffTheListEvenWithAnExtra() {
        val items = ManualShoppingRules.additionalItems(
            planned = emptyList(),
            ingredients = listOf(milk),
            pantry = emptyMap(),
            inclusion = mapOf("milk" to false),
            extras = mapOf("milk" to 1000.0)
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun manualLinesWithoutAnExtraKeepTheirOldBehaviour() {
        val items = ManualShoppingRules.additionalItems(
            planned = emptyList(),
            ingredients = listOf(coffee),
            pantry = emptyMap(),
            inclusion = mapOf("coffee" to true)
        )
        val line = items.single()
        assertEquals(0.0, line.requiredBase, 0.0)
        assertEquals(0.0, line.extraBase, 0.0)
    }

    // --- offline queue ----------------------------------------------------

    @Test
    fun extraMutationSurvivesTheOfflineQueueRoundTripAndSyncsAsShopping() {
        val mutation = QueuedMutation.SetShoppingExtra("week-1", "milk", 1000.0)
        assertEquals(
            mutation,
            QueuedMutationCodec.decode(QueuedMutationCodec.encode(mutation))
        )
        assertEquals("shopping-extra:week-1:milk", mutation.key())
        assertEquals(setOf(CloudSyncDomain.SHOPPING), mutation.domains())
        // A later edit of the same ingredient replaces the queued one.
        assertEquals(
            mutation.key(),
            QueuedMutation.SetShoppingExtra("week-1", "milk", 0.0).key()
        )
    }

    // --- snapshot cache ---------------------------------------------------

    @Test
    fun cachedSnapshotKeepsExtrasAndOlderCachesWithoutThemStillLoad() {
        val snapshot = CloudSnapshot.EMPTY.copy(
            ingredients = listOf(milk),
            shoppingExtra = mapOf("milk" to 1000.0),
            viewWeekStart = java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        )
        val encoded = RoomSnapshotCodec.encode(snapshot)
        assertEquals(
            mapOf("milk" to 1000.0),
            RoomSnapshotCodec.decode(encoded)!!.shoppingExtra
        )

        val withoutField = encoded.replace(Regex("\"shoppingExtra\":\\{[^}]*\\},?"), "")
        assertFalse(withoutField.contains("shoppingExtra"))
        assertTrue(RoomSnapshotCodec.decode(withoutField)!!.shoppingExtra.isEmpty())
    }

    // --- price estimate ---------------------------------------------------

    private val product = BilkaProduct(
        id = "p-milk", name = "Letmælk", brand = "Arla",
        packageQuantity = 1.0, packageUnit = "l",
        packageBaseAmount = 1000.0, quantityKind = BilkaQuantityKind.VOLUME,
        priceOre = 1295
    )
    private val catalog = BilkaProductCatalog(
        candidates = emptyList(),
        defaults = listOf(BilkaListDefault("milk", "p-milk")),
        products = listOf(product)
    )
    private val context = BilkaShoppingContext(
        defaults = listOf(BilkaShoppingDefault("milk", "p-milk")),
        purchases = emptyList()
    )

    @Test
    fun estimateRoundsExtraPlusRecipeNeedUpToWholePackages() {
        // Recipes need 0.5 l and the household wants 1 l extra: 1.5 l -> 2 packs.
        val estimate = estimateShoppingPrice(
            items = listOf(planned(milk, 500.0, extra = 1000.0)),
            context = context, catalog = catalog
        )
        assertEquals(2 * 1295L, estimate.totalOre)
        assertEquals(1, estimate.pricedLines)
        assertEquals(0, estimate.unpricedLines)
    }

    @Test
    fun withoutTheExtraOnePackageIsEnough() {
        val estimate = estimateShoppingPrice(
            items = listOf(planned(milk, 500.0)), context = context, catalog = catalog
        )
        assertEquals(1295L, estimate.totalOre)
    }

    @Test
    fun linesWithoutAStandardProductAreCountedAsUnpricedNotAsFree() {
        val estimate = estimateShoppingPrice(
            items = listOf(planned(milk, 500.0), planned(coffee, 200.0)),
            context = context, catalog = catalog
        )
        assertEquals(1295L, estimate.totalOre)
        assertEquals(1, estimate.pricedLines)
        assertEquals(1, estimate.unpricedLines)
    }

    @Test
    fun itemsCoveredByThePantryCostNothingAndAreNotCounted() {
        val covered = ShoppingItem(milk, requiredBase = 500.0, pantryBase = 800.0)
        val estimate = estimateShoppingPrice(listOf(covered), context, catalog)
        assertEquals(0L, estimate.totalOre)
        assertEquals(0, estimate.pricedLines)
        assertEquals(0, estimate.unpricedLines)
    }

    @Test
    fun aRecordedPurchaseUsesWhatWasActuallyPaid() {
        val paid = BilkaShoppingContext(
            defaults = context.defaults,
            purchases = listOf(
                BilkaShoppingPurchase(
                    ingredientId = "milk", packageCount = 3,
                    purchasedAmountBase = 3000.0, paidTotalOre = 3000L
                )
            )
        )
        val estimate = estimateShoppingPrice(listOf(planned(milk, 500.0)), paid, catalog)
        assertEquals(3000L, estimate.totalOre)
        assertEquals(1, estimate.pricedLines)
    }

    @Test
    fun noCatalogMeansNothingIsPricedAndNothingBreaks() {
        val estimate = estimateShoppingPrice(listOf(planned(milk, 500.0)), null, null)
        assertEquals(0, estimate.pricedLines)
        assertEquals(1, estimate.unpricedLines)
    }

    @Test
    fun aRecordedPurchaseWithoutAPaidAmountIsPricedFromTheProductAndPackageCount() {
        val bought = BilkaShoppingContext(
            defaults = emptyList(),
            purchases = listOf(
                BilkaShoppingPurchase(
                    ingredientId = "milk", productId = "p-milk", packageCount = 2,
                    purchasedAmountBase = 2000.0, paidTotalOre = null
                )
            )
        )
        val estimate = estimateShoppingPrice(listOf(planned(milk, 500.0)), bought, catalog)
        assertEquals(2 * 1295L, estimate.totalOre)
        assertEquals(1, estimate.pricedLines)
        assertEquals(0, estimate.unpricedLines)
    }

    @Test
    fun aRecordedPurchaseOfAManuallyNamedProductHasNoPrice() {
        val bought = BilkaShoppingContext(
            defaults = emptyList(),
            purchases = listOf(
                BilkaShoppingPurchase(
                    ingredientId = "milk", manualProductName = "Landmælk", packageCount = 1,
                    purchasedAmountBase = 1000.0
                )
            )
        )
        val estimate = estimateShoppingPrice(listOf(planned(milk, 500.0)), bought, catalog)
        assertEquals(0, estimate.pricedLines)
        assertEquals(1, estimate.unpricedLines)
    }
}
