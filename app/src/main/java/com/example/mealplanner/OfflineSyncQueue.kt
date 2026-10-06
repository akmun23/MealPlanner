package com.example.mealplanner

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity(
    tableName = "pending_mutations",
    indices = [Index("household_id")]
)
internal data class PendingMutationEntity(
    @PrimaryKey
    val id: String,
    val revision: String,
    @ColumnInfo(name = "household_id")
    val householdId: String,
    @ColumnInfo(name = "mutation_key")
    val mutationKey: String,
    val priority: Int,
    @ColumnInfo(name = "domain_mask")
    val domainMask: Int,
    val payload: String,
    @ColumnInfo(name = "base_snapshot_payload")
    val baseSnapshotPayload: String? = null,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(
        name = "attempt_count",
        defaultValue = "0"
    )
    val attemptCount: Int = 0,
    @ColumnInfo(
        name = "conflict",
        defaultValue = "0"
    )
    val conflict: Boolean = false,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)

@Dao
internal interface PendingMutationDao : PendingMutationStore {
    @Query(
        """
        select *
        from pending_mutations
        where household_id = :householdId
          and conflict = 0
        order by priority asc, updated_at_epoch_ms asc
        limit 1
        """
    )
    override suspend fun next(
        householdId: String
    ): PendingMutationEntity?

    @Query(
        """
        select *
        from pending_mutations
        where id = :id
        limit 1
        """
    )
    suspend fun find(id: String): PendingMutationEntity?

    @Query(
        """
        delete from pending_mutations
        where id = :id
          and revision = :revision
        """
    )
    override suspend fun deleteIfCurrent(
        id: String,
        revision: String
    ): Int

    @Query(
        """
        update pending_mutations
        set attempt_count = attempt_count + 1,
            last_error = :message
        where id = :id
          and revision = :revision
        """
    )
    override suspend fun markFailure(
        id: String,
        revision: String,
        message: String?
    ): Int

    @Query(
        """
        select count(*)
        from pending_mutations
        where household_id = :householdId
        """
    )
    suspend fun count(householdId: String): Int

    @Query(
        """
        select count(*)
        from pending_mutations
        where household_id = :householdId
        """
    )
    fun observeCount(householdId: String): Flow<Int>

    @Query(
        """
        select count(*)
        from pending_mutations
        where household_id = :householdId
          and attempt_count >= 6
          and conflict = 0
        """
    )
    fun observeFailedCount(householdId: String): Flow<Int>

    @Query(
        """
        select count(*)
        from pending_mutations
        where household_id = :householdId
          and conflict = 1
        """
    )
    fun observeConflictCount(
        householdId: String
    ): Flow<Int>

    @Query(
        """
        select *
        from pending_mutations
        where household_id = :householdId
          and conflict = 1
        order by priority asc, updated_at_epoch_ms asc
        """
    )
    suspend fun conflicts(
        householdId: String
    ): List<PendingMutationEntity>

    @Query(
        """
        update pending_mutations
        set conflict = 1,
            last_error = :message
        where id = :id
          and revision = :revision
        """
    )
    override suspend fun markConflict(
        id: String,
        revision: String,
        message: String?
    ): Int

    @Query(
        """
        update pending_mutations
        set conflict = 0,
            base_snapshot_payload = null,
            attempt_count = 0,
            last_error = null
        where household_id = :householdId
          and conflict = 1
        """
    )
    suspend fun forceConflicts(
        householdId: String
    ): Int

    @Query(
        """
        delete from pending_mutations
        where household_id = :householdId
          and conflict = 1
        """
    )
    suspend fun deleteConflicts(
        householdId: String
    ): Int

    @Query(
        """
        select count(*) > 0
        from pending_mutations
        where household_id = :householdId
          and (domain_mask & :domainMask) != 0
        """
    )
    suspend fun hasDomain(
        householdId: String,
        domainMask: Int
    ): Boolean
}

@Serializable
internal sealed interface QueuedMutation {
    @Serializable
    @SerialName("upsert_ingredient")
    data class UpsertIngredient(
        val ingredient: Ingredient
    ) : QueuedMutation

