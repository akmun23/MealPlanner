package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PantryImageRulesTest {

    @Test
    fun pantryPlaceholderRemainsSixtyDpRegardlessOfOriginalPixels() {
        assertEquals(60, PantryImageRules.THUMBNAIL_SIZE_DP)
        assertEquals(384, PantryImageRules.INGREDIENT_UPLOAD_MAX_PIXELS)
    }

    @Test
    fun selectedBilkaProductImageShowsInPantryWithoutManualPhoto() {
        val image = "https://images.example.com/hvedemel-large-4000x3000.jpg"
        assertEquals(image, PantryImageRules.displayImage(null, image))
    }

    @Test
    fun userChosenIngredientImageTakesPrecedenceOverBilkaProductImage() {
        val personal = "mealplanner-image://user-photo"
        val product = "https://images.example.com/product.jpg"
        assertEquals(personal, PantryImageRules.displayImage(personal, product))
    }

    @Test
    fun missingOrInvalidProductImageFallsBackToExistingPlaceholder() {
        assertNull(PantryImageRules.displayImage(null, null))
        assertNull(PantryImageRules.displayImage(null, "javascript:alert(1)"))
        assertNull(PantryImageRules.displayImage("", "file:///private/secret.jpg"))
    }
}
