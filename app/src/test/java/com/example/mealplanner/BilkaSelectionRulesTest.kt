package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilkaSelectionRulesTest {
    private fun item(
        id: String,
        brand: String,
        unitPrice: Double?,
        basis: BilkaUnitPriceBasis?
    ) = BilkaProduct(
        id = id,
        name = "Hvedemel",
        brand = brand,
        packageQuantity = 2.0,
        packageUnit = "kg",
        unitPriceOre = unitPrice,
        unitPriceBasis = basis,
        priceOre = 2500
    )

    @Test
    fun sortsComparableKilogramPricesAndKeepsExactProductId() {
        val items = listOf(
            item("1", "Valsemøllen", 1300.0, BilkaUnitPriceBasis.KG),
            item("2", "Budget", 900.0, BilkaUnitPriceBasis.KG),
            item("3", "Finax", null, BilkaUnitPriceBasis.KG)
        )
        assertEquals(
            listOf("2", "1", "3"),
            BilkaSelectionRules.sortForIngredient(items, emptyMap(), true).map { it.id }
        )
        assertEquals("Budget", items[1].brand)
    }

    @Test
    fun differentPriceUnitsUseRelevanceRatherThanInvalidComparison() {
        val items = listOf(
            item("1", "Mærke A", 100.0, BilkaUnitPriceBasis.PIECE),
            item("2", "Mærke B", 1500.0, BilkaUnitPriceBasis.KG)
        )
        assertEquals(
            listOf("2", "1"),
            BilkaSelectionRules.sortForIngredient(
                items, mapOf("2" to 0, "1" to 1), true
            ).map { it.id }
        )
    }

    @Test
    fun keepsRememberedSkuFirstEvenWhenAnotherSuggestionIsCheaper() {
        val products = listOf(
            item("old", "Valgt mærke", 1500.0, BilkaUnitPriceBasis.KG),
            item("new", "Billigere alternativ", 900.0, BilkaUnitPriceBasis.KG)
        )
        assertEquals(
            listOf("old", "new"),
            BilkaSelectionRules.sortForIngredient(
                products, emptyMap(), true, chosenProductId = "old"
            ).map { it.id }
        )
        assertEquals(
            listOf("new", "old"),
            BilkaSelectionRules.sortForIngredient(products, emptyMap(), true).map { it.id }
        )
    }

    @Test
    fun onlyNewSuggestedSkuCanBeSaved() {
        assertTrue(BilkaSelectionRules.canConfirm("new", setOf("new"), "old"))
        assertFalse(BilkaSelectionRules.canConfirm("old", setOf("old"), "old"))
        assertFalse(BilkaSelectionRules.canConfirm("other-households-sku", setOf("new"), "old"))
        assertFalse(BilkaSelectionRules.canConfirm(null, setOf("new"), null))
    }

    @Test
    fun catalogReadsBrandImagesAndKeepsHouseholdDefaultSeparate() {
        val raw = """
        {
          "products": [{
            "id": "42", "name": "Hvedemel", "brand": "Valsemøllen",
            "package_quantity": 2.0, "package_unit": "kg",
            "image_url": "https://example.com/mel.jpg",
            "price_ore": 2595, "unit_price_ore": 1297.5,
            "unit_price_basis": "KG", "chain_slug": "bilka"
          }],
          "candidates": [{"ingredient_id": "flour", "product_id": "42", "display_rank": 0}],
          "defaults": [{"ingredient_id": "flour", "product_id": "42"}]
        }
        """
        val catalog = parseBilkaCatalog(
            Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        )
        assertEquals("42", catalog.defaultId("flour"))
        assertEquals("Valsemøllen", catalog.productsFor("flour").single().brandLabel)
        assertEquals("https://example.com/mel.jpg", catalog.products.single().imageUrl)
    }

    @Test
    fun emptyCandidateListDoesNotInventAProductChoice() {
        val raw = """{"products":[],"candidates":[],"defaults":[]}"""
        val catalog = parseBilkaCatalog(
            Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        )
        assertEquals(emptyList<BilkaProduct>(), catalog.productsFor("flour"))
    }
    @Test
    fun relevanceSortPutsActualCreamBeforeLiqueurAndSweetsWithoutHidingAnything() {
        val items = listOf(
            item("liqueur", "A", 800.0, BilkaUnitPriceBasis.L).copy(name = "Flødelikør 17%"),
            item("chocolate", "B", 700.0, BilkaUnitPriceBasis.PIECE).copy(name = "Proteinbar Flødebolle"),
            item("cream", "C", 1900.0, BilkaUnitPriceBasis.L).copy(name = "Arla Piskefløde 38%"),
            item("plain", "D", 2200.0, BilkaUnitPriceBasis.L).copy(name = "Fløde")
        )
        val ranked = BilkaSelectionRules.sortForIngredient(
            items, emptyMap(), false, ingredientName = "Fløde"
        )
        assertEquals(listOf("plain", "cream", "chocolate", "liqueur"), ranked.map { it.id })
        assertEquals(4, ranked.size)
    }

    @Test
    fun relevanceKeepsSavedSkuFirstAndDemotesCultivationAndAlcohol() {
        val items = listOf(
            item("garden", "A", 1200.0, BilkaUnitPriceBasis.KG).copy(name = "Chili plante"),
            item("alcohol", "B", 1100.0, BilkaUnitPriceBasis.L).copy(name = "Chili tequila"),
            item("food", "C", 2200.0, BilkaUnitPriceBasis.KG).copy(name = "Økologisk Chili")
        )
        val ranked = BilkaSelectionRules.sortForIngredient(
            items, emptyMap(), false, chosenProductId = "garden", ingredientName = "Chili"
        )
        assertEquals(listOf("garden", "food", "alcohol"), ranked.map { it.id })
        assertEquals(3, ranked.size)
    }

    @Test
    fun matchingIngredientDoesNotTreatANamePrefixAsACompleteMatch() {
        assertTrue(BilkaSelectionRules.ingredientNameScore("Fløde", "Arla Fløde 38%") >
            BilkaSelectionRules.ingredientNameScore("Fløde", "Flødebolle"))
        assertTrue(BilkaSelectionRules.ingredientNameScore("Mælk", "Sødmælk") >
            BilkaSelectionRules.ingredientNameScore("Mælk", "Mælkebøttefjerner"))
        assertTrue(BilkaSelectionRules.ingredientNameScore("Agurk", "Agurkeplanter") <= 0)
    }

}
