package com.example.mealplanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Portable JSON for household-owned meal data, without authentication tokens,
 * household invite codes or account credentials. The user can edit the JSON
 * file and restore it into a local-only household.
 */
internal object SnapshotBackupCodec {
    private const val FORMAT = "mealplanner-household-backup"
    private const val VERSION = 1
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    fun encode(snapshot: CloudSnapshot, exportedAt: Instant = Instant.now()): String {
        val data = Json.parseToJsonElement(RoomSnapshotCodec.encode(snapshot))
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exported_at_utc", exportedAt.toString())
            put("data", data)
        }.toString()
    }

    fun decode(payload: String): CloudSnapshot {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Backup file is too large"
        }
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid JSON backup", it) }
        require(obj["format"]?.jsonPrimitive?.content == FORMAT) {
            "This is not a MealPlanner backup"
        }
        require(obj["version"]?.jsonPrimitive?.int == VERSION) {
            "Unsupported backup format version"
        }
        val data = obj["data"]?.jsonObject
            ?: throw IllegalArgumentException("Backup contains no household data")
        val snapshot = RoomSnapshotCodec.decode(data.toString())
            ?: throw IllegalArgumentException("Invalid household data")
        validate(snapshot)
        return snapshot
    }

    private fun validate(snapshot: CloudSnapshot) {
        val ingredients = snapshot.ingredients
        val recipes = snapshot.recipes
        require(ingredients.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(recipes.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(ingredients.map { it.id }.distinct().size == ingredients.size)
        require(recipes.map { it.id }.distinct().size == recipes.size)
        require(snapshot.pantry.values.all { it.isFinite() && it >= 0.0 })
        require(recipes.all { recipe ->
            recipe.ingredients.all { it.amountBase.isFinite() && it.amountBase >= 0.0 } &&
                recipe.pantryPrerequisites.all {
                    it.minimumAmountBase.isFinite() && it.minimumAmountBase >= 0.0
                }
        })
    }
}
