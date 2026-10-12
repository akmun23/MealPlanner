package com.example.mealplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sample payload matches the live bilka-prej catalog/discoveryStatus fields.
 * This path must not use generated serializers in an R8 optimized release.
 */
class BilkaWireParsingTest {
    private fun obj(source: String) = Json.parseToJsonElement(source).jsonObject

    @Test
    fun decodesLiveCatalogFieldsAndNullableValues() {
        val catalog = parseBilkaCatalog(
            obj(
                """
            {
              "candidates": [
                {"ingredient_id":"ingredient-1","product_id":"bilkatogo:120",
                 "display_rank":4}
              ],
              "defaults": [
                {"ingredient_id":"ingredient-1","product_id":"bilkatogo:120"}
              ],
              "products": [
                {
                  "id":"bilkatogo:120","name":"Letmælk","brand":null,
                  "chain_slug":"bilkatogo","package_quantity":1000,
                  "package_unit":"ml","package_base_amount":1000,
                  "quantity_kind":"VOLUME","image_url":null,
                  "price_ore":1450,"unit_price_ore":1450.0,
                  "unit_price_basis":"L","is_offer":false,
                  "is_available":true,"price_updated_at":null,
                  "forward_compatible_field":42
                }
              ]
            }
            """.trimIndent()
            )
        )
        val product = catalog.product("bilkatogo:120")!!
        assertEquals("bilkatogo:120", catalog.defaultId("ingredient-1"))
        assertEquals(4, catalog.ranks("ingredient-1")["bilkatogo:120"])
        assertEquals("Letmælk", product.name)
        assertNull(product.brand)
        assertEquals(BilkaQuantityKind.VOLUME, product.quantityKind)
        assertEquals(BilkaUnitPriceBasis.L, product.unitPriceBasis)
        assertEquals(1450L, product.priceOre)
        assertEquals(1450.0, product.unitPriceOre!!, 0.0001)
        assertEquals(true, product.isAvailable)
        assertEquals(false, product.isOffer)
        assertTrue(catalog.parseWarnings.isEmpty())
    }

    @Test
    fun decodesDiscoveryStatusWithoutUnknownFieldsCrashing() {
        val ingredients = parseBilkaIngredients(
            obj(
                """
            {
              "total":2,"chosen":1,
              "items":[
                {"ingredientId":"milk","name":"Mælk",
                 "state":"CHOSEN","candidateCount":3,
                 "futureField":"safe"},
                {"ingredientId":"bread","name":"Brød",
                 "state":"READY","candidateCount":5}
              ]
            }
            """.trimIndent()
            )
        )
        assertEquals(listOf("milk", "bread"), ingredients.map { it.ingredientId })
        assertEquals(3, ingredients.first().candidateCount)
        assertEquals("READY", ingredients.last().state)
    }

    @Test
    fun missingRequiredArraysAreReportedRatherThanSilentlyEmpty() {
        val result = runCatching { parseBilkaCatalog(obj("""{"products":[]}""")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun parsesCountAndPackageProductsForCheckoutCompatibility() {
        val catalog = parseBilkaCatalog(
            obj(
                """{"candidates":[],"defaults":[],"products":[
                     {"id":"count","name":"Avocado","quantity_kind":"COUNT"},
                     {"id":"package","name":"Dåsetomat","quantity_kind":"PACKAGE"}
                   ]}"""
            )
        )
        assertEquals(BilkaQuantityKind.COUNT, catalog.product("count")?.quantityKind)
        assertEquals(BilkaQuantityKind.PACKAGE, catalog.product("package")?.quantityKind)
    }

    @Test
    fun unsupportedEnumDoesNotRemoveOtherwiseValidProduct() {
        val catalog = parseBilkaCatalog(
            obj(
                """
            {"candidates":[],"defaults":[],"products":[
               {"id":"99","name":"Ny vare",
                "quantity_kind":"UNEXPECTED","unit_price_basis":"UNEXPECTED"}]}
            """.trimIndent()
            )
        )
        assertEquals(1, catalog.products.size)
        assertNull(catalog.products.first().quantityKind)
        assertNull(catalog.products.first().unitPriceBasis)
        assertFalse(catalog.products.isEmpty())
    }
}
