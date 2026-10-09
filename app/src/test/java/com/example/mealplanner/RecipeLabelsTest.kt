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
    fun compactMetadataDoesNotHideProteinBehindDinnerLabel() {
        val dinner = Recipe(
            id = "dinner",
            name = "Dinner",
            categoryId = "soups",
            prepMinutes = 25,
            ingredients = emptyList()
        )
        val lunch = dinner.copy(mealType = MealType.LUNCH)

        assertEquals("Supper · 25 min", recipeCompactMeta(dinner, "Supper"))
        assertEquals("Frokost · Supper · 25 min", recipeCompactMeta(lunch, "Supper"))
    }

    @Test
    fun proteinAndExplicitCategoryArePreserved() {
        assertEquals("Kylling + Æg", recipeProteinLabel(listOf("Kylling", "Æg")))
        assertEquals("Andet", recipeCategoryDisplayName("Andet", listOf("Kylling")))
        assertEquals("Supper", recipeCategoryDisplayName("Supper", emptyList()))
    }
}