    @Serializable
    @SerialName("delete_ingredient")
    data class DeleteIngredient(
        val ingredientId: String,
        val imagePath: String?
    ) : QueuedMutation

    @Serializable
    @SerialName("upsert_ingredient_category")
    data class UpsertIngredientCategory(
        val category: IngredientCategory
    ) : QueuedMutation

    @Serializable
    @SerialName("upsert_recipe")
    data class UpsertRecipe(
        val recipe: Recipe
    ) : QueuedMutation

    @Serializable
    @SerialName("delete_recipe")
    data class DeleteRecipe(
        val recipeId: String,
        val imagePath: String?
    ) : QueuedMutation

    @Serializable
    @SerialName("upsert_recipe_category")
    data class UpsertRecipeCategory(
        val category: RecipeCategoryOption
    ) : QueuedMutation

    @Serializable
    @SerialName("set_pantry")
    data class SetPantry(
        val ingredientId: String,
        val amountBase: Double
    ) : QueuedMutation

    @Serializable
    @SerialName("set_week_state")
    data class SetWeekState(
        val weeklyPlanId: String,
        val plan: Map<String, String?>,
        val dayModes: Map<String, String>,
        val lockedDays: List<String>,
        val sandwichRecipeId: String?,
        val sandwichDay: String?
    ) : QueuedMutation

    @Serializable
    @SerialName("set_household_sandwich")
    data class SetHouseholdSandwich(
        val recipeId: String?
    ) : QueuedMutation

    @Serializable
    @SerialName("set_day_prep")
    data class SetDayPrep(
        val weeklyPlanId: String,
        val day: String,
        val maxPrepMinutes: Int?
    ) : QueuedMutation

    @Serializable
    @SerialName("replace_lunch")
    data class ReplaceLunch(
        val weeklyPlanId: String,
        val recipeIds: List<String>
    ) : QueuedMutation

    @Serializable
    @SerialName("shopping_included")
    data class SetShoppingIncluded(
        val weeklyPlanId: String,
        val ingredientId: String,
        val included: Boolean
    ) : QueuedMutation

    @Serializable
    @SerialName("shopping_checked")
    data class SetShoppingChecked(
        val weeklyPlanId: String,
        val ingredientId: String,
        val checked: Boolean
    ) : QueuedMutation

    @Serializable
    @SerialName("clear_shopping")
    data class ClearShopping(
        val weeklyPlanId: String
    ) : QueuedMutation
}

internal object QueuedMutationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "mutation_type"
    }

    fun encode(mutation: QueuedMutation): String =
        json.encodeToString<QueuedMutation>(mutation)

    fun decode(payload: String): QueuedMutation =
        json.decodeFromString<QueuedMutation>(payload)
}

internal fun QueuedMutation.key(): String = when (this) {
    is QueuedMutation.UpsertIngredient ->
        "ingredient:${ingredient.id}"
    is QueuedMutation.DeleteIngredient ->
        "ingredient:$ingredientId"
    is QueuedMutation.UpsertIngredientCategory ->
        "ingredient-category:${category.id}"
    is QueuedMutation.UpsertRecipe ->
        "recipe:${recipe.id}"
    is QueuedMutation.DeleteRecipe ->
        "recipe:$recipeId"
    is QueuedMutation.UpsertRecipeCategory ->
        "recipe-category:${category.id}"
    is QueuedMutation.SetPantry ->
        "pantry:$ingredientId"
    is QueuedMutation.SetWeekState ->
        "week:$weeklyPlanId"
    is QueuedMutation.SetHouseholdSandwich ->
        "household-sandwich"
    is QueuedMutation.SetDayPrep ->
        "day-prep:$weeklyPlanId:$day"
    is QueuedMutation.ReplaceLunch ->
        "lunch:$weeklyPlanId"
    is QueuedMutation.SetShoppingIncluded ->
        "shopping-included:$weeklyPlanId:$ingredientId"
    is QueuedMutation.SetShoppingChecked ->
        "shopping-checked:$weeklyPlanId:$ingredientId"
    is QueuedMutation.ClearShopping ->
        "shopping-clear:$weeklyPlanId"
}

