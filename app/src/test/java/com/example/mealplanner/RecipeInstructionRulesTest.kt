package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeInstructionRulesTest {
    private val steps = listOf(
        RecipeInstructionStep(id = "main-1", text = "Hak løg", level = 0),
        RecipeInstructionStep(id = "sub-1", text = "Hak fint", level = 1),
        RecipeInstructionStep(id = "sub-2", text = "Gem resten", level = 1),
        RecipeInstructionStep(id = "main-2", text = "Steg", level = 0)
    )

    @Test
    fun mainStepsAndSubstepsHaveExpectedLabels() {
        assertEquals(
            listOf("1.", "1.a", "1.b", "2."),
            RecipeInstructionRules.numbered(steps).map { it.first })
    }

    @Test
    fun movingMainStepMovesItsSubstepsAsOneBlock() {
        val moved = RecipeInstructionRules.move(steps, 0, 1)
        assertEquals(
            listOf("main-2", "main-1", "sub-1", "sub-2"),
            moved.map { it.id })
        assertEquals(
            listOf("1.", "2.", "2.a", "2.b"),
            RecipeInstructionRules.numbered(moved).map { it.first })
    }

    @Test
    fun deletingParentAlsoRemovesItsChildren() {
        assertEquals(
            listOf("main-2"),
            RecipeInstructionRules.remove(steps, 0).map { it.id })
        assertEquals(
            listOf("main-1", "sub-2", "main-2"),
            RecipeInstructionRules.remove(steps, 1).map { it.id })
    }

    @Test
    fun recipeInstructionsAndSourceUrlSurviveSerialization() {
        val recipe = Recipe(
            id = "example",
            name = "Gnocchi",
            ingredients = emptyList(),
            notes = "Min personlige note",
            instructionSteps = steps,
            sourceUrl = "https://example.com/recipe"
        )
        val saved = Json.decodeFromString(
            Recipe.serializer(),
            Json.encodeToString(Recipe.serializer(), recipe)
        )
        assertEquals(recipe, saved)
        assertEquals("Min personlige note", saved.notes)
        assertEquals("https://example.com/recipe", saved.sourceUrl)
    }

    @Test
    fun savingRecipeAlsoStoresUnsubmittedStepText() {
        val result = RecipeInstructionRules.withPendingText(
            steps, "  Servér retten varm  ", "  Drys frisk persille over  "
        )
        assertEquals(6, result.size)
        assertEquals("Servér retten varm", result[4].text)
        assertEquals(0, result[4].level)
        assertEquals("Drys frisk persille over", result[5].text)
        assertEquals(1, result[5].level)
        assertEquals(steps.size, RecipeInstructionRules.withPendingText(steps, "", " ").size)
    }

    @Test
    fun previousRecipesDefaultToNoStepsOrSourceUrl() {
        val original = Recipe(
            id = "old",
            name = "Tidligere opskrift",
            ingredients = emptyList(),
            notes = "Bevar gamle noter"
        )
        val json = Json.encodeToString(Recipe.serializer(), original)
        val parsed = Json.decodeFromString(Recipe.serializer(), json)
        assertTrue(parsed.instructionSteps.isEmpty())
        assertEquals(null, parsed.sourceUrl)
        assertEquals("Bevar gamle noter", parsed.notes)
    }
}
