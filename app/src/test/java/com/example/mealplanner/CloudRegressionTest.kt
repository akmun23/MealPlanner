package com.example.mealplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRegressionTest {

    @Test
    fun clearedRecipeNotesAreStillSerialized() {
        val row = RecipeRow(
            id = "recipe-id",
            householdId = "household-id",
            name = "Test",
            notes = ""
        )

        val encoded = Json
            .encodeToJsonElement(RecipeRow.serializer(), row)
            .jsonObject

        assertTrue(encoded.containsKey("notes"))
        assertEquals("", encoded["notes"]?.jsonPrimitive?.content)
    }

    @Test
    fun invalidLoginGetsShortDanishMessage() {
        val message = authErrorMessage(
            error = IllegalStateException("Invalid login credentials"),
            action = AuthAction.SIGN_IN
        )

        assertEquals("Forkert e-mail eller adgangskode.", message)
    }

    @Test
    fun unknownAuthErrorsDoNotExposeRawBackendMessage() {
        val message = authErrorMessage(
            error = IllegalStateException(
                "Unexpected backend error with implementation details that should not be shown"
            ),
            action = AuthAction.SIGN_UP
        )

        assertEquals("Kunne ikke oprette konto. Prøv igen.", message)
    }
}