internal fun QueuedMutation.priority(): Int = when (this) {
    is QueuedMutation.UpsertIngredientCategory,
    is QueuedMutation.UpsertRecipeCategory -> 10

    is QueuedMutation.UpsertIngredient -> 20
    is QueuedMutation.UpsertRecipe -> 30

    is QueuedMutation.SetPantry,
    is QueuedMutation.SetWeekState,
    is QueuedMutation.SetHouseholdSandwich,
    is QueuedMutation.SetDayPrep,
    is QueuedMutation.ReplaceLunch -> 40

    is QueuedMutation.ClearShopping,
    is QueuedMutation.SetShoppingIncluded,
    is QueuedMutation.SetShoppingChecked -> 50

    is QueuedMutation.DeleteRecipe -> 60
    is QueuedMutation.DeleteIngredient -> 70
}

private fun domainMask(vararg domains: CloudSyncDomain): Int =
    domains.fold(0) { acc, domain ->
        acc or (1 shl domain.ordinal)
    }

internal fun QueuedMutation.domains(): Set<CloudSyncDomain> = when (this) {
    is QueuedMutation.UpsertIngredient,
    is QueuedMutation.UpsertIngredientCategory ->
        setOf(CloudSyncDomain.INGREDIENTS)

    is QueuedMutation.DeleteIngredient ->
        setOf(
            CloudSyncDomain.INGREDIENTS,
            CloudSyncDomain.PANTRY,
            CloudSyncDomain.SHOPPING
        )

    is QueuedMutation.UpsertRecipe,
    is QueuedMutation.DeleteRecipe,
    is QueuedMutation.UpsertRecipeCategory ->
        setOf(CloudSyncDomain.RECIPES)

    is QueuedMutation.SetPantry ->
        setOf(CloudSyncDomain.PANTRY)

    is QueuedMutation.SetWeekState,
    is QueuedMutation.SetHouseholdSandwich,
    is QueuedMutation.SetDayPrep,
    is QueuedMutation.ReplaceLunch ->
        setOf(CloudSyncDomain.WEEK)

    is QueuedMutation.ClearShopping,
    is QueuedMutation.SetShoppingIncluded,
    is QueuedMutation.SetShoppingChecked ->
        setOf(CloudSyncDomain.SHOPPING)
}

internal fun QueuedMutation.domainMask(): Int = when (this) {
    is QueuedMutation.UpsertIngredient,
    is QueuedMutation.UpsertIngredientCategory ->
        domainMask(CloudSyncDomain.INGREDIENTS)

    is QueuedMutation.DeleteIngredient ->
        domainMask(
            CloudSyncDomain.INGREDIENTS,
            CloudSyncDomain.PANTRY,
            CloudSyncDomain.SHOPPING
        )

    is QueuedMutation.UpsertRecipe,
    is QueuedMutation.DeleteRecipe,
    is QueuedMutation.UpsertRecipeCategory ->
        domainMask(CloudSyncDomain.RECIPES)

    is QueuedMutation.SetPantry ->
        domainMask(CloudSyncDomain.PANTRY)

    is QueuedMutation.SetWeekState,
    is QueuedMutation.SetHouseholdSandwich,
    is QueuedMutation.SetDayPrep,
    is QueuedMutation.ReplaceLunch ->
        domainMask(CloudSyncDomain.WEEK)

    is QueuedMutation.ClearShopping,
    is QueuedMutation.SetShoppingIncluded,
    is QueuedMutation.SetShoppingChecked ->
        domainMask(CloudSyncDomain.SHOPPING)
}

internal val OFFLINE_SYNC_WORK_POLICY =
    ExistingWorkPolicy.APPEND_OR_REPLACE

