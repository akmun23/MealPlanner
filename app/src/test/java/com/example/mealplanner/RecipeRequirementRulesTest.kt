package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeRequirementRulesTest {
    @Test
    fun duplicateIngredientTapsMergeAmountsInOriginalOrder() {
        val saved = RecipeRequirementRules.combineIngredients(listOf(
            RecipeIngredient("flour", 100.0),
            RecipeIngredient("eggs", 2.0),
            RecipeIngredient("flour", 50.0)
        ))
        assertEquals(listOf(
            RecipeIngredient("flour", 150.0),
            RecipeIngredient("eggs", 2.0)
        ), saved)
        assertEquals(saved, RecipeRequirementRules.combineIngredients(saved))
    }

    @Test
    fun repeatedPantryPrerequisitesUseStrongestMinimum() {
        val saved = RecipeRequirementRules.combinePrerequisites(listOf(
            RecipePantryPrerequisite("flour", 50.0),
            RecipePantryPrerequisite("eggs", 2.0),
            RecipePantryPrerequisite("flour", 100.0)
        ))
        assertEquals(listOf(
            RecipePantryPrerequisite("flour", 100.0),
            RecipePantryPrerequisite("eggs", 2.0)
        ), saved)
        assertEquals(saved, RecipeRequirementRules.combinePrerequisites(saved))
    }

    @Test
    fun emptyDraftsStayEmpty() {
        assertEquals(emptyList<RecipeIngredient>(),
            RecipeRequirementRules.combineIngredients(emptyList()))
        assertEquals(emptyList<RecipePantryPrerequisite>(),
            RecipeRequirementRules.combinePrerequisites(emptyList()))
    }
}
