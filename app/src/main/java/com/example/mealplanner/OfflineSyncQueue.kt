package com.example.mealplanner

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity(
    tableName = "pending_mutations",
    indices = [
        Index("household_id"),
        Index("batch_id")
    ]
)
internal data class PendingMutationEntity(
    @PrimaryKey
    val id: String,
    val revision: String,
    @ColumnInfo(name = "batch_id")
    val batchId: String = "single",
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
          and "conflict" = 0
        order by priority asc, updated_at_epoch_ms asc, id asc
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
        select *
        from pending_mutations
        where batch_id = :batchId
          and "conflict" = 0
        order by priority asc, updated_at_epoch_ms asc, id asc
        """
    )
    override suspend fun batch(
        batchId: String
    ): List<PendingMutationEntity>

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
          and "conflict" = 0
        """
    )
    fun observeFailedCount(householdId: String): Flow<Int>

    @Query(
        """
        select count(distinct batch_id)
        from pending_mutations
        where household_id = :householdId
          and "conflict" = 1
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
          and "conflict" = 1
        order by priority asc, updated_at_epoch_ms asc
        """
    )
    suspend fun conflicts(
        householdId: String
    ): List<PendingMutationEntity>

    @Query(
        """
        update pending_mutations
        set "conflict" = 1,
            last_error = :message
        where batch_id = :batchId
        """
    )
    override suspend fun markConflict(
        batchId: String,
        message: String?
    ): Int

    @Query(
        """
        update pending_mutations
        set base_snapshot_payload = :baseSnapshotPayload,
            "conflict" = 0,
            attempt_count = 0,
            last_error = null
        where id = :id
        """
    )
    override suspend fun rebaseLatest(
        id: String,
        baseSnapshotPayload: String
    ): Int

    @Query(
        """
        update pending_mutations
        set "conflict" = 0,
            base_snapshot_payload = null,
            attempt_count = 0,
            last_error = null
        where household_id = :householdId
          and "conflict" = 1
        """
    )
    suspend fun forceConflicts(
        householdId: String
    ): Int

    @Query(
        """
        delete from pending_mutations
        where household_id = :householdId
          and "conflict" = 1
        """
    )
    suspend fun deleteConflicts(
        householdId: String
    ): Int

    @Query(
        """
        update pending_mutations
        set attempt_count = 0,
            last_error = null
        where household_id = :householdId
        """
    )
    suspend fun resetAttempts(
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
    @SerialName("delete_recipe_category")
    data class DeleteRecipeCategory(
        val categoryId: String
    ) : QueuedMutation

    @Serializable
    @SerialName("delete_ingredient_category")
    data class DeleteIngredientCategory(
        val categoryId: String
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

    @Serializable
    @SerialName("set_meal_eaten")
    data class SetMealEaten(
        val eatenOn: String,
        val recipeId: String?
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

    is QueuedMutation.DeleteIngredientCategory ->
        "ingredient-category:$categoryId"

    is QueuedMutation.UpsertRecipe ->
        "recipe:${recipe.id}"

    is QueuedMutation.DeleteRecipe ->
        "recipe:$recipeId"

    is QueuedMutation.UpsertRecipeCategory ->
        "recipe-category:${category.id}"

    is QueuedMutation.DeleteRecipeCategory ->
        "recipe-category:$categoryId"

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

    is QueuedMutation.SetMealEaten ->
        "meal-eaten:$eatenOn"
}

internal fun QueuedMutation.priority(): Int = when (this) {
    is QueuedMutation.UpsertIngredientCategory,
    is QueuedMutation.DeleteIngredientCategory,
    is QueuedMutation.UpsertRecipeCategory,
    is QueuedMutation.DeleteRecipeCategory -> 10

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

    is QueuedMutation.SetMealEaten -> 55

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

    is QueuedMutation.DeleteIngredientCategory ->
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

    is QueuedMutation.DeleteRecipeCategory ->
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

    is QueuedMutation.SetMealEaten ->
        setOf(CloudSyncDomain.HISTORY)
}

internal fun QueuedMutation.domainMask(): Int = when (this) {
    is QueuedMutation.UpsertIngredient,
    is QueuedMutation.UpsertIngredientCategory ->
        domainMask(CloudSyncDomain.INGREDIENTS)

    is QueuedMutation.DeleteIngredientCategory ->
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

    is QueuedMutation.DeleteRecipeCategory ->
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

    is QueuedMutation.SetMealEaten ->
        domainMask(CloudSyncDomain.HISTORY)
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
        mutations: List<QueuedMutation>
    ) {
        if (mutations.isEmpty()) return

        val now = System.currentTimeMillis()
        val previousSnapshotPayload =
            database.householdSnapshotDao()
                .find(householdId)
                ?.payload

        val keyedMutations = mutations.map { mutation ->
            val key = mutation.key()
            val id = "$householdId|$key"
            Triple(mutation, id, dao.find(id))
        }
        val batchId = keyedMutations
            .firstNotNullOfOrNull { (_, _, existing) ->
                existing?.batchId
            }
            ?: UUID.randomUUID().toString()
        val batchHasConflict =
            keyedMutations.any { (_, _, existing) ->
                existing?.conflict == true
            }

        val entities = keyedMutations.mapIndexed { index,
                                                   (mutation, id, existing) ->
            PendingMutationEntity(
                id = id,
                revision = UUID.randomUUID().toString(),
                batchId = batchId,
                householdId = householdId,
                mutationKey = mutation.key(),
                priority = mutation.priority(),
                domainMask = mutation.domainMask(),
                payload = QueuedMutationCodec.encode(mutation),
                baseSnapshotPayload =
                    existing?.baseSnapshotPayload
                        ?: previousSnapshotPayload,
                updatedAtEpochMs = now + index,
                conflict = batchHasConflict,
                lastError = if (batchHasConflict) {
                    existing?.lastError
                        ?: "Afventer konfliktvalg"
                } else {
                    null
                }
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

    suspend fun resetAttempts() {
        dao.resetAttempts(householdId)
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

    suspend fun drainInApp(repository: CloudMealRepository): OfflineSyncDrainResult =
        withContext(Dispatchers.IO) {
            val dao = database.pendingMutationDao()

            OfflineSyncDrain(
                store = dao,
                hasConflict = { pending, _ ->
                    pending.conflict
                },
                onSuperseded = { mutationId ->
                    val currentRemote = repository.loadCurrent()
                    dao.rebaseLatest(
                        id = mutationId,
                        baseSnapshotPayload = RoomSnapshotCodec.encode(currentRemote)
                    )
                },
                applyMutation = { mutation ->
                    applyQueuedMutation(
                        repository = repository,
                        mutation = mutation
                    )
                }
            ).drain(householdId)
        }
}

internal interface PendingMutationStore {
    suspend fun next(
        householdId: String
    ): PendingMutationEntity?

    suspend fun batch(
        batchId: String
    ): List<PendingMutationEntity>

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
        batchId: String,
        message: String?
    ): Int

    suspend fun rebaseLatest(
        id: String,
        baseSnapshotPayload: String
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
    private val onSuperseded: suspend (String) -> Unit = {},
    private val applyMutation: suspend (QueuedMutation) -> Unit
) {
    suspend fun drain(
        householdId: String
    ): OfflineSyncDrainResult {
        while (true) {
            val first = store.next(householdId)
                ?: return OfflineSyncDrainResult.EMPTY
            val batch = store.batch(first.batchId)
                .ifEmpty { listOf(first) }

            val decoded = try {
                batch.map { pending ->
                    pending to QueuedMutationCodec.decode(
                        pending.payload
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.markFailure(
                    id = first.id,
                    revision = first.revision,
                    message = e.message
                )
                return OfflineSyncDrainResult.RETRY
            }

            val conflicts = try {
                decoded.any { (pending, mutation) ->
                    hasConflict(pending, mutation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.markFailure(
                    id = first.id,
                    revision = first.revision,
                    message = e.message
                )
                return OfflineSyncDrainResult.RETRY
            }

            if (conflicts) {
                store.markConflict(
                    batchId = first.batchId,
                    message = "Ændret på en anden enhed"
                )
                continue
            }

            for ((pending, mutation) in decoded) {
                try {
                    applyMutation(mutation)
                    val deleted = store.deleteIfCurrent(
                        id = pending.id,
                        revision = pending.revision
                    )

                    if (deleted == 0) {
                        onSuperseded(pending.id)
                    }
                } catch (e: CancellationException) {
                    throw e
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
}

internal class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val householdId =
            inputData.getString(HOUSEHOLD_ID)
                ?: return Result.failure()

        // Legacy local-only sessions may have left WorkManager requests from
        // older app versions. Never try to upload their fake household IDs,
        // even if the user later signs in to a real cloud account.
        if (householdId == "local-household-1") return Result.success()

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
                hasConflict = { pending, _ ->
                    pending.conflict
                },
                onSuperseded = { mutationId ->
                    val currentRemote =
                        repository.loadCurrent(
                        )
                    dao.rebaseLatest(
                        id = mutationId,
                        baseSnapshotPayload =
                            RoomSnapshotCodec.encode(
                                currentRemote
                            )
                    )
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

        is QueuedMutation.DeleteRecipeCategory -> {
            repository.deleteRecipeCategory(
                mutation.categoryId
            )
        }

        is QueuedMutation.DeleteIngredientCategory -> {
            repository.deleteShoppingCategory(
                mutation.categoryId
            )
        }

        is QueuedMutation.SetMealEaten -> {
            repository.setMealEaten(
                eatenOn = java.time.LocalDate.parse(mutation.eatenOn),
                recipeId = mutation.recipeId
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