internal class OfflineSyncQueue(
    private val context: Context,
    private val householdId: String
) {
    private val database =
        MealPlannerDatabaseProvider.get(context)
    private val dao = database.pendingMutationDao()

    fun observePendingCount(): Flow<Int> =
        dao.observeCount(householdId)

    fun observeFailedCount(): Flow<Int> =
        dao.observeFailedCount(householdId)

    fun observeConflictCount(): Flow<Int> =
        dao.observeConflictCount(householdId)

    suspend fun hasPendingMutations(): Boolean =
        dao.count(householdId) > 0

    suspend fun hasPending(
        domain: CloudSyncDomain
    ): Boolean =
        dao.hasDomain(
            householdId = householdId,
            domainMask = domainMask(domain)
        )

    suspend fun enqueue(
        snapshot: CloudSnapshot,
        mutation: QueuedMutation
    ) {
        enqueue(snapshot, listOf(mutation))
    }

    suspend fun enqueue(
        snapshot: CloudSnapshot,
        mutations: List<QueuedMutation>
    ) {
        if (mutations.isEmpty()) return

        val now = System.currentTimeMillis()
        val previousSnapshotPayload =
            database.householdSnapshotDao()
                .find(householdId)
                ?.payload

        val entities = mutations.mapIndexed { index, mutation ->
            val key = mutation.key()
            val id = "$householdId|$key"
            val existing = dao.find(id)

            PendingMutationEntity(
                id = id,
                revision = UUID.randomUUID().toString(),
                householdId = householdId,
                mutationKey = key,
                priority = mutation.priority(),
                domainMask = mutation.domainMask(),
                payload = QueuedMutationCodec.encode(mutation),
                baseSnapshotPayload =
                    existing?.baseSnapshotPayload
                        ?: previousSnapshotPayload,
                updatedAtEpochMs = now + index
            )
        }

        database.localStateDao()
            .persistSnapshotAndMutations(
                snapshot = HouseholdSnapshotEntity(
                    householdId = householdId,
                    payload = RoomSnapshotCodec.encode(snapshot),
                    updatedAtEpochMs = now
                ),
                mutations = entities
            )

        schedule()
    }

    suspend fun forceConflicts() {
        dao.forceConflicts(householdId)
        schedule()
    }

    suspend fun discardConflicts(): Set<CloudSyncDomain> {
        val domains = dao.conflicts(householdId)
            .flatMap { entity ->
                CloudSyncDomain.entries.filter { domain ->
                    entity.domainMask and
                        (1 shl domain.ordinal) != 0
                }
            }
            .toSet()

        dao.deleteConflicts(householdId)
        return domains
    }

    fun schedule() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(
                        OfflineSyncWorker.HOUSEHOLD_ID,
                        householdId
                    )
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "mealplanner-sync-$householdId",
            OFFLINE_SYNC_WORK_POLICY,
            request
        )
    }
}

internal interface PendingMutationStore {
    suspend fun next(
        householdId: String
    ): PendingMutationEntity?

    suspend fun deleteIfCurrent(
        id: String,
        revision: String
    ): Int

    suspend fun markFailure(
        id: String,
        revision: String,
        message: String?
    ): Int

    suspend fun markConflict(
        id: String,
        revision: String,
        message: String?
    ): Int
}

internal enum class OfflineSyncDrainResult {
    EMPTY,
    RETRY
}

internal class OfflineSyncDrain(
    private val store: PendingMutationStore,
    private val hasConflict: suspend (
        PendingMutationEntity,
        QueuedMutation
    ) -> Boolean = { _, _ -> false },
    private val applyMutation: suspend (QueuedMutation) -> Unit
) {
    suspend fun drain(
        householdId: String
    ): OfflineSyncDrainResult {
        while (true) {
            val pending = store.next(householdId)
                ?: return OfflineSyncDrainResult.EMPTY

            try {
                val mutation = QueuedMutationCodec.decode(
                    pending.payload
                )

                if (hasConflict(pending, mutation)) {
                    store.markConflict(
                        id = pending.id,
                        revision = pending.revision,
                        message = "Ændret på en anden enhed"
                    )
                    continue
                }

                applyMutation(mutation)
                store.deleteIfCurrent(
                    id = pending.id,
                    revision = pending.revision
                )
            } catch (e: Exception) {
                store.markFailure(
                    id = pending.id,
                    revision = pending.revision,
                    message = e.message
                )
                return OfflineSyncDrainResult.RETRY
            }
        }
    }
}

