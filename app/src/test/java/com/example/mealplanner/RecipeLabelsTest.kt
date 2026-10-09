package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeLabelsTest {
    @Test
    fun noProteinIsVegetarianInsteadOfOther() {
        assertEquals("Vegetarisk", recipeProteinLabel(emptyList()))
        assertEquals("Vegetarisk", recipeCategoryDisplayName("Andet", emptyList()))
        assertEquals("Vegetarisk", recipeCategoryDisplayName("andet", emptyList()))
    }

    @Test
    fun proteinAndExplicitCategoryArePreserved() {
        assertEquals("Kylling + Æg", recipeProteinLabel(listOf("Kylling", "Æg")))
        assertEquals("Andet", recipeCategoryDisplayName("Andet", listOf("Kylling")))
        assertEquals("Supper", recipeCategoryDisplayName("Supper", emptyList()))
    }
}
