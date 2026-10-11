package com.example.mealplanner

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the 0.5-second recipe-steps disappearance after saving:
 * the optimistic local recipe has steps, but cloud reload used to request
 * only six columns and silently decode instructionSteps as emptyList().
 */
class RecipeCloudProjectionTest {
    @Test
    fun recipesReadEveryPersistedFieldIncludingStepsAndUrl() {
        assertEquals(
            listOf(
                "id", "household_id", "name", "meal_type", "notes",
                "instruction_steps", "source_url", "image_path"
            ),
            recipeCloudReadColumns
        )
        val serializedColumnNames = RecipeRow.serializer().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map(descriptor::getElementName)
        }
        assertEquals(
            "Every serializable recipe field must be included in remote SELECT",
            serializedColumnNames,
            recipeCloudReadColumns
        )
    }

    @Test
    fun downloadedRecipeRetainsStepsAndSourceUrl() {
        val row = Json.decodeFromString<RecipeRow>(
            """
            {
              "id": "recipe-1",
              "household_id": "house-1",
              "name": "Chili",
              "meal_type": "DINNER",
              "notes": "",
              "instruction_steps": [
                {"id": "step-1", "text": "Hak løg", "level": 0},
                {"id": "step-2", "text": "Steg chilien", "level": 1}
              ],
              "source_url": "https://example.com/recipe",
              "image_path": null
            }
            """.trimIndent()
        )

        assertEquals(listOf("Hak løg", "Steg chilien"), row.instructionSteps.map { it.text })
        assertEquals(listOf(0, 1), row.instructionSteps.map { it.level })
        assertEquals("https://example.com/recipe", row.sourceUrl)
    }
}