internal class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val householdId =
            inputData.getString(HOUSEHOLD_ID)
                ?: return Result.failure()

        val status = SupabaseProvider.client.auth.sessionStatus.first {
            it != SessionStatus.Initializing
        }
        if (status !is SessionStatus.Authenticated) {
            return Result.success()
        }

        val database =
            MealPlannerDatabaseProvider.get(applicationContext)
        val dao = database.pendingMutationDao()
        val repository = CloudMealRepository(
            context = applicationContext,
            householdId = householdId
        )

        return when (
            OfflineSyncDrain(
                store = dao,
                hasConflict = { pending, mutation ->
                    pending.baseSnapshotPayload
                        ?.let(RoomSnapshotCodec::decode)
                        ?.let { baseSnapshot ->
                            mutation.hasRemoteConflict(
                                repository = repository,
                                baseSnapshot = baseSnapshot
                            )
                        }
                        ?: false
                },
                applyMutation = { mutation ->
                    applyQueuedMutation(
                        repository = repository,
                        mutation = mutation
                    )
                }
            ).drain(householdId)
        ) {
            OfflineSyncDrainResult.EMPTY ->
                Result.success()

            OfflineSyncDrainResult.RETRY ->
                Result.retry()
        }
    }

    companion object {
        const val HOUSEHOLD_ID =
            "mealplanner_household_id"
    }
}

private fun Ingredient.conflictComparable(): Ingredient =
    copy(imageUri = null)

private fun Recipe.conflictComparable(): Recipe =
    copy(imageUri = null)

internal suspend fun QueuedMutation.hasRemoteConflict(
    repository: CloudMealRepository,
    baseSnapshot: CloudSnapshot
): Boolean =
    when (this) {
        is QueuedMutation.UpsertIngredient -> {
            val remote = repository.loadIngredientsSnapshot()
            baseSnapshot.ingredients
                .find { it.id == ingredient.id }
                ?.conflictComparable() !=
                remote.ingredients
                    .find { it.id == ingredient.id }
                    ?.conflictComparable()
        }

        is QueuedMutation.DeleteIngredient -> {
            val remote = repository.loadIngredientsSnapshot()
            baseSnapshot.ingredients
                .find { it.id == ingredientId }
                ?.conflictComparable() !=
                remote.ingredients
                    .find { it.id == ingredientId }
                    ?.conflictComparable()
        }

        is QueuedMutation.UpsertIngredientCategory -> {
            val remote = repository.loadIngredientsSnapshot()
            baseSnapshot.ingredientCategories
                .find { it.id == category.id } !=
                remote.ingredientCategories
                    .find { it.id == category.id }
        }

        is QueuedMutation.UpsertRecipe -> {
            val remote = repository.loadRecipesSnapshot()
            baseSnapshot.recipes
                .find { it.id == recipe.id }
                ?.conflictComparable() !=
                remote.recipes
                    .find { it.id == recipe.id }
                    ?.conflictComparable()
        }

        is QueuedMutation.DeleteRecipe -> {
            val remote = repository.loadRecipesSnapshot()
            baseSnapshot.recipes
                .find { it.id == recipeId }
                ?.conflictComparable() !=
                remote.recipes
                    .find { it.id == recipeId }
                    ?.conflictComparable()
        }

        is QueuedMutation.UpsertRecipeCategory -> {
            val remote = repository.loadRecipesSnapshot()
            baseSnapshot.recipeCategories
                .find { it.id == category.id } !=
                remote.recipeCategories
                    .find { it.id == category.id }
        }

        is QueuedMutation.SetPantry -> {
            val remote = repository.loadPantrySnapshot()
            baseSnapshot.pantry[ingredientId] !=
                remote[ingredientId]
        }

        is QueuedMutation.SetWeekState -> {
            val remote = repository.loadWeekSnapshot()
            baseSnapshot.plan != remote.plan ||
                baseSnapshot.dayModes != remote.dayModes ||
                baseSnapshot.lockedDays != remote.lockedDays ||
                baseSnapshot.weeklySandwichRecipeId !=
                    remote.weeklySandwichRecipeId ||
                baseSnapshot.sandwichDay != remote.sandwichDay
        }

        is QueuedMutation.SetDayPrep -> {
            val remote = repository.loadWeekSnapshot()
            val targetDay = DayOfWeek.valueOf(day)
            baseSnapshot.dayMaxPrepMinutes[targetDay] !=
                remote.dayMaxPrepMinutes[targetDay]
        }

        is QueuedMutation.ReplaceLunch -> {
            val remote = repository.loadWeekSnapshot()
            baseSnapshot.selectedLunchRecipeIds !=
                remote.selectedLunchRecipeIds
        }

        is QueuedMutation.SetHouseholdSandwich,
        is QueuedMutation.SetShoppingIncluded,
        is QueuedMutation.SetShoppingChecked,
        is QueuedMutation.ClearShopping ->
            false
    }

