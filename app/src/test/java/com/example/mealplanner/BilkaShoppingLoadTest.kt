package com.example.mealplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilkaShoppingLoadTest {
    private val catalog = Json.parseToJsonElement(
        """{"defaults":[],"candidates":[],"products":[{"id":"123","name":"Mælk"}]}"""
    ).jsonObject
    private val context = Json.parseToJsonElement(
        """{"defaults":[{"ingredient_id":"milk","product_id":"123"}],"overrides":[]}"""
    ).jsonObject

    @Test
    fun preservesShoppingContextIfCatalogFails() {
        val decoded = decodeBilkaShoppingLoad(
            Result.failure(IllegalStateException("offline")),
            Result.success(context)
        )
        assertTrue(decoded.hasError)
        assertNull(decoded.catalog)
        assertEquals("123", decoded.context?.default("milk")?.productId)
    }

    @Test
    fun preservesCatalogIfShoppingContextFails() {
        val decoded = decodeBilkaShoppingLoad(
            Result.success(catalog),
            Result.failure(IllegalStateException("offline"))
        )
        assertTrue(decoded.hasError)
        assertNotNull(decoded.catalog)
        assertNull(decoded.context)
        assertEquals("Mælk", decoded.catalog?.product("123")?.name)
    }

    @Test
    fun malformedContextDoesNotEraseCatalog() {
        val decoded = decodeBilkaShoppingLoad(
            Result.success(catalog),
            Result.success(Json.parseToJsonElement("""{}""").jsonObject)
        )
        assertTrue(decoded.hasError)
        assertNotNull(decoded.catalog)
        assertNull(decoded.context)
    }

    @Test
    fun fullSuccessKeepsBothResults() {
        val decoded = decodeBilkaShoppingLoad(
            Result.success(catalog),
            Result.success(context)
        )
        assertFalse(decoded.hasError)
        assertNotNull(decoded.catalog)
        assertNotNull(decoded.context)
    }
}
