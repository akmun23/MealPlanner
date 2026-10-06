package com.example.mealplanner

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate

@Entity(tableName = "household_snapshots")
internal data class HouseholdSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "household_id")
    val householdId: String,
    val payload: String,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)

@Dao
internal interface HouseholdSnapshotDao {
    @Query(
        """
        select *
        from household_snapshots
        where household_id = :householdId
        limit 1
        """
    )
    suspend fun find(householdId: String): HouseholdSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HouseholdSnapshotEntity)
}

@Database(
    entities = [HouseholdSnapshotEntity::class],
    version = 1,
    exportSchema = true
)
internal abstract class MealPlannerDatabase : RoomDatabase() {
    abstract fun householdSnapshotDao(): HouseholdSnapshotDao
}

internal object MealPlannerDatabaseProvider {
    @Volatile
    private var instance: MealPlannerDatabase? = null

    fun get(context: Context): MealPlannerDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MealPlannerDatabase::class.java,
                "meal_planner_local.db"
            ).build().also {
                instance = it
            }
        }
}

internal class RoomSnapshotCache(
    context: Context,
    private val householdId: String
) {
    private val dao = MealPlannerDatabaseProvider
        .get(context)
        .householdSnapshotDao()

    suspend fun load(): CloudSnapshot? {
        val entity = dao.find(householdId) ?: return null
        return RoomSnapshotCodec.decode(entity.payload)
    }

    suspend fun save(snapshot: CloudSnapshot) {
        dao.upsert(
            HouseholdSnapshotEntity(
                householdId = householdId,
                payload = RoomSnapshotCodec.encode(snapshot),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }
}

@Serializable
private data class CachedIngredient(
    val id: String,
    val name: String,
    val quantityType: String,
    val preferredUnit: String,
    val shoppingCategoryId: String?,
    val isProtein: Boolean,
    val imageUri: String?,
    val imagePath: String?
)

@Serializable
private data class CachedCategory(
    val id: String,
    val name: String,
    val position: Int
)

@Serializable
private data class CachedRecipeIngredient(
    val ingredientId: String,
    val amountBase: Double
)

@Serializable
private data class CachedRecipe(
    val id: String,
    val name: String,
    val mealType: String,
    val autoRotationEnabled: Boolean,
    val prepMinutes: Int?,
    val proteinTag: String,
    val categoryTag: String,
    val categoryId: String?,
    val notes: String,
    val imageUri: String?,
    val imagePath: String?,
    val ingredients: List<CachedRecipeIngredient>
)

@Serializable
private data class CachedHistoryEntry(
    val recipeId: String,
    val eatenOn: String
)

@Serializable
private data class CachedCloudSnapshot(
    val ingredients: List<CachedIngredient>,
    val ingredientCategories: List<CachedCategory>,
    val recipeCategories: List<CachedCategory>,
    val recipes: List<CachedRecipe>,
    val pantry: Map<String, Double>,
    val dayModes: Map<String, String>,
    val dayMaxPrepMinutes: Map<String, Int?>,
    val plan: Map<String, String?>,
    val lockedDays: List<String>,
    val shoppingChecked: Map<String, Boolean>,
    val shoppingIncluded: Map<String, Boolean>,
    val selectedLunchRecipeIds: List<String>,
    val weeklyPlanId: String,
    val weeklySandwichRecipeId: String?,
    val sandwichDay: String?,
    val history: List<CachedHistoryEntry>
)

internal object RoomSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: CloudSnapshot): String =
        json.encodeToString(snapshot.toCached())

    fun decode(payload: String): CloudSnapshot? =
        runCatching {
            json.decodeFromString<CachedCloudSnapshot>(payload)
                .toCloudSnapshot()
        }.getOrNull()

    private fun CloudSnapshot.toCached() = CachedCloudSnapshot(
        ingredients = ingredients.map {
            CachedIngredient(
                id = it.id,
                name = it.name,
                quantityType = it.quantityType.name,
                preferredUnit = it.preferredUnit.name,
                shoppingCategoryId = it.shoppingCategoryId,
                isProtein = it.isProtein,
                imageUri = it.imageUri,
                imagePath = it.imagePath
            )
        },
        ingredientCategories = ingredientCategories.map {
            CachedCategory(
                id = it.id,
                name = it.name,
                position = it.position
            )
        },
        recipeCategories = recipeCategories.map {
            CachedCategory(
                id = it.id,
                name = it.name,
                position = it.position
            )
        },
        recipes = recipes.map { recipe ->
            CachedRecipe(
                id = recipe.id,
                name = recipe.name,
                mealType = recipe.mealType.name,
                autoRotationEnabled = recipe.autoRotationEnabled,
                prepMinutes = recipe.prepMinutes,
                proteinTag = recipe.proteinTag,
                categoryTag = recipe.categoryTag,
                categoryId = recipe.categoryId,
                notes = recipe.notes,
                imageUri = recipe.imageUri,
                imagePath = recipe.imagePath,
                ingredients = recipe.ingredients.map {
                    CachedRecipeIngredient(
                        ingredientId = it.ingredientId,
                        amountBase = it.amountBase
                    )
                }
            )
        },
        pantry = pantry,
        dayModes = dayModes.mapKeys { it.key.name }
            .mapValues { it.value.name },
        dayMaxPrepMinutes =
            dayMaxPrepMinutes.mapKeys { it.key.name },
        plan = plan.mapKeys { it.key.name },
        lockedDays = lockedDays.map { it.name },
        shoppingChecked = shoppingChecked,
        shoppingIncluded = shoppingIncluded,
        selectedLunchRecipeIds = selectedLunchRecipeIds,
        weeklyPlanId = weeklyPlanId,
        weeklySandwichRecipeId = weeklySandwichRecipeId,
        sandwichDay = sandwichDay?.name,
        history = history.map {
            CachedHistoryEntry(
                recipeId = it.recipeId,
                eatenOn = it.eatenOn.toString()
            )
        }
    )

    private fun CachedCloudSnapshot.toCloudSnapshot(): CloudSnapshot =
        CloudSnapshot(
            ingredients = ingredients.map {
                Ingredient(
                    id = it.id,
                    name = it.name,
                    quantityType =
                        QuantityType.valueOf(it.quantityType),
                    preferredUnit =
                        MeasurementUnit.valueOf(it.preferredUnit),
                    shoppingCategoryId = it.shoppingCategoryId,
                    isProtein = it.isProtein,
                    imageUri = it.imageUri,
                    imagePath = it.imagePath
                )
            },
            ingredientCategories = ingredientCategories.map {
                IngredientCategory(
                    id = it.id,
                    name = it.name,
                    position = it.position
                )
            },
            recipeCategories = recipeCategories.map {
                RecipeCategoryOption(
                    id = it.id,
                    name = it.name,
                    position = it.position
                )
            },
            recipes = recipes.map { recipe ->
                Recipe(
                    id = recipe.id,
                    name = recipe.name,
                    mealType = MealType.valueOf(recipe.mealType),
                    autoRotationEnabled =
                        recipe.autoRotationEnabled,
                    prepMinutes = recipe.prepMinutes,
                    proteinTag = recipe.proteinTag,
                    categoryTag = recipe.categoryTag,
                    categoryId = recipe.categoryId,
                    notes = recipe.notes,
                    imageUri = recipe.imageUri,
                    imagePath = recipe.imagePath,
                    ingredients = recipe.ingredients.map {
                        RecipeIngredient(
                            ingredientId = it.ingredientId,
                            amountBase = it.amountBase
                        )
                    }
                )
            },
            pantry = pantry,
            dayModes = dayModes.mapKeys {
                DayOfWeek.valueOf(it.key)
            }.mapValues {
                DayMode.valueOf(it.value)
            },
            dayMaxPrepMinutes =
                dayMaxPrepMinutes.mapKeys {
                    DayOfWeek.valueOf(it.key)
                },
            plan = plan.mapKeys {
                DayOfWeek.valueOf(it.key)
            },
            lockedDays = lockedDays
                .mapTo(mutableSetOf()) {
                    DayOfWeek.valueOf(it)
                },
            shoppingChecked = shoppingChecked,
            shoppingIncluded = shoppingIncluded,
            selectedLunchRecipeIds = selectedLunchRecipeIds,
            weeklyPlanId = weeklyPlanId,
            weeklySandwichRecipeId = weeklySandwichRecipeId,
            sandwichDay = sandwichDay?.let(
                DayOfWeek::valueOf
            ),
            history = history.map {
                MealHistoryEntry(
                    recipeId = it.recipeId,
                    eatenOn = LocalDate.parse(it.eatenOn)
                )
            }
        )
}