private suspend fun applyQueuedMutation(
    repository: CloudMealRepository,
    mutation: QueuedMutation
) {
    when (mutation) {
        is QueuedMutation.UpsertIngredient -> {
            repository.upsertIngredient(mutation.ingredient)
        }

        is QueuedMutation.DeleteIngredient -> {
            repository.deleteIngredient(
                id = mutation.ingredientId,
                imagePath = mutation.imagePath
            )
        }

        is QueuedMutation.UpsertIngredientCategory -> {
            repository.upsertIngredientCategory(
                mutation.category
            )
        }

        is QueuedMutation.UpsertRecipe -> {
            repository.upsertRecipe(mutation.recipe)
        }

        is QueuedMutation.DeleteRecipe -> {
            repository.deleteRecipe(
                id = mutation.recipeId,
                imagePath = mutation.imagePath
            )
        }

        is QueuedMutation.UpsertRecipeCategory -> {
            repository.upsertRecipeCategory(
                mutation.category
            )
        }

        is QueuedMutation.SetPantry -> {
            repository.setPantry(
                ingredientId = mutation.ingredientId,
                amountBase = mutation.amountBase
            )
        }

        is QueuedMutation.SetWeekState -> {
            repository.saveWeekState(
                weeklyPlanId = mutation.weeklyPlanId,
                plan = mutation.plan.mapKeys {
                    DayOfWeek.valueOf(it.key)
                },
                dayModes = mutation.dayModes
                    .mapKeys { DayOfWeek.valueOf(it.key) }
                    .mapValues { DayMode.valueOf(it.value) },
                lockedDays = mutation.lockedDays
                    .mapTo(mutableSetOf()) {
                        DayOfWeek.valueOf(it)
                    },
                sandwichRecipeId =
                    mutation.sandwichRecipeId,
                sandwichDay = mutation.sandwichDay?.let(
                    DayOfWeek::valueOf
                )
            )
        }

        is QueuedMutation.SetHouseholdSandwich -> {
            repository.setHouseholdSandwich(
                mutation.recipeId
            )
        }

        is QueuedMutation.SetDayPrep -> {
            repository.setDayPrepLimit(
                weeklyPlanId = mutation.weeklyPlanId,
                day = DayOfWeek.valueOf(mutation.day),
                maxPrepMinutes = mutation.maxPrepMinutes
            )
        }

        is QueuedMutation.ReplaceLunch -> {
            repository.replaceLunchRecipes(
                weeklyPlanId = mutation.weeklyPlanId,
                recipeIds = mutation.recipeIds
            )
        }

        is QueuedMutation.SetShoppingIncluded -> {
            repository.setShoppingIncluded(
                weeklyPlanId = mutation.weeklyPlanId,
                ingredientId = mutation.ingredientId,
                included = mutation.included
            )
            if (!mutation.included) {
                repository.setShoppingChecked(
                    weeklyPlanId = mutation.weeklyPlanId,
                    ingredientId = mutation.ingredientId,
                    checked = false
                )
            }
        }

        is QueuedMutation.SetShoppingChecked -> {
            repository.setShoppingChecked(
                weeklyPlanId = mutation.weeklyPlanId,
                ingredientId = mutation.ingredientId,
                checked = mutation.checked
            )
        }

        is QueuedMutation.ClearShopping -> {
            repository.clearShoppingChecks(
                mutation.weeklyPlanId
            )
        }
    }
}
