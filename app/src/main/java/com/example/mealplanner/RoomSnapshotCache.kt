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
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Entity(tableName = "household_snapshots")
internal data class HouseholdSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "household_id")
    val householdId: String,
    val payload: String,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "normalized_v3", defaultValue = "0")
    val normalizedV3: Boolean = false
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

@Dao
internal abstract class LocalStateDao {
    @Query("select * from household_snapshots where household_id = :householdId limit 1")
    protected abstract suspend fun previousSnapshot(
        householdId: String
    ): HouseholdSnapshotEntity?

    @Query("delete from local_ingredients where household_id = :householdId")
    protected abstract suspend fun clearIngredients(householdId: String)

    @Query("delete from local_recipes where household_id = :householdId")
    protected abstract suspend fun clearRecipes(householdId: String)

    @Query("delete from local_pantry where household_id = :householdId")
    protected abstract suspend fun clearPantry(householdId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertIngredients(
        entities: List<LocalIngredientEntity>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRecipes(
        entities: List<LocalRecipeEntity>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPantry(
        entities: List<LocalPantryEntity>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSnapshot(
        entity: HouseholdSnapshotEntity
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMutations(
        entities: List<PendingMutationEntity>
    )

    @Transaction
    open suspend fun persistSnapshotAndMutations(
        snapshot: HouseholdSnapshotEntity,
        mutations: List<PendingMutationEntity>
    ) {
        val previous = previousSnapshot(snapshot.householdId)
        val decoded = RoomSnapshotCodec.decode(snapshot.payload)
        upsertSnapshot(snapshot.copy(normalizedV3 = decoded != null))
        if (mutations.isNotEmpty()) upsertMutations(mutations)
        if (decoded != null) {
            val old = previous
                ?.takeIf { it.normalizedV3 }
                ?.let { RoomSnapshotCodec.decode(it.payload) }

            // Skip unnecessary SQL writes on frequent shopping/plan updates.
            // All updates run in the same transaction as the outbox entry.
            if (old?.ingredients != decoded.ingredients) {
                clearIngredients(snapshot.householdId)
                insertIngredients(
                    LocalDomainCodec.ingredientRows(
                        snapshot.householdId, decoded.ingredients
                    )
                )
            }
            if (old?.recipes != decoded.recipes) {
                clearRecipes(snapshot.householdId)
                insertRecipes(
                    LocalDomainCodec.recipeRows(
                        snapshot.householdId, decoded.recipes
                    )
                )
            }
            if (old?.pantry != decoded.pantry) {
                clearPantry(snapshot.householdId)
                insertPantry(
                    LocalDomainCodec.pantryRows(
                        snapshot.householdId, decoded.pantry
                    )
                )
            }
        }
    }
}

@Database(
    entities = [
        HouseholdSnapshotEntity::class,
        PendingMutationEntity::class,
        LocalIngredientEntity::class,
        LocalRecipeEntity::class,
        LocalPantryEntity::class
    ],
    version = 3,
    exportSchema = true
)
internal abstract class MealPlannerDatabase : RoomDatabase() {
    abstract fun householdSnapshotDao(): HouseholdSnapshotDao
    abstract fun pendingMutationDao(): PendingMutationDao
    abstract fun localStateDao(): LocalStateDao
    abstract fun localDomainDao(): LocalDomainDao
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
            )
                // Never delete pending offline edits just to recover from a
                // missing migration. Fail visibly instead of losing user data.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also {
                    instance = it
                }
        }

    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                create table if not exists pending_mutations (
                    id text not null primary key,
                    revision text not null,
                    batch_id text not null,
                    household_id text not null,
                    mutation_key text not null,
                    priority integer not null,
                    domain_mask integer not null,
                    payload text not null,
                    base_snapshot_payload text,
                    updated_at_epoch_ms integer not null,
                    attempt_count integer not null default 0,
                    conflict integer not null default 0,
                    last_error text
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                create index if not exists
                    index_pending_mutations_household_id
                on pending_mutations(household_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                create index if not exists
                    index_pending_mutations_batch_id
                on pending_mutations(batch_id)
                """.trimIndent()
            )
        }
    }

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "alter table household_snapshots add column normalized_v3 integer not null default 0"
            )
            db.execSQL(
                """
                create table if not exists local_ingredients (
                    household_id text not null,
                    ingredient_id text not null,
                    name text not null,
                    category_id text,
                    payload text not null,
                    primary key (household_id, ingredient_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                create index if not exists index_local_ingredients_household_id_name
                on local_ingredients(household_id, name)
                """.trimIndent()
            )
            db.execSQL(
                """
                create table if not exists local_recipes (
                    household_id text not null,
                    recipe_id text not null,
                    name text not null,
                    category_id text,
                    meal_type text not null,
                    payload text not null,
                    primary key (household_id, recipe_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                create index if not exists index_local_recipes_household_id_name
                on local_recipes(household_id, name)
                """.trimIndent()
            )
            db.execSQL(
                """
                create table if not exists local_pantry (
                    household_id text not null,
                    ingredient_id text not null,
                    amount_base real not null,
                    primary key (household_id, ingredient_id)
                )
                """.trimIndent()
            )
        }
    }
}

internal class RoomSnapshotCache(
    context: Context,
    private val householdId: String
) {
    private val database = MealPlannerDatabaseProvider.get(context)
    private val snapshots = database.householdSnapshotDao()
    private val localState = database.localStateDao()
    private val domains = database.localDomainDao()

    suspend fun load(): CloudSnapshot? {
        val entity = snapshots.find(householdId) ?: return null
        val snapshot = RoomSnapshotCodec.decode(entity.payload) ?: return null

        // Existing installs have a v2 snapshot but no normalized rows.
        // Materialize it once without requiring a network connection.
        if (!entity.normalizedV3) {
            localState.persistSnapshotAndMutations(entity, emptyList())
        }

        return LocalDomainCodec.hydrate(
            snapshot = snapshot,
            ingredients = domains.ingredients(householdId),
            recipes = domains.recipes(householdId),
            pantry = domains.pantry(householdId)
        ) ?: snapshot
    }

    suspend fun save(snapshot: CloudSnapshot) {
        localState.persistSnapshotAndMutations(
            snapshot = HouseholdSnapshotEntity(
                householdId = householdId,
                payload = RoomSnapshotCodec.encode(snapshot),
                updatedAtEpochMs = System.currentTimeMillis()
            ),
            mutations = emptyList()
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
    val neverBuy: Boolean = false,
    val imageUri: String?,
    val imagePath: String?,
    val unitConversions: List<IngredientUnitConversion> = emptyList()
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
    val amountBase: Double,
    val displayAmount: Double? = null,
    val displayUnitCode: String? = null,
    val basePerDisplayUnit: Double? = null,
    val conversionApproximate: Boolean = false
)

@Serializable
private data class CachedPantryPrerequisite(
    val ingredientId: String,
    val minimumAmountBase: Double
)

@Serializable
private data class CachedRecipe(
    val id: String,
    val name: String,
    val mealType: String,
    val autoRotationEnabled: Boolean,
    val prepMinutes: Int?,
    val categoryId: String?,
    val notes: String,
    val imageUri: String?,
    val imagePath: String?,
    val ingredients: List<CachedRecipeIngredient>,
    val pantryPrerequisites: List<CachedPantryPrerequisite>,
    val instructionSteps: List<RecipeInstructionStep> = emptyList(),
    val sourceUrl: String? = null
)

@Serializable
private data class CachedHistoryEntry(
    val recipeId: String,
    val eatenOn: String
)

@Serializable
private data class CachedCloudSnapshot(
    val weekStart: String,
    val ingredients: List<CachedIngredient>,
    val ingredientCategories: List<CachedCategory>,
    val recipeCategories: List<CachedCategory>,
    val recipes: List<CachedRecipe>,
    val pantry: Map<String, Double>,
    val pantryLots: List<PantryLot> = emptyList(),
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

    fun decode(
        payload: String,
        today: LocalDate = LocalDate.now()
    ): CloudSnapshot? =
        runCatching {
            val cached = json.decodeFromString<CachedCloudSnapshot>(payload)
            val snapshot = cached.toCloudSnapshot()
            val weekStart = currentWeekStart(today).toString()
            if (cached.weekStart == weekStart) {
                snapshot
            } else {
                // Week-sensitive identifiers cannot be written into the new
                // week's server plan. Keep pantry, recipes and history offline.
                val defaults = CloudSnapshot.EMPTY
                snapshot.copy(
                    dayModes = defaults.dayModes,
                    dayMaxPrepMinutes = defaults.dayMaxPrepMinutes,
                    plan = defaults.plan,
                    lockedDays = emptySet(),
                    shoppingChecked = emptyMap(),
                    shoppingIncluded = emptyMap(),
                    selectedLunchRecipeIds = emptyList(),
                    weeklyPlanId = "offline-week-$weekStart",
                    weeklySandwichRecipeId = null,
                    sandwichDay = null,
                    viewWeekStart = currentWeekStart(today)
                )
            }
        }.getOrNull()

    private fun currentWeekStart(
        today: LocalDate = LocalDate.now()
    ): LocalDate = today.with(
        TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
    )

    private fun CloudSnapshot.toCached() = CachedCloudSnapshot(
        weekStart = viewWeekStart.toString(),
        ingredients = ingredients.map {
            CachedIngredient(
                id = it.id,
                name = it.name,
                quantityType = it.quantityType.name,
                preferredUnit = it.preferredUnit.name,
                shoppingCategoryId = it.shoppingCategoryId,
                isProtein = it.isProtein,
                neverBuy = it.neverBuy,
                imageUri = it.imageUri,
                imagePath = it.imagePath,
                unitConversions = it.unitConversions
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
                categoryId = recipe.categoryId,
                notes = recipe.notes,
                imageUri = recipe.imageUri,
                imagePath = recipe.imagePath,
                instructionSteps = recipe.instructionSteps,
                sourceUrl = recipe.sourceUrl,
                ingredients = recipe.ingredients.map {
                    CachedRecipeIngredient(
                        ingredientId = it.ingredientId,
                        amountBase = it.amountBase,
                        displayAmount = it.displayAmount,
                        displayUnitCode = it.displayUnitCode,
                        basePerDisplayUnit = it.basePerDisplayUnit,
                        conversionApproximate = it.conversionApproximate
                    )
                },
                pantryPrerequisites = recipe.pantryPrerequisites.map {
                    CachedPantryPrerequisite(
                        ingredientId = it.ingredientId,
                        minimumAmountBase = it.minimumAmountBase
                    )
                }
            )
        },
        pantry = pantry,
        pantryLots = pantryLots,
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
                    neverBuy = it.neverBuy,
                    imageUri = it.imageUri,
                    imagePath = it.imagePath,
                    unitConversions = it.unitConversions
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
                    categoryId = recipe.categoryId,
                    notes = recipe.notes,
                    imageUri = recipe.imageUri,
                    imagePath = recipe.imagePath,
                    instructionSteps = recipe.instructionSteps,
                    sourceUrl = recipe.sourceUrl,
                    ingredients = recipe.ingredients.map {
                        RecipeIngredient(
                            ingredientId = it.ingredientId,
                            amountBase = it.amountBase,
                            displayAmount = it.displayAmount,
                            displayUnitCode = it.displayUnitCode,
                            basePerDisplayUnit = it.basePerDisplayUnit,
                            conversionApproximate = it.conversionApproximate
                        )
                    },
                    pantryPrerequisites =
                        recipe.pantryPrerequisites.map {
                            RecipePantryPrerequisite(
                                ingredientId = it.ingredientId,
                                minimumAmountBase = it.minimumAmountBase
                            )
                        }
                )
            },
            pantry = pantry,
            pantryLots = pantryLots,
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
            },
            viewWeekStart = LocalDate.parse(weekStart)
        )
}
