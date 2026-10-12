package com.example.mealplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One bad Bilka response must not hide the other successfully loaded list. */
class BilkaOverviewLoadTest {
    private fun raw(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    private val ingredients = raw(
        """{"items":[{"ingredientId":"milk","name":"Mælk","state":"CHOSEN","candidateCount":2}]}"""
    )
    private val products = raw(
        """
        {
          "candidates": [{"ingredient_id": "milk", "product_id": "123"}],
          "defaults": [{"ingredient_id": "milk", "product_id": "123"}],
          "products": [{"id": "123", "name": "Letmælk", "price_ore": 1200}]
        }
        """.trimIndent()
    )

    @Test
    fun productCatalogFailureDoesNotHideSavedIngredientChoices() {
        val result = decodeBilkaOverview(
            Result.success(ingredients),
            Result.failure(IllegalStateException("Netværksfejl"))
        )
        assertEquals(1, result.rows?.size)
        assertEquals("CHOSEN", result.rows?.single()?.state)
        assertNull(result.catalog)
        assertTrue(result.error.orEmpty().contains("Netværksfejl"))
    }

    @Test
    fun ingredientStatusFailureDoesNotDiscardCatalog() {
        val result = decodeBilkaOverview(
            Result.failure(IllegalStateException("Status ikke tilgængelig")),
            Result.success(products)
        )
        assertNull(result.rows)
        assertNotNull(result.catalog)
        assertEquals("123", result.catalog?.defaultId("milk"))
    }

    @Test
    fun malformedCatalogReturnsVisibleIngredientList() {
        val result = decodeBilkaOverview(
            Result.success(ingredients),
            Result.success(raw("""{"products":[] }"""))
        )
        assertEquals("Mælk", result.rows?.single()?.name)
        assertNull(result.catalog)
        assertNotNull(result.error)
    }

    @Test
    fun fullSuccessIncludesExistingChosenSku() {
        val result = decodeBilkaOverview(
            Result.success(ingredients),
            Result.success(products)
        )
        assertNull(result.error)
        assertEquals("Letmælk", result.catalog?.productsFor("milk")?.single()?.name)
    }

    @Test
    fun malformedProductRecordKeepsValidProductsVisible() {
        val catalogResponse = raw(
            """{"candidates":[{"ingredient_id":"milk","product_id":"123"}],
                "defaults":[],"products":[
                {"id":"123","name":"Letmælk"},
                {"id":"345","quantity_kind":"MASS"}]}"""
        )
        val result = decodeBilkaOverview(
            Result.success(ingredients),
            Result.success(catalogResponse)
        )
        assertEquals("Letmælk", result.catalog?.product("123")?.name)
        assertEquals(listOf("1 varer"), result.catalog?.parseWarnings)
        assertNotNull(result.error)
    }

    @Test
    fun unknownProviderEnumKeepsProductVisibleWithUnknownUnitKind() {
        val decoded = parseBilkaCatalog(
            raw(
                """
            {"candidates":[],"defaults":[],"products":[
              {"id":"bilkatogo:123","name":"Ny Bilka-vare",
               "quantity_kind":"FUTURE_KIND","unit_price_basis":"FUTURE_BASIS",
               "price_ore":2595}
            ]}
            """.trimIndent()
            )
        )
        assertEquals(1, decoded.products.size)
        assertNull(decoded.products.single().quantityKind)
        assertNull(decoded.products.single().unitPriceBasis)
        assertEquals(2595L, decoded.products.single().priceOre)
        assertTrue(decoded.parseWarnings.isEmpty())
    }

    @Test
    fun snakeCaseIngredientStatusAndOptionalFieldsStillShowRow() {
        val decoded = parseBilkaIngredients(
            raw(
                """{"items":[{"ingredient_id":"milk","name":"Mælk","state":"READY",
               "candidate_count":3}, {"ingredientId":"bread","name":"Brød"}]}"""
            )
        )
        assertEquals(listOf("milk", "bread"), decoded.map { it.ingredientId })
        assertEquals(3, decoded.first().candidateCount)
        assertEquals("PENDING", decoded.last().state)
    }

}
