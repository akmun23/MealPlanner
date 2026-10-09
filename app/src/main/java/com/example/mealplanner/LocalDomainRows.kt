package com.example.mealplanner

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import kotlinx.serialization.json.Json

/**
 * Indexed household-scoped records. The weekly plan/categories/history are
 * retained in the snapshot while these frequently used domains can be read
 * independently from Room without decoding the entire household JSON.
 */
@Entity(
    tableName = "local_ingredients",
    primaryKeys = ["household_id", "ingredient_id"],
    indices = [Index(value = ["household_id", "name"])]
)
internal data class LocalIngredientEntity(
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "ingredient_id") val ingredientId: String,
    val name: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val payload: String
)

@Entity(
    tableName = "local_recipes",
    primaryKeys = ["household_id", "recipe_id"],
    indices = [Index(value = ["household_id", "name"])]
)
internal data class LocalRecipeEntity(
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val name: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "meal_type") val mealType: String,
    val payload: String
)

@Entity(
    tableName = "local_pantry",
    primaryKeys = ["household_id", "ingredient_id"]
)
internal data class LocalPantryEntity(
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "ingredient_id") val ingredientId: String,
    @ColumnInfo(name = "amount_base") val amountBase: Double
)

@Dao
internal interface LocalDomainDao {
    @Query("select * from local_ingredients where household_id = :householdId order by name collate nocase")
    suspend fun ingredients(householdId: String): List<LocalIngredientEntity>

    @Query("select * from local_recipes where household_id = :householdId order by name collate nocase")
    suspend fun recipes(householdId: String): List<LocalRecipeEntity>

    @Query("select * from local_pantry where household_id = :householdId")
    suspend fun pantry(householdId: String): List<LocalPantryEntity>
}

internal object LocalDomainCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun ingredientRows(
        householdId: String,
        ingredients: List<Ingredient>
    ): List<LocalIngredientEntity> = ingredients.map {
        LocalIngredientEntity(
            householdId = householdId,
            ingredientId = it.id,
            name = it.name,
            categoryId = it.shoppingCategoryId,
            payload = json.encodeToString(it)
        )
    }

    fun recipeRows(
        householdId: String,
        recipes: List<Recipe>
    ): List<LocalRecipeEntity> = recipes.map {
        LocalRecipeEntity(
            householdId = householdId,
            recipeId = it.id,
            name = it.name,
            categoryId = it.categoryId,
            mealType = it.mealType.name,
            payload = json.encodeToString(it)
        )
    }

    fun pantryRows(
        householdId: String,
        pantry: Map<String, Double>
    ): List<LocalPantryEntity> = pantry.map { (ingredientId, amountBase) ->
        LocalPantryEntity(householdId, ingredientId, amountBase)
    }

    fun hydrate(
        snapshot: CloudSnapshot,
        ingredients: List<LocalIngredientEntity>,
        recipes: List<LocalRecipeEntity>,
        pantry: List<LocalPantryEntity>
    ): CloudSnapshot? = runCatching {
        snapshot.copy(
            ingredients = ingredients.map {
                json.decodeFromString<Ingredient>(it.payload)
            },
            recipes = recipes.map {
                json.decodeFromString<Recipe>(it.payload)
            },
            pantry = pantry.associate { it.ingredientId to it.amountBase }
        )
    }.getOrNull()
}
