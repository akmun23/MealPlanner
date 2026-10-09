package com.example.mealplanner

import java.time.DayOfWeek
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBackupCodecTest {
    private val data = CloudSnapshot.EMPTY.copy(
        ingredients = listOf(
            Ingredient(
                id = "rice", name = "Ris",
                quantityType = QuantityType.MASS,
                preferredUnit = MeasurementUnit.GRAM
            )
        ),
        recipes = listOf(
            Recipe(
                id = "dinner",
                name = "Risret",
                ingredients = listOf(RecipeIngredient("rice", 250.0))
            )
        ),
        pantry = mapOf("rice" to 500.0),
        plan = orderedWeekDays.associateWith {
            if (it == DayOfWeek.MONDAY) "dinner" else null
        }
    )

    @Test
    fun backupRoundTripsWithoutAccountSecrets() {
        val json = SnapshotBackupCodec.encode(
            data,
            exportedAt = Instant.parse("2026-10-09T15:00:00Z")
        )
        assertTrue(json.contains("mealplanner-household-backup"))
        assertTrue(json.contains("2026-10-09T15:00:00Z"))
        assertFalse(json.contains("access_token"))
        assertFalse(json.contains("join_code"))
        assertEquals(data, SnapshotBackupCodec.decode(json))
    }

    @Test
    fun usersCanEditIngredientNamesAndAmountsInExportedJson() {
        val original = SnapshotBackupCodec.encode(data)
        val modified = original
            .replace("\"name\":\"Ris\"", "\"name\":\"Basmati ris\"")
            .replace("\"rice\":500.0", "\"rice\":900.0")
        val decoded = SnapshotBackupCodec.decode(modified)
        assertEquals("Basmati ris", decoded.ingredients.single().name)
        assertEquals(900.0, decoded.pantry["rice"] ?: 0.0, 0.0)
    }

    @Test
    fun malformedOrUnknownFormatsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotBackupCodec.decode("not-json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotBackupCodec.decode("{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotBackupCodec.decode(
                SnapshotBackupCodec.encode(data)
                    .replace("\"version\":1", "\"version\":999")
            )
        }
    }

    @Test
    fun corruptedNegativePantryAndDuplicateIdsAreRejected() {
        val invalidPantry = data.copy(pantry = mapOf("rice" to -2.0))
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotBackupCodec.decode(SnapshotBackupCodec.encode(invalidPantry))
        }
        val duplicate = data.copy(ingredients = data.ingredients + data.ingredients)
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotBackupCodec.decode(SnapshotBackupCodec.encode(duplicate))
        }
    }
}
