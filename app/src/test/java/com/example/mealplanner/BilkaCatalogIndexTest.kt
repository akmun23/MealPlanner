package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilkaCatalogIndexTest {
    @Test
    fun largeCatalogOnlyReturnsProductsForSelectedIngredient() {
        val products = (1..1000).map { id ->
            BilkaProduct(
                id = id.toString(),
                name = "Vare $id",
                brand = "Mærke",
                packageQuantity = 1.0,
                packageUnit = "stk"
            )
        }
        val catalog = BilkaProductCatalog(
            products = products,
            defaults = listOf(BilkaListDefault("milk", "32")),
            candidates = listOf(
                BilkaListCandidate("milk", "44", 1),
                BilkaListCandidate("milk", "32", 0),
                BilkaListCandidate("bread", "107", 0)
            )
        )
        assertEquals("32", catalog.defaultId("milk"))
        assertEquals(setOf("32", "44"), catalog.suggestions("milk"))
        assertEquals(setOf("32", "44"), catalog.productsFor("milk").map { it.id }.toSet())
        assertEquals(listOf("107"), catalog.productsFor("bread").map { it.id })
        assertTrue(catalog.productsFor("missing").isEmpty())
        assertEquals(mapOf("44" to 1, "32" to 0), catalog.ranks("milk"))
        assertEquals("Vare 44", catalog.product("44")?.name)
        assertNull(catalog.product("not-found"))
    }
}
