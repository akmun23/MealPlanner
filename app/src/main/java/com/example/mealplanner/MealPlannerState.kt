package com.example.mealplanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class MealPlannerState(
    context: Context,
    private val householdId: String,
    parentScope: CoroutineScope
) {
    // Keep household work isolated from other UI and household scopes.
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job])
    )
    private val isLocalOnly = householdId == "local-household-1"
    val isLocalMode: Boolean get() = isLocalOnly
    val householdIdentifier: String get() = householdId
    private val repository = CloudMealRepository(
        context = context.applicationContext,
        householdId = householdId
    )
    private val roomCache = RoomSnapshotCache(
        context = context.applicationContext,
        householdId = householdId
    )
    private val offlineSyncQueue = OfflineSyncQueue(
        context = context.applicationContext,
        householdId = householdId
    )
    private val plannerSettingsStore = PlannerSettingsStore(
        context.applicationContext
    )
    private val networkMonitor = NetworkConnectivityMonitor(context.applicationContext)
    private val reconnectMutex = Mutex()
    private val reloadMutex = Mutex()
    private val mutationQueueMutex = Mutex()
    private var cachePersistJob: Job? = null
    private val syncTracker = SyncGenerationTracker()
    private var syncStatusClearJob: Job? = null
    private val amountFormatter = NumberFormat
        .getNumberInstance(Locale.forLanguageTag("da-DK"))
        .apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
            isGroupingUsed = false
        }

    val ingredients = mutableStateListOf<Ingredient>()
    val ingredientCategories = mutableStateListOf<IngredientCategory>()
    val recipeCategories = mutableStateListOf<RecipeCategoryOption>()
    val recipes = mutableStateListOf<Recipe>()
    val pantry = mutableStateMapOf<String, Double>()
    val dayModes = mutableStateMapOf<DayOfWeek, DayMode>()
    val dayMaxPrepMinutes = mutableStateMapOf<DayOfWeek, Int?>()
    val plan = mutableStateMapOf<DayOfWeek, String?>()
    val lockedDays = mutableStateSetOf<DayOfWeek>()
    val shoppingChecked = mutableStateMapOf<String, Boolean>()
    val shoppingIncluded = mutableStateMapOf<String, Boolean>()
    val selectedLunchRecipeIds = mutableStateListOf<String>()
    val history = mutableStateListOf<MealHistoryEntry>()

    // Explicit unlocks are session-scoped: completed meals are locked again
    // when the user reopens the app or navigates to another week.
    private val editableCompletedDates = mutableStateSetOf<LocalDate>()
    private val unlockedRegenerationWeeks = mutableStateSetOf<LocalDate>()

    var weeklySandwichRecipeId by mutableStateOf<String?>(null)
        private set

    var sandwichDay by mutableStateOf<DayOfWeek?>(null)
        private set

    var weeklyPlanId by mutableStateOf<String?>(null)
        private set

    var displayedWeekStart by mutableStateOf(currentWeekStart())
        private set

    var isWeekChanging by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(true)
        private set

    var isPlanning by mutableStateOf(false)
        private set

    var plannerWeights by mutableStateOf(
        plannerSettingsStore.load(householdId)
    )
        private set

    fun updatePlannerWeights(weights: PlannerWeights) {
        plannerSettingsStore.save(householdId, weights)
        plannerWeights = weights
    }

    fun resetPlannerWeights() {
        plannerSettingsStore.reset(householdId)
        plannerWeights = PlannerWeights()
    }

    /**
     * Export the latest visible state, including changes not yet synchronized
     * to the cloud. No auth/session secrets are included.
     */
    suspend fun exportPortableBackup(): String {
        val snapshot = currentSnapshot()
            ?: error("The household has not finished loading")
        return withContext(Dispatchers.Default) {
            SnapshotBackupCodec.encode(snapshot)
        }
    }

    /**
     * Restore backups only into an explicitly local-only household. Importing
     * directly into a shared cloud household would bypass conflict tracking
     * and overwrite other people's edits.
     */
    suspend fun restoreLocalBackup(payload: String) {
        require(isLocalOnly) { "Import requires local-only mode" }
        val restored = withContext(Dispatchers.Default) {
            SnapshotBackupCodec.decode(payload)
        }.copy(
            weeklyPlanId = "local-week-${currentWeekStart()}"
        )
        cachePersistJob?.cancelAndJoin()
        mutationQueueMutex.withLock {
            // Persist first: the UI remains unchanged if disk persistence
            // fails, and the restored data survives process termination.
            withContext(Dispatchers.IO) {
                roomCache.save(restored)
            }
            applySnapshot(restored)
            syncTracker.markLocalChange(CloudSyncDomain.entries.toSet())
            syncIndicator = SyncIndicator.SAVED
        }
    }

    var lastError by mutableStateOf<String?>(null)
        private set

    internal var syncIndicator by mutableStateOf(SyncIndicator.IDLE)
        private set

    internal var syncConflictCount by mutableIntStateOf(0)
        private set

    internal var pendingSyncCount by mutableIntStateOf(0)
        private set

    internal var failedSyncCount by mutableIntStateOf(0)
        private set

    init {
        orderedWeekDays.forEach { day ->
            dayModes[day] = DayMode.AUTO
            dayMaxPrepMinutes[day] = null
            plan[day] = null
        }

        if (!isLocalOnly) scope.launch {
            observeSyncQueue()
        }

        val initialLoad = scope.launch {
            initialize()
        }
        scope.launch {
            initialLoad.join()
            // Cache images for the other tabs without composing those screens
            // and without blocking the first household frame.
            delay(2_500L.milliseconds)
            val recipesById = recipes.associateBy { it.id }
            val imageReferences = buildList {
                orderedWeekDays.mapNotNull { plan[it] }.forEach { id ->
                    recipesById[id]?.imageUri?.let(::add)
                }
                recipes.forEach { it.imageUri?.let(::add) }
                ingredients.forEach { it.imageUri?.let(::add) }
            }
            MealImagePrefetcher.warm(context.applicationContext, imageReferences)
        }
        if (!isLocalOnly) scope.launch {
            // During initial load, initialize() already attempts a refresh.
            // Start listening once it has finished, then react to later
            // offline-to-online transitions without polling.
            initialLoad.join()
            networkMonitor.connected.drop(1).collect { online ->
                if (online) {
                    try {
                        reconnectAndRefresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        syncIndicator = SyncIndicator.OFFLINE
                    }
                } else if (!offlineSyncQueue.hasPendingMutations()) {
                    syncIndicator = SyncIndicator.OFFLINE
                }
            }
        }
    }

    /** Refresh cloud data on demand; pending local edits must sync first. */
    fun refreshNow() {
        if (isLocalOnly) return
        scope.launch {
            try {
                reconnectAndRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                syncIndicator = if (offlineSyncQueue.hasPendingMutations()) {
                    SyncIndicator.PENDING
                } else {
                    SyncIndicator.OFFLINE
                }
                lastError = "Kunne ikke hente nyeste data. De lokale data er stadig tilgængelige."
            }
        }
    }

    private suspend fun reconnectAndRefresh() = reconnectMutex.withLock {
        mutationQueueMutex.withLock {
            if (offlineSyncQueue.hasPendingMutations()) {
                offlineSyncQueue.drainInApp(repository)
            }
        }
        // A full reload is only safe after all optimistic edits have been sent.
        if (!offlineSyncQueue.hasPendingMutations()) {
            reload()
        }
        repository.startRealtime(scope) { event ->
            handleRealtime(event)
        }
        if (
            syncIndicator == SyncIndicator.OFFLINE &&
            !offlineSyncQueue.hasPendingMutations()
        ) {
            syncIndicator = SyncIndicator.IDLE
        }
    }

    private suspend fun initialize() {
        var hasCachedSnapshot = false

        // The Room query itself suspends, but JSON decoding and
        // projection hydration must also run off the main thread.
        withContext(Dispatchers.IO) {
            runCatching { roomCache.load() }.getOrNull()
        }?.let { cached ->
                applySnapshot(cached)
                hasCachedSnapshot = true
                isLoading = false
            }

        if (isLocalOnly) {
            // No credentials or cloud household exist in local-only mode.
            // Use a fresh local week ID after rollover and never schedule
            // mutations for a household that cannot authenticate to Supabase.
            if (!hasCachedSnapshot || weeklyPlanId == null) {
                weeklyPlanId = "local-week-${currentWeekStart()}"
            }
            try {
                roomCache.save(currentSnapshot()!!)
            } catch (_: Exception) {
                lastError = "Den lokale madplan kunne ikke gemmes."
            }
            isLoading = false
            syncIndicator = SyncIndicator.IDLE
            return
        }

        val hasPendingMutations =
            runCatching {
                offlineSyncQueue.hasPendingMutations()
            }.getOrDefault(false)

        if (hasPendingMutations) {
            try {
                mutationQueueMutex.withLock {
                    offlineSyncQueue.drainInApp(repository)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Local outbox entries remain durable for the next reconnect.
            }
        }

        try {
            val stillPending = offlineSyncQueue.hasPendingMutations()
            if (!stillPending) {
                // Even if there were edits when the app started, a successful
                // drain must be followed by reconciliation with the server.
                reload()
                // No automatic backfill: planned dishes must never be
                // recorded as eaten without user confirmation.
            } else if (!hasCachedSnapshot) {
                lastError = "Lokale ændringer afventer synkronisering. Prøv igen, når du er online."
            }
            repository.startRealtime(scope) { event ->
                handleRealtime(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (hasCachedSnapshot) {
                syncIndicator = if (hasPendingMutations) {
                    SyncIndicator.PENDING
                } else {
                    SyncIndicator.OFFLINE
                }
            } else {
                lastError =
                    "Data kunne ikke hentes lige nu. Tjek forbindelsen og prøv igen."
            }
        } finally {
            isLoading = false
        }
    }

    private suspend fun observeSyncQueue() {
        var previousCount = 0

        combine(
            offlineSyncQueue.observePendingCount(),
            offlineSyncQueue.observeFailedCount(),
            offlineSyncQueue.observeConflictCount()
        ) { count, failedCount, conflictCount ->
            Triple(count, failedCount, conflictCount)
        }.collectLatest { (count, failedCount, conflictCount) ->
            pendingSyncCount = count
            failedSyncCount = failedCount
            syncConflictCount = conflictCount

            when {
                conflictCount > 0 -> {
                    syncStatusClearJob?.cancel()
                    syncIndicator = SyncIndicator.CONFLICT
                }

                failedCount > 0 -> {
                    syncStatusClearJob?.cancel()
                    syncIndicator = SyncIndicator.DELAYED
                }

                count > 0 -> {
                    syncStatusClearJob?.cancel()
                    syncIndicator = SyncIndicator.PENDING
                }

                previousCount > 0 -> {
                    val generationSnapshot =
                        CloudSyncDomain.entries
                            .associateWith(syncTracker::generation)

                    syncIndicator = SyncIndicator.SAVED
                    delay(250L.milliseconds)

                    if (
                        !offlineSyncQueue.hasPendingMutations() &&
                        syncTracker.isCurrent(generationSnapshot)
                    ) {
                        runCatching {
                            reload(
                                expectedGenerations =
                                    generationSnapshot
                            )
                        }
                    }

                    syncStatusClearJob?.cancel()
                    syncStatusClearJob = scope.launch {
                        delay(1200L.milliseconds)
                        if (
                            !offlineSyncQueue
                                .hasPendingMutations()
                        ) {
                            syncIndicator =
                                SyncIndicator.IDLE
                        }
                    }
                }

                syncIndicator == SyncIndicator.PENDING -> {
                    syncIndicator = SyncIndicator.IDLE
                }
            }

            previousCount = count
        }
    }

    fun retryPendingSync() {
        scope.launch {
            syncIndicator = SyncIndicator.SAVING
            mutationQueueMutex.withLock {
                offlineSyncQueue.resetAttempts()
                offlineSyncQueue.drainInApp(repository)
                syncIndicator = if (!offlineSyncQueue.hasPendingMutations()) {
                    SyncIndicator.SAVED
                } else {
                    SyncIndicator.PENDING
                }
            }
        }
    }

    fun keepMyConflictingChanges() {
        scope.launch {
            offlineSyncQueue.forceConflicts()
            syncIndicator = SyncIndicator.PENDING
        }
    }

    fun useSharedConflictingChanges() {
        scope.launch {
            val domains =
                offlineSyncQueue.discardConflicts()

            domains.forEach { domain ->
                runCatching {
                    reload(domain)
                }
            }

            if (!offlineSyncQueue.hasPendingMutations()) {
                syncIndicator = SyncIndicator.SAVED
            }
        }
    }

    suspend fun close() {
        try {
            cachePersistJob?.join()
            repository.stopRealtime()
        } finally {
            // Stop database observers and pending network work on household exit.
            scope.cancel()
        }
    }

    fun clearError() {
        lastError = null
    }

    private suspend fun reload(
        expectedGenerations: Map<CloudSyncDomain, Long>? = null
    ) {
        // Avoid applying a server response that began before a local edit.
        val startingGenerations = CloudSyncDomain.entries
            .associateWith(syncTracker::generation)
        if (offlineSyncQueue.hasPendingMutations()) return
        val requestedWeek = displayedWeekStart
        val snapshot = repository.loadCurrent(requestedWeek)

        var applied = false
        reloadMutex.withLock {
            if (
                !syncTracker.isCurrent(startingGenerations) ||
                (expectedGenerations != null &&
                    !syncTracker.isCurrent(expectedGenerations)) ||
                offlineSyncQueue.hasPendingMutations() ||
                displayedWeekStart != requestedWeek
            ) {
                return@withLock
            }

            applySnapshot(snapshot)
            applied = true
        }

        if (applied) {
            scheduleCachePersist(delayMillis = 0L)
        }
    }

    private suspend fun reload(domain: CloudSyncDomain) {
        if (offlineSyncQueue.hasPending(domain)) return

        var applied = false

        reloadMutex.withLock {
            val generationSnapshot = when (domain) {
                CloudSyncDomain.WEEK -> mapOf(
                    CloudSyncDomain.WEEK to
                        syncTracker.generation(CloudSyncDomain.WEEK),
                    CloudSyncDomain.SHOPPING to
                        syncTracker.generation(CloudSyncDomain.SHOPPING)
                )

                else -> mapOf(
                    domain to syncTracker.generation(domain)
                )
            }

            when (domain) {
                CloudSyncDomain.INGREDIENTS -> {
                    val snapshot = repository.loadIngredientsSnapshot()
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    applyIngredientsSnapshot(snapshot)
                    applied = true
                }

                CloudSyncDomain.RECIPES -> {
                    val snapshot = repository.loadRecipesSnapshot()
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    applyRecipesSnapshot(snapshot)
                    applied = true
                }

                CloudSyncDomain.PANTRY -> {
                    val snapshot = repository.loadPantrySnapshot()
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    syncMap(
                        target = pantry,
                        incoming = snapshot
                    )
                    applied = true
                }

                CloudSyncDomain.WEEK -> {
                    val requestedWeek = displayedWeekStart
                    val week = repository.loadWeekSnapshot(requestedWeek)
                    val shopping = repository.loadShoppingSnapshot(
                        week.weeklyPlanId
                    )
                    if (!syncTracker.isCurrent(generationSnapshot) ||
                        displayedWeekStart != requestedWeek
                    ) {
                        return@withLock
                    }
                    applyWeekSnapshot(week)
                    applyShoppingSnapshot(shopping)
                    applied = true
                }

                CloudSyncDomain.SHOPPING -> {
                    val planId = weeklyPlanId
                        ?: return@withLock
                    val snapshot =
                        repository.loadShoppingSnapshot(planId)
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    applyShoppingSnapshot(snapshot)
                    applied = true
                }

                CloudSyncDomain.HISTORY -> {
                    val snapshot = repository.loadHistorySnapshot()
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    syncListByKey(
                        target = history,
                        incoming = snapshot,
                        key = {
                            it.recipeId to it.eatenOn
                        }
                    )
                    applied = true
                }
            }
        }

        if (applied) {
            scheduleCachePersist()
        }
    }

    private suspend fun handleRealtime(
        event: CloudRealtimeEvent
    ) {
        if (offlineSyncQueue.hasPending(event.domain)) return

        when (event) {
            is CloudRealtimeEvent.DomainChanged -> {
                reload(event.domain)
            }

            is CloudRealtimeEvent.PantryChanged -> {
                if (event.amountBase == null) {
                    pantry.remove(event.ingredientId)
                } else if (
                    pantry[event.ingredientId] != event.amountBase
                ) {
                    pantry[event.ingredientId] = event.amountBase
                }
                scheduleCachePersist()
            }

            is CloudRealtimeEvent.ShoppingCheckedChanged -> {
                if (event.weeklyPlanId != weeklyPlanId) return
                if (event.checked == null) {
                    shoppingChecked.remove(event.ingredientId)
                } else if (
                    shoppingChecked[event.ingredientId] != event.checked
                ) {
                    shoppingChecked[event.ingredientId] =
                        event.checked
                }
                scheduleCachePersist()
            }

            is CloudRealtimeEvent.ShoppingIncludedChanged -> {
                if (event.weeklyPlanId != weeklyPlanId) return
                if (event.included == null) {
                    shoppingIncluded.remove(event.ingredientId)
                } else if (
                    shoppingIncluded[event.ingredientId] !=
                    event.included
                ) {
                    shoppingIncluded[event.ingredientId] =
                        event.included
                }
                scheduleCachePersist()
            }
        }
    }

    private fun applySnapshot(snapshot: CloudSnapshot) = Snapshot.withMutableSnapshot {
        syncListByKey(
            target = ingredients,
            incoming = snapshot.ingredients,
            key = Ingredient::id
        )
        syncListByKey(
            target = ingredientCategories,
            incoming = snapshot.ingredientCategories,
            key = IngredientCategory::id
        )
        syncListByKey(
            target = recipeCategories,
            incoming = snapshot.recipeCategories,
            key = RecipeCategoryOption::id
        )
        syncListByKey(
            target = recipes,
            incoming = snapshot.recipes,
            key = Recipe::id
        )

        syncMap(
            target = pantry,
            incoming = snapshot.pantry
        )
        syncMap(
            target = dayModes,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.dayModes[day] ?: DayMode.AUTO
            }
        )
        syncMap(
            target = dayMaxPrepMinutes,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.dayMaxPrepMinutes[day]
            }
        )
        syncMap(
            target = plan,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.plan[day]
            }
        )
        syncSet(
            target = lockedDays,
            incoming = snapshot.lockedDays
        )
        syncMap(
            target = shoppingChecked,
            incoming = snapshot.shoppingChecked
        )
        syncMap(
            target = shoppingIncluded,
            incoming = snapshot.shoppingIncluded
        )
        syncListByKey(
            target = selectedLunchRecipeIds,
            incoming = snapshot.selectedLunchRecipeIds,
            key = { it }
        )
        syncListByKey(
            target = history,
            incoming = snapshot.history,
            key = {
                it.recipeId to it.eatenOn
            }
        )

        weeklyPlanId = snapshot.weeklyPlanId
            .takeUnless { it.startsWith("offline-week-") }
        displayedWeekStart = snapshot.viewWeekStart
        weeklySandwichRecipeId = snapshot.weeklySandwichRecipeId
        sandwichDay = snapshot.sandwichDay
    }

    private fun applyIngredientsSnapshot(
        snapshot: CloudIngredientsSnapshot
    ) = Snapshot.withMutableSnapshot {
        syncListByKey(
            target = ingredients,
            incoming = snapshot.ingredients,
            key = Ingredient::id
        )
        syncListByKey(
            target = ingredientCategories,
            incoming = snapshot.ingredientCategories,
            key = IngredientCategory::id
        )
    }

    private fun applyRecipesSnapshot(
        snapshot: CloudRecipesSnapshot
    ) = Snapshot.withMutableSnapshot {
        syncListByKey(
            target = recipeCategories,
            incoming = snapshot.recipeCategories,
            key = RecipeCategoryOption::id
        )
        syncListByKey(
            target = recipes,
            incoming = snapshot.recipes,
            key = Recipe::id
        )
    }

    private fun applyWeekSnapshot(
        snapshot: CloudWeekSnapshot
    ) = Snapshot.withMutableSnapshot {
        syncMap(
            target = dayModes,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.dayModes[day] ?: DayMode.AUTO
            }
        )
        syncMap(
            target = dayMaxPrepMinutes,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.dayMaxPrepMinutes[day]
            }
        )
        syncMap(
            target = plan,
            incoming = orderedWeekDays.associateWith { day ->
                snapshot.plan[day]
            }
        )
        syncSet(
            target = lockedDays,
            incoming = snapshot.lockedDays
        )
        syncListByKey(
            target = selectedLunchRecipeIds,
            incoming = snapshot.selectedLunchRecipeIds,
            key = { it }
        )

        weeklyPlanId = snapshot.weeklyPlanId
        weeklySandwichRecipeId = snapshot.weeklySandwichRecipeId
        sandwichDay = snapshot.sandwichDay
    }

    private fun applyShoppingSnapshot(
        snapshot: CloudShoppingSnapshot
    ) = Snapshot.withMutableSnapshot {
        syncMap(
            target = shoppingChecked,
            incoming = snapshot.checked
        )
        syncMap(
            target = shoppingIncluded,
            incoming = snapshot.included
        )
    }

    private fun currentSnapshot(): CloudSnapshot {
        // A temporary week id lets unrelated pantry and recipe edits
        // persist even before a real weekly plan is available.
        val planId = weeklyPlanId ?: "offline-week-${currentWeekStart()}"

        return CloudSnapshot(
            ingredients = ingredients.toList(),
            ingredientCategories = ingredientCategories.toList(),
            recipeCategories = recipeCategories.toList(),
            recipes = recipes.toList(),
            pantry = pantry.toMap(),
            dayModes = dayModes.toMap(),
            dayMaxPrepMinutes = dayMaxPrepMinutes.toMap(),
            plan = plan.toMap(),
            lockedDays = lockedDays.toSet(),
            shoppingChecked = shoppingChecked.toMap(),
            shoppingIncluded = shoppingIncluded.toMap(),
            selectedLunchRecipeIds =
                selectedLunchRecipeIds.toList(),
            weeklyPlanId = planId,
            weeklySandwichRecipeId = weeklySandwichRecipeId,
            sandwichDay = sandwichDay,
            history = history.toList(),
            viewWeekStart = displayedWeekStart
        )
    }

    private fun scheduleCachePersist(
        delayMillis: Long = 100L
    ) {
        val generations = CloudSyncDomain.entries
            .associateWith(syncTracker::generation)

        cachePersistJob?.cancel()
        cachePersistJob = scope.launch {
            delay(delayMillis.milliseconds)
            // The outbox transaction must remain the last writer of local
            // optimistic state. A delayed remote cache write is never allowed
            // to replace a newer edit or a pending mutation.
            mutationQueueMutex.withLock {
                if (
                    syncTracker.isCurrent(generations) &&
                    !offlineSyncQueue.hasPendingMutations()
                ) {
                    currentSnapshot()?.let { snapshot ->
                        roomCache.save(snapshot)
                    }
                }
            }
        }
    }

    private fun enqueueMutation(
        mutation: QueuedMutation
    ) {
        enqueueMutations(listOf(mutation))
    }

    private fun enqueueMutations(
        mutations: List<QueuedMutation>
    ) {
        if (mutations.isEmpty()) return

        val domains = mutations
            .flatMap { it.domains() }
            .toSet()

        syncTracker.markLocalChange(domains)
        syncIndicator = SyncIndicator.SAVING

        val snapshot = currentSnapshot() ?: return

        if (isLocalOnly) {
            // Local-only updates are durable in Room, without creating an
            // outbox entry or a WorkManager task requiring cloud credentials.
            cachePersistJob?.cancel()
            cachePersistJob = scope.launch {
                try {
                    mutationQueueMutex.withLock {
                        roomCache.save(snapshot)
                    }
                    syncIndicator = SyncIndicator.SAVED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    syncIndicator = SyncIndicator.ERROR
                    lastError = "Ændringen kunne ikke gemmes lokalt."
                }
            }
            return
        }

        scope.launch {
            mutationQueueMutex.withLock {
                try {
                    offlineSyncQueue.enqueue(
                        snapshot = snapshot,
                        mutations = mutations
                    )
                    val drainResult = offlineSyncQueue.drainInApp(repository)
                    if (!offlineSyncQueue.hasPendingMutations()) {
                        syncIndicator = SyncIndicator.SAVED
                    } else if (drainResult == OfflineSyncDrainResult.RETRY) {
                        syncIndicator = SyncIndicator.PENDING
                    }
                } catch (e: Exception) {
                    if (offlineSyncQueue.hasPendingMutations()) {
                        syncIndicator = SyncIndicator.PENDING
                    } else {
                        lastError =
                            "Ændringen kunne ikke gemmes på telefonen. Prøv igen."
                        syncIndicator = SyncIndicator.ERROR
                    }
                }
            }
        }
    }

    private fun weekStateMutation(
        weeklyPlanId: String
    ): QueuedMutation.SetWeekState =
        QueuedMutation.SetWeekState(
            weeklyPlanId = weeklyPlanId,
            plan = plan.mapKeys { it.key.name },
            dayModes = dayModes
                .mapKeys { it.key.name }
                .mapValues { it.value.name },
            lockedDays = lockedDays.map { it.name },
            sandwichRecipeId = weeklySandwichRecipeId,
            sandwichDay = sandwichDay?.name
        )

    private fun persistWeek(
        clearChecks: Boolean = false,
        updateHouseholdSandwich: Boolean = false
    ) {
        val id = weeklyPlanId ?: return

        val mutations = buildList {
            if (updateHouseholdSandwich) {
                add(
                    QueuedMutation.SetHouseholdSandwich(
                        weeklySandwichRecipeId
                    )
                )
            }

            add(weekStateMutation(id))

            if (clearChecks) {
                shoppingChecked.clear()
                shoppingIncluded.clear()
                add(QueuedMutation.ClearShopping(id))
            }
        }

        enqueueMutations(mutations)
    }

    private fun canEditWeek(): Boolean {
        if (isWeekChanging || isPlanning) return false
        if (weeklyPlanId != null) return true
        lastError = "Ugeplanen kan opdateres, når forbindelsen er tilbage. Lager og opskrifter virker offline."
        return false
    }

    private fun dateFor(day: DayOfWeek): LocalDate =
        displayedWeekStart.plusDays((day.value - 1).toLong())

    fun isCompletedDayLocked(day: DayOfWeek): Boolean =
        WeekRules.completedDayLocked(dateFor(day), history, editableCompletedDates)

    fun isDayLocked(day: DayOfWeek): Boolean =
        isCompletedDayLocked(day) || day in lockedDays

    fun isWeekRegenerationLocked(): Boolean =
        WeekRules.requiresRegenerationUnlock(
            displayedWeekStart, history, unlockedRegenerationWeeks
        )

    /** This unlocks only regeneration; individual completed dinners stay protected. */
    fun unlockWeekRegeneration() {
        if (!canEditWeek() || !isWeekRegenerationLocked()) return
        unlockedRegenerationWeeks.add(displayedWeekStart)
    }

    fun unlockRegisteredMeal(date: LocalDate) {
        if (isWeekChanging || isPlanning || !WeekRules.completedOn(date, history)) return
        editableCompletedDates.add(date)
        if (date in displayedWeekStart..displayedWeekStart.plusDays(6)) {
            lockedDays.remove(date.dayOfWeek)
            persistWeek()
        }
    }

    fun isRegisteredMealEditable(date: LocalDate): Boolean =
        !WeekRules.completedOn(date, history) || date in editableCompletedDates

    fun toggleLockDay(day: DayOfWeek) {
        if (!canEditWeek()) return
        val date = dateFor(day)
        if (!WeekRules.completedOn(date, history) &&
            (dayModes[day] == DayMode.AWAY || recipeFor(day) == null)) return
        if (WeekRules.completedOn(date, history)) {
            if (date in editableCompletedDates) {
                editableCompletedDates.remove(date)
                lockedDays.add(day)
            } else {
                editableCompletedDates.add(date)
                lockedDays.remove(day)
            }
        } else if (day in lockedDays) {
            lockedDays.remove(day)
        } else {
            lockedDays.add(day)
        }
        persistWeek()
    }

    fun setRecipeForDay(day: DayOfWeek, recipeId: String?) {
        if (!canEditWeek()) return
        if (isCompletedDayLocked(day)) {
            lastError = "Retten er markeret som spist. Lås dagen op før ændringer."
            return
        }
        if (day == sandwichDay) return
        if (plan[day] == recipeId &&
            (recipeId == null || dayModes[day] != DayMode.AWAY)
        ) return // Re-selecting the same meal must not reset shopping progress.

        val previousItems = shoppingItems()
        val previousChecked = shoppingChecked.toMap()
        val previousIncluded = shoppingIncluded.toMap()

        plan[day] = recipeId
        if (recipeId != null) dayModes[day] = DayMode.AUTO

        val retainedIds = ShoppingRetentionRules.unchangedIngredientIds(
            previousItems, shoppingItems()
        )
        shoppingChecked.clear()
        shoppingIncluded.clear()
        retainedIds.forEach { ingredientId ->
            previousIncluded[ingredientId]?.let { shoppingIncluded[ingredientId] = it }
            previousChecked[ingredientId]?.let { shoppingChecked[ingredientId] = it }
        }

        // Clear and restore retained choices in the same durable outbox
        // transaction so offline/cloud clients see the same shopping state.
        val id = weeklyPlanId ?: return
        enqueueMutations(buildList {
            add(weekStateMutation(id))
            add(QueuedMutation.ClearShopping(id))
            shoppingIncluded.forEach { (ingredientId, included) ->
                add(QueuedMutation.SetShoppingIncluded(id, ingredientId, included))
            }
            shoppingChecked.forEach { (ingredientId, checked) ->
                add(QueuedMutation.SetShoppingChecked(id, ingredientId, checked))
            }
        })
    }

    fun isIngredientNameAvailable(
        name: String,
        excludingId: String? = null
    ): Boolean {
        val clean = name.trim()
        return clean.isNotBlank() && ingredients.none {
            it.id != excludingId &&
                it.name.trim().equals(clean, ignoreCase = true)
        }
    }

    fun addIngredient(
        name: String,
        type: QuantityType,
        shoppingCategoryId: String?,
        isProtein: Boolean,
        imageUri: String? = null,
        preferredUnit: MeasurementUnit = MeasurementUnit.defaultFor(type),
        neverBuy: Boolean = false
    ): Boolean {
        val clean = name.normalizedDisplayName()
        if (clean.isBlank() || preferredUnit.quantityType != type) return false
        if (!isIngredientNameAvailable(clean)) {
            lastError = "Ingrediensen findes allerede."
            return false
        }

        val ingredient = Ingredient(
            id = UUID.randomUUID().toString(),
            name = clean,
            quantityType = type,
            preferredUnit = preferredUnit,
            shoppingCategoryId = shoppingCategoryId,
            isProtein = isProtein,
            neverBuy = neverBuy,
            imageUri = imageUri
        )

        ingredients += ingredient
        enqueueMutation(
            QueuedMutation.UpsertIngredient(ingredient)
        )
        return true
    }

    fun canChangeIngredientQuantityType(id: String, newType: QuantityType): Boolean {
        val ingredient = ingredients.find { it.id == id } ?: return false
        return canChangeIngredientQuantityType(
            ingredient = ingredient,
            newType = newType,
            pantryBase = pantry[id] ?: 0.0,
            recipes = recipes.toList()
        )
    }

    fun updateIngredient(
        id: String,
        name: String,
        type: QuantityType,
        shoppingCategoryId: String?,
        isProtein: Boolean,
        preferredUnit: MeasurementUnit,
        imageUri: String? = null,
        neverBuy: Boolean = false
    ): Boolean {
        val index = ingredients.indexOfFirst { it.id == id }
        if (
            index < 0 ||
            name.isBlank() ||
            preferredUnit.quantityType != type
        ) return false

        if (!canChangeIngredientQuantityType(id, type)) {
            lastError = "Typen kan ikke ændres, mens ingrediensen er på lager eller bruges i en opskrift."
            return false
        }

        if (!isIngredientNameAvailable(name, excludingId = id)) {
            lastError = "Ingrediensen findes allerede."
            return false
        }

        val updated = ingredients[index].copy(
            name = name.normalizedDisplayName(),
            quantityType = type,
            preferredUnit = preferredUnit,
            shoppingCategoryId = shoppingCategoryId,
            isProtein = isProtein,
            neverBuy = neverBuy,
            imageUri = imageUri
        )
        ingredients[index] = updated
        enqueueMutation(
            QueuedMutation.UpsertIngredient(updated)
        )
        return true
    }

    fun isIngredientCategoryNameAvailable(
        name: String,
        excludingId: String? = null
    ): Boolean {
        val clean = name.trim()
        return clean.isNotBlank() && ingredientCategories.none {
            it.id != excludingId &&
                it.name.trim().equals(clean, ignoreCase = true)
        }
    }

    fun addIngredientCategory(name: String): Boolean {
        val clean = name.normalizedDisplayName()
        if (!isIngredientCategoryNameAvailable(clean)) {
            lastError = "Kategorien findes allerede."
            return false
        }

        val category = IngredientCategory(
            id = UUID.randomUUID().toString(),
            name = clean,
            position =
                (ingredientCategories.maxOfOrNull { it.position } ?: -1) + 1
        )

        ingredientCategories += category
        ingredientCategories.sortWith(
            compareBy<IngredientCategory> { it.position }
                .thenBy { it.name.lowercase() }
        )
        enqueueMutation(
            QueuedMutation.UpsertIngredientCategory(category)
        )
        return true
    }

    fun moveIngredientCategory(categoryId: String, delta: Int) {
        val index = ingredientCategories.indexOfFirst { it.id == categoryId }
        val reordered = reorderCategory(
            items = ingredientCategories.toList(),
            index = index,
            delta = delta,
            withPosition = { category, position ->
                category.copy(position = position)
            }
        ) ?: return
        syncListByKey(ingredientCategories, reordered, IngredientCategory::id)
        enqueueMutations(reordered.map { QueuedMutation.UpsertIngredientCategory(it) })
    }

    fun renameIngredientCategory(
        categoryId: String,
        name: String
    ): Boolean {
        val clean = name.normalizedDisplayName()
        if (!isIngredientCategoryNameAvailable(clean, excludingId = categoryId)) {
            lastError = "Kategorien findes allerede."
            return false
        }

        val index = ingredientCategories.indexOfFirst { it.id == categoryId }
        if (index < 0) return false

        val updated =
            ingredientCategories[index].copy(name = clean)
        ingredientCategories[index] = updated

        enqueueMutation(
            QueuedMutation.UpsertIngredientCategory(updated)
        )
        return true
    }

    fun ingredientCategoryName(categoryId: String?): String =
        ingredientCategories
            .find { it.id == categoryId }
            ?.name
            ?: "Andet"

    fun isRecipeCategoryNameAvailable(
        name: String,
        excludingId: String? = null
    ): Boolean {
        val clean = name.normalizedDisplayName()
        return clean.isNotBlank() && recipeCategories.none {
            it.id != excludingId &&
                it.name.equals(clean, ignoreCase = true)
        }
    }

    fun addRecipeCategory(name: String): Boolean {
        val clean = name.normalizedDisplayName()
        if (!isRecipeCategoryNameAvailable(clean)) {
            lastError = "Kategorien findes allerede."
            return false
        }

        val category = RecipeCategoryOption(
            id = UUID.randomUUID().toString(),
            name = clean,
            position =
                (recipeCategories.maxOfOrNull { it.position } ?: -1) + 1
        )

        recipeCategories += category
        recipeCategories.sortWith(
            compareBy<RecipeCategoryOption> { it.position }
                .thenBy { it.name.lowercase() }
        )
        enqueueMutation(
            QueuedMutation.UpsertRecipeCategory(category)
        )
        return true
    }

    fun moveRecipeCategory(categoryId: String, delta: Int) {
        val index = recipeCategories.indexOfFirst { it.id == categoryId }
        val reordered = reorderCategory(
            items = recipeCategories.toList(),
            index = index,
            delta = delta,
            withPosition = { category, position ->
                category.copy(position = position)
            }
        ) ?: return
        syncListByKey(recipeCategories, reordered, RecipeCategoryOption::id)
        enqueueMutations(reordered.map { QueuedMutation.UpsertRecipeCategory(it) })
    }

    fun renameRecipeCategory(
        categoryId: String,
        name: String
    ): Boolean {
        val clean = name.normalizedDisplayName()
        if (!isRecipeCategoryNameAvailable(clean, excludingId = categoryId)) {
            lastError = "Kategorien findes allerede."
            return false
        }

        val index = recipeCategories.indexOfFirst { it.id == categoryId }
        if (index < 0) return false

        val updated =
            recipeCategories[index].copy(name = clean)
        recipeCategories[index] = updated

        enqueueMutation(
            QueuedMutation.UpsertRecipeCategory(updated)
        )
        return true
    }

    fun deleteRecipeCategory(categoryId: String): Boolean {
        val index = recipeCategories.indexOfFirst { it.id == categoryId }
        if (index < 0) return false
        if (recipeCategories.size <= 1) {
            lastError = "Der skal være mindst én kategori."
            return false
        }

        recipeCategories.removeAt(index)
        val fallbackId = recipeCategories.firstOrNull()?.id ?: ""
        recipes.forEach { recipe ->
            if (recipe.categoryId == categoryId) {
                val rIndex = recipes.indexOfFirst { it.id == recipe.id }
                if (rIndex >= 0) {
                    val updated = recipes[rIndex].copy(categoryId = fallbackId)
                    recipes[rIndex] = updated
                    enqueueMutation(QueuedMutation.UpsertRecipe(updated))
                }
            }
        }

        enqueueMutation(QueuedMutation.DeleteRecipeCategory(categoryId))
        return true
    }

    fun deleteIngredientCategory(categoryId: String): Boolean {
        val index = ingredientCategories.indexOfFirst { it.id == categoryId }
        if (index < 0) return false
        if (ingredientCategories.size <= 1) {
            lastError = "Der skal være mindst én kategori."
            return false
        }

        ingredientCategories.removeAt(index)
        val fallbackId = ingredientCategories.firstOrNull()?.id ?: ""
        ingredients.forEach { ingredient ->
            if (ingredient.shoppingCategoryId == categoryId) {
                val iIndex = ingredients.indexOfFirst { it.id == ingredient.id }
                if (iIndex >= 0) {
                    val updated = ingredients[iIndex].copy(shoppingCategoryId = fallbackId)
                    ingredients[iIndex] = updated
                    enqueueMutation(QueuedMutation.UpsertIngredient(updated))
                }
            }
        }

        enqueueMutation(QueuedMutation.DeleteIngredientCategory(categoryId))
        return true
    }

    fun recipeCategoryName(recipe: Recipe): String =
        recipeCategories
            .find { it.id == recipe.categoryId }
            ?.name
            ?: "Andet"

    fun recipeProteinNames(recipe: Recipe): List<String> {
        val ingredientsById = ingredients.associateBy { it.id }
        return recipe.ingredients
            .asSequence()
            .mapNotNull { ingredientsById[it.ingredientId] }
            .filter { it.isProtein }
            .map { it.name }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    fun deleteIngredient(ingredientId: String): Boolean {
        if (recipes.any { recipe ->
                recipe.ingredients.any {
                    it.ingredientId == ingredientId
                } ||
                    recipe.pantryPrerequisites.any {
                        it.ingredientId == ingredientId
                    }
            }
        ) {
            lastError =
                "Ingrediensen bruges i en opskrift og kan derfor ikke slettes."
            return false
        }

        val ingredient = ingredients.find {
            it.id == ingredientId
        } ?: return false

        ingredients.removeAll { it.id == ingredientId }
        pantry.remove(ingredientId)
        shoppingChecked.remove(ingredientId)
        shoppingIncluded.remove(ingredientId)

        enqueueMutation(
            QueuedMutation.DeleteIngredient(
                ingredientId = ingredientId,
                imagePath = ingredient.imagePath
            )
        )
        return true
    }

    fun isRecipeNameAvailable(
        name: String,
        excludingId: String? = null
    ): Boolean {
        val clean = name.trim()
        return clean.isNotBlank() && recipes.none {
            it.id != excludingId &&
                it.name.trim().equals(clean, ignoreCase = true)
        }
    }

    fun addRecipe(
        name: String,
        mealType: MealType,
        autoRotationEnabled: Boolean,
        prepMinutes: Int?,
        categoryId: String?,
        notes: String = "",
        imageUri: String? = null,
        pantryPrerequisites: List<DraftRequirement> = emptyList(),
        requirements: List<DraftRequirement>,
        instructionSteps: List<RecipeInstructionStep> = emptyList(),
        sourceUrl: String? = null
    ): String? {
        if (!isRecipeNameAvailable(name)) {
            lastError = "Opskriften findes allerede."
            return null
        }

        val recipe = buildRecipe(
            id = UUID.randomUUID().toString(),
            name = name,
            mealType = mealType,
            autoRotationEnabled = autoRotationEnabled,
            prepMinutes = prepMinutes,
            categoryId = categoryId,
            notes = notes,
            imageUri = imageUri,
            imagePath = null,
            pantryPrerequisites = pantryPrerequisites,
            requirements = requirements,
            instructionSteps = instructionSteps,
            sourceUrl = sourceUrl
        ) ?: return null

        recipes += recipe
        enqueueMutation(
            QueuedMutation.UpsertRecipe(recipe)
        )
        return recipe.id
    }

    fun updateRecipe(
        id: String,
        name: String,
        mealType: MealType,
        autoRotationEnabled: Boolean,
        prepMinutes: Int?,
        categoryId: String?,
        notes: String = "",
        imageUri: String? = null,
        pantryPrerequisites: List<DraftRequirement> = emptyList(),
        requirements: List<DraftRequirement>,
        instructionSteps: List<RecipeInstructionStep> = emptyList(),
        sourceUrl: String? = null
    ): Boolean {
        val index = recipes.indexOfFirst { it.id == id }
        if (index < 0) return false

        if (!isRecipeNameAvailable(name, excludingId = id)) {
            lastError = "Opskriften findes allerede."
            return false
        }

        val existing = recipes[index]
        val updated = buildRecipe(
            id = id,
            name = name,
            mealType = mealType,
            autoRotationEnabled = autoRotationEnabled,
            prepMinutes = prepMinutes,
            categoryId = categoryId,
            notes = notes,
            imageUri = imageUri,
            imagePath = existing.imagePath,
            pantryPrerequisites = pantryPrerequisites,
            requirements = requirements,
            instructionSteps = instructionSteps,
            sourceUrl = sourceUrl
        ) ?: return false

        recipes[index] = updated

        var weekChanged = false
        var removedFromLunch = false

        if (updated.mealType == MealType.LUNCH) {
            orderedWeekDays.forEach { day ->
                if (plan[day] == id) {
                    plan[day] = null
                    lockedDays.remove(day)
                    weekChanged = true
                }
            }

            if (weeklySandwichRecipeId == id) {
                weeklySandwichRecipeId = null
                sandwichDay = null
                weekChanged = true
            }
        } else if (selectedLunchRecipeIds.remove(id)) {
            removedFromLunch = true
            weekChanged = true
        }

        if (weekChanged) {
            shoppingChecked.clear()
        shoppingIncluded.clear()
        }

        val mutations = buildList {
            add(QueuedMutation.UpsertRecipe(updated))

            val currentPlanId = weeklyPlanId
            if (removedFromLunch && currentPlanId != null) {
                add(
                    QueuedMutation.ReplaceLunch(
                        weeklyPlanId = currentPlanId,
                        recipeIds = selectedLunchRecipeIds.toList()
                    )
                )
            }

            if (weekChanged && currentPlanId != null) {
                add(
                    QueuedMutation.SetHouseholdSandwich(
                        weeklySandwichRecipeId
                    )
                )
                add(weekStateMutation(currentPlanId))
                add(QueuedMutation.ClearShopping(currentPlanId))
            }
        }

        enqueueMutations(mutations)
        return true
    }

    private fun buildRecipe(
        id: String,
        name: String,
        mealType: MealType,
        autoRotationEnabled: Boolean,
        prepMinutes: Int?,
        categoryId: String?,
        notes: String,
        imageUri: String?,
        imagePath: String?,
        pantryPrerequisites: List<DraftRequirement>,
        requirements: List<DraftRequirement>,
        instructionSteps: List<RecipeInstructionStep>,
        sourceUrl: String?
    ): Recipe? {
        val clean = name.normalizedDisplayName()
        val cleanPrepMinutes = prepMinutes?.takeIf { it in 1..1440 }
        if (
            clean.isBlank() ||
            requirements.isEmpty() ||
            (
                categoryId != null &&
                recipeCategories.none { it.id == categoryId }
            )
        ) return null

        val converted = requirements.mapNotNull { draft ->
            val ingredient = ingredients.find { it.id == draft.ingredientId }
                ?: return@mapNotNull null

            if (
                draft.amount <= 0.0 ||
                draft.unit.quantityType != ingredient.quantityType
            ) return@mapNotNull null

            RecipeIngredient(
                ingredientId = draft.ingredientId,
                amountBase = draft.unit.toBase(draft.amount)
            )
        }

        if (converted.isEmpty()) return null

        val convertedPrerequisites = if (mealType == MealType.DINNER) {
            pantryPrerequisites.mapNotNull { draft ->
                val ingredient = ingredients.find {
                    it.id == draft.ingredientId
                } ?: return@mapNotNull null

                if (
                    draft.amount <= 0.0 ||
                    draft.unit.quantityType != ingredient.quantityType
                ) return@mapNotNull null

                RecipePantryPrerequisite(
                    ingredientId = draft.ingredientId,
                    minimumAmountBase = draft.unit.toBase(draft.amount)
                )
            }
        } else {
            emptyList()
        }

        if (
            mealType == MealType.DINNER &&
            convertedPrerequisites.size != pantryPrerequisites.size
        ) return null

        return Recipe(
            id = id,
            name = clean,
            mealType = mealType,
            autoRotationEnabled = autoRotationEnabled,
            prepMinutes = cleanPrepMinutes,
            categoryId = categoryId,
            notes = notes.trim(),
            instructionSteps = instructionSteps.filter { it.text.isNotBlank() }
                .map { it.copy(text = it.text.trim(), level = it.level.coerceIn(0, 1)) },
            sourceUrl = sourceUrl?.trim()?.takeIf { it.isNotBlank() },
            imageUri = imageUri,
            imagePath = imagePath,
            ingredients = RecipeRequirementRules.combineIngredients(converted),
            pantryPrerequisites = RecipeRequirementRules.combinePrerequisites(convertedPrerequisites)
        )
    }

    fun deleteRecipe(recipeId: String) {
        val recipe = recipes.find { it.id == recipeId } ?: return

        recipes.removeAll { it.id == recipeId }
        selectedLunchRecipeIds.remove(recipeId)
        plan.keys.forEach { day ->
            if (plan[day] == recipeId) plan[day] = null
        }

        if (weeklySandwichRecipeId == recipeId) {
            weeklySandwichRecipeId = null
            sandwichDay = null
        }

        shoppingChecked.clear()
        shoppingIncluded.clear()

        val mutations = buildList {
            val planId = weeklyPlanId
            if (planId != null) {
                add(
                    QueuedMutation.ReplaceLunch(
                        weeklyPlanId = planId,
                        recipeIds = selectedLunchRecipeIds.toList()
                    )
                )
                add(
                    QueuedMutation.SetHouseholdSandwich(
                        weeklySandwichRecipeId
                    )
                )
                add(weekStateMutation(planId))
                add(QueuedMutation.ClearShopping(planId))
            }

            add(
                QueuedMutation.DeleteRecipe(
                    recipeId = recipeId,
                    imagePath = recipe.imagePath
                )
            )
        }

        enqueueMutations(mutations)
    }

    fun removeSandwichThisWeek() {
        if (!canEditWeek()) return
        if (sandwichDay?.let(::isCompletedDayLocked) == true) {
            lastError = "Den spiste sandwich er låst. Lås dagen op før ændringer."
            return
        }
        if (weeklySandwichRecipeId == null && sandwichDay == null) return

        weeklySandwichRecipeId = null
        sandwichDay = null
        shoppingChecked.clear()
        shoppingIncluded.clear()

        persistWeek(clearChecks = true)
    }

    fun setWeeklySandwichRecipe(recipeId: String) {
        if (!canEditWeek()) return
        if (recipes.none { it.id == recipeId && it.mealType == MealType.DINNER }) return

        val targetDay = if (sandwichDay == null ||
            sandwichDay?.let { dayModes[it] == DayMode.AWAY } == true
        ) {
            WeeklyPlanner.preferredSandwichDay(dayModes)
        } else sandwichDay

        if (sandwichDay?.let(::isCompletedDayLocked) == true ||
            targetDay?.let(::isCompletedDayLocked) == true) {
            lastError = "En spist sandwichdag er låst. Lås dagen op før ændringer."
            return
        }

        weeklySandwichRecipeId = recipeId
        sandwichDay = targetDay

        shoppingChecked.clear()
        shoppingIncluded.clear()
        persistWeek(
            clearChecks = true,
            updateHouseholdSandwich = true
        )
    }

    fun toggleEatingAtHome(day: DayOfWeek) {
        if (!canEditWeek()) return
        if (isCompletedDayLocked(day)) {
            lastError = "Dagen er markeret som spist og er låst."
            return
        }
        val result = WeekRules.toggleHome(
            day = day,
            modes = dayModes.toMap(),
            currentSandwichDay = sandwichDay,
            hasSandwichRecipe = weeklySandwichRecipeId != null
        )
        if (result.sandwichDay != sandwichDay &&
            (sandwichDay?.let(::isCompletedDayLocked) == true ||
                result.sandwichDay?.let(::isCompletedDayLocked) == true)) {
            lastError = "En spist sandwichdag kan ikke flyttes uden at låse dagen op."
            return
        }

        syncMap(
            target = dayModes,
            incoming = result.modes
        )
        sandwichDay = result.sandwichDay

        if (dayModes[day] == DayMode.AWAY) {
            lockedDays.remove(day)
        }

        shoppingChecked.clear()
        shoppingIncluded.clear()
        persistWeek(clearChecks = true)
    }

    fun setDayMaxPrepMinutes(
        day: DayOfWeek,
        maxPrepMinutes: Int?
    ) {
        if (!canEditWeek()) return
        if (isCompletedDayLocked(day)) {
            lastError = "Dagen er markeret som spist og er låst."
            return
        }
        val normalized = maxPrepMinutes?.takeIf { it in 1..1440 }
        dayMaxPrepMinutes[day] = normalized

        val planId = weeklyPlanId ?: return
        enqueueMutation(
            QueuedMutation.SetDayPrep(
                weeklyPlanId = planId,
                day = day.name,
                maxPrepMinutes = normalized
            )
        )
    }

    fun setPantry(
        ingredientId: String,
        amount: Double,
        unit: MeasurementUnit
    ) {
        val ingredient = ingredients.find { it.id == ingredientId } ?: return
        if (unit.quantityType != ingredient.quantityType) return

        if (!amount.isFinite() || amount < 0.0) return
        val base = unit.toBase(amount)
        if (!base.isFinite()) return
        pantry[ingredientId] = base

        enqueueMutation(
            QueuedMutation.SetPantry(
                ingredientId = ingredientId,
                amountBase = base
            )
        )
    }

    fun addPantryAmount(
        ingredientId: String,
        amount: Double,
        unit: MeasurementUnit
    ) {
        val ingredient = ingredients.find { it.id == ingredientId } ?: return
        if (unit.quantityType != ingredient.quantityType ||
            !amount.isFinite() || amount <= 0.0
        ) return

        val addedBase = unit.toBase(amount)
        val next = (pantry[ingredientId] ?: 0.0) + addedBase
        if (!next.isFinite()) return
        pantry[ingredientId] = next

        enqueueMutation(
            QueuedMutation.SetPantry(
                ingredientId = ingredientId,
                amountBase = next
            )
        )
    }

    fun consumeRecipeIngredients(
        recipeId: String,
        deductions: Map<String, Double>? = null
    ) {
        val recipe = recipes.find { it.id == recipeId } ?: return
        val updates = mutableMapOf<String, Double>()

        if (deductions != null) {
            deductions.forEach { (ingredientId, deductBase) ->
                if (deductBase > 0.0) {
                    val current = pantry[ingredientId] ?: 0.0
                    val next = (current - deductBase).coerceAtLeast(0.0)
                    updates[ingredientId] = next
                }
            }
        } else {
            updates.putAll(
                PantryRules.remainingAfterCooking(
                    recipe = recipe,
                    pantry = pantry
                )
            )
        }

        if (updates.isEmpty()) return

        updates.forEach { (ingredientId, amountBase) ->
            pantry[ingredientId] = amountBase
        }

        enqueueMutations(
            updates.map { (ingredientId, amountBase) ->
                QueuedMutation.SetPantry(
                    ingredientId = ingredientId,
                    amountBase = amountBase
                )
            }
        )
    }

    /**
     * Finish a planned dinner and record the explicitly reviewed pantry usage
     * in one offline-sync batch. A second tap cannot consume stock again;
     * corrections to history remain possible in the separate history screen
     * without repeating pantry deductions.
     */
    fun completePlannedDinner(
        date: LocalDate,
        recipeId: String,
        deductions: Map<String, Double>
    ): Boolean {
        if (isPlanning || isWeekChanging) {
            lastError = "Vent til madplanen er opdateret, før du registrerer retten."
            return false
        }
        if (!MealCompletionRules.mayFinish(date, LocalDate.now(), history)) {
            lastError = "Dagen er allerede registreret, eller datoen ligger uden for de seneste 90 dage."
            return false
        }
        val recipe = recipes.find { it.id == recipeId && it.mealType == MealType.DINNER }
        if (date in displayedWeekStart..displayedWeekStart.plusDays(6) &&
            recipeFor(date.dayOfWeek)?.id != recipeId) {
            lastError = "Dagens planlagte ret er ændret. Luk vinduet og prøv igen."
            return false
        }
        if (recipe == null) {
            lastError = "Opskriften findes ikke længere."
            return false
        }
        val remaining = MealCompletionRules.remainingAfterMeal(recipe, pantry, deductions)
        if (remaining == null) {
            lastError = "Kontrollér lagerforbruget. En mængde er ugyldig eller større end lageret."
            return false
        }

        remaining.forEach { (ingredientId, value) -> pantry[ingredientId] = value }
        history.add(MealHistoryEntry(recipeId, date))
        // Completion and the persisted day lock share an offline outbox batch.
        val lockMutation = if (date in displayedWeekStart..displayedWeekStart.plusDays(6)) {
            lockedDays.add(date.dayOfWeek)
            weeklyPlanId?.let(::weekStateMutation)
        } else null
        enqueueMutations(
            remaining.map { (ingredientId, amountBase) ->
                QueuedMutation.SetPantry(ingredientId = ingredientId, amountBase = amountBase)
            } + QueuedMutation.SetMealEaten(
                eatenOn = date.toString(), recipeId = recipeId
            ) + listOfNotNull(lockMutation)
        )
        return true
    }

    /**
     * Confirm or correct the actual dinner eaten on a date. Unconfirmed
     * planning entries are intentionally excluded from meal history.
     */
    fun recordEaten(date: LocalDate, recipeId: String?) {
        if (isPlanning || isWeekChanging) {
            lastError = "Vent til madplanen er opdateret, før du retter historikken."
            return
        }
        if (date.isAfter(LocalDate.now()) ||
            date.isBefore(LocalDate.now().minusDays(89))) {
            lastError = "Vælg en dag inden for de seneste 90 dage."
            return
        }
        if (recipeId != null && recipes.none { it.id == recipeId }) {
            lastError = "Opskriften findes ikke længere."
            return
        }
        if (!isRegisteredMealEditable(date)) {
            lastError = "Det spiste måltid er låst. Lås op før du retter historikken."
            return
        }
        val previous = history.find { it.eatenOn == date }?.recipeId
        if (previous == recipeId) {
            // A confirmation of the existing registration must not leave an
            // already-completed day permanently unlocked in this session.
            editableCompletedDates.remove(date)
            if (recipeId != null && date in displayedWeekStart..displayedWeekStart.plusDays(6)) {
                lockedDays.add(date.dayOfWeek)
                persistWeek()
            }
            return
        }

        history.removeAll { it.eatenOn == date }
        if (recipeId != null) {
            history.add(MealHistoryEntry(recipeId, date))
        }
        // Editing a historic entry is a one-time operation; relock afterwards.
        editableCompletedDates.remove(date)
        val lockMutation = if (date in displayedWeekStart..displayedWeekStart.plusDays(6)) {
            if (recipeId == null) lockedDays.remove(date.dayOfWeek)
            else lockedDays.add(date.dayOfWeek)
            weeklyPlanId?.let(::weekStateMutation)
        } else null
        enqueueMutations(
            listOf(
                QueuedMutation.SetMealEaten(
                    eatenOn = date.toString(),
                    recipeId = recipeId
                )
            ) + listOfNotNull(lockMutation)
        )
    }

    fun generateWeek() {
        if (isPlanning || isWeekChanging || weeklyPlanId == null) return
        if (isWeekRegenerationLocked()) {
            lastError = "Ugen indeholder spiste måltider. Lås genplanlægning op først."
            return
        }

        val recipesSnapshot = recipes.toList()
        val pantrySnapshot = pantry.toMap()
        val modeSnapshot = dayModes.toMap()
        val maxPrepSnapshot = dayMaxPrepMinutes.toMap()
        val planSnapshot = plan.toMap()
        val lockSnapshot = lockedDays.toSet() +
            WeekRules.completedDaysForWeek(displayedWeekStart, history).filter {
                isCompletedDayLocked(it)
            }
        val historySnapshot = history.toList()
        val weightsSnapshot = plannerWeights
        val proteinIngredientIds = ingredients
            .asSequence()
            .filter { it.isProtein }
            .map { it.id }
            .toSet()
        val sandwichRecipe = weeklySandwichRecipeId
        val currentSandwichDay = sandwichDay
        val weekStartSnapshot = displayedWeekStart

        isPlanning = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    WeeklyPlanner.generate(
                        recipes = recipesSnapshot,
                        pantry = pantrySnapshot,
                        dayModes = modeSnapshot,
                        maxPrepMinutesByDay = maxPrepSnapshot,
                        weeklySandwichRecipeId = sandwichRecipe,
                        currentPlan = planSnapshot,
                        currentSandwichDay = currentSandwichDay,
                        lockedDays = lockSnapshot,
                        history = historySnapshot,
                        proteinIngredientIds = proteinIngredientIds,
                        weekStart = weekStartSnapshot,
                        weights = weightsSnapshot
                    )
                }

                plan.putAll(result.plan)
                sandwichDay = result.sandwichDay
                shoppingChecked.clear()
                shoppingIncluded.clear()

                persistWeek(clearChecks = true)
            } catch (e: Exception) {
                lastError =
                    "Madplanen kunne ikke opdateres. Prøv igen."
            } finally {
                isPlanning = false
            }
        }
    }

    fun reroll(day: DayOfWeek) {
        if (!canEditWeek() || isPlanning) return
        if (isWeekRegenerationLocked() || isCompletedDayLocked(day)) {
            lastError = "Ugen eller dagen er låst efter registrering af et måltid."
            return
        }
        if (
            day in lockedDays ||
            dayModes[day] == DayMode.AWAY ||
            day == sandwichDay
        ) return

        val recipesSnapshot = recipes.toList()
        val pantrySnapshot = pantry.toMap()
        val modeSnapshot = dayModes.toMap()
        val maxPrepSnapshot = dayMaxPrepMinutes.toMap()
        val planSnapshot = plan.toMap()
        val historySnapshot = history.toList()
        val weightsSnapshot = plannerWeights
        val weekStartSnapshot = displayedWeekStart
        val proteinIngredientIds = ingredients
            .asSequence()
            .filter { it.isProtein }
            .map { it.id }
            .toSet()

        isPlanning = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.Default) {
                    WeeklyPlanner.rerollDay(
                        day = day,
                        recipes = recipesSnapshot,
                        pantry = pantrySnapshot,
                        dayModes = modeSnapshot,
                        maxPrepMinutesByDay = maxPrepSnapshot,
                        weeklySandwichRecipeId = weeklySandwichRecipeId,
                        sandwichDay = sandwichDay,
                        currentPlan = planSnapshot,
                        history = historySnapshot,
                        proteinIngredientIds = proteinIngredientIds,
                        weekStart = weekStartSnapshot,
                        weights = weightsSnapshot
                    )
                }

                if (isWeekRegenerationLocked() || isCompletedDayLocked(day)) return@launch
                plan.putAll(updated)
                shoppingChecked.clear()
                shoppingIncluded.clear()
                persistWeek(clearChecks = true)
            } catch (e: Exception) {
                lastError =
                    "Retten kunne ikke skiftes. Prøv igen."
            } finally {
                isPlanning = false
            }
        }
    }

    fun moveSandwich() {
        if (!canEditWeek()) return
        if (weeklySandwichRecipeId == null) return
        val nextDay = WeekRules.nextSandwichDay(
            current = sandwichDay,
            modes = dayModes.toMap()
        )
        if (sandwichDay?.let(::isCompletedDayLocked) == true ||
            nextDay?.let(::isCompletedDayLocked) == true) {
            lastError = "En spist sandwichdag er låst. Lås dagen op først."
            return
        }

        sandwichDay = nextDay

        shoppingChecked.clear()
        shoppingIncluded.clear()
        persistWeek(clearChecks = true)
    }

    fun sandwichDay(): DayOfWeek? = sandwichDay

    fun canMake(recipe: Recipe): Boolean =
        PantryRules.canMake(
            recipe = recipe,
            pantry = pantry
        )

    fun recipeFor(day: DayOfWeek): Recipe? {
        if (dayModes[day] == DayMode.AWAY) return null

        val id = if (
            day == sandwichDay &&
            weeklySandwichRecipeId != null
        ) {
            weeklySandwichRecipeId
        } else {
            plan[day]
        }

        return id?.let { recipeId ->
            recipes.find { it.id == recipeId }
        }
    }

    fun shoppingItems(): List<ShoppingItem> {
        val totals = mutableMapOf<String, Double>()
        val recipesById = recipes.associateBy { it.id }

        orderedWeekDays.forEach { day ->
            if (dayModes[day] == DayMode.AWAY) return@forEach

            val recipeId = if (
                day == sandwichDay &&
                weeklySandwichRecipeId != null
            ) {
                weeklySandwichRecipeId
            } else {
                plan[day]
            }

            val recipe = recipeId?.let(recipesById::get) ?: return@forEach
            recipe.ingredients.forEach { req ->
                totals[req.ingredientId] =
                    (totals[req.ingredientId] ?: 0.0) + req.amountBase
            }
        }

        selectedLunchRecipeIds.forEach { recipeId ->
            val recipe = recipesById[recipeId]
                ?.takeIf { it.mealType == MealType.LUNCH }
                ?: return@forEach

            recipe.ingredients.forEach { req ->
                totals[req.ingredientId] =
                    (totals[req.ingredientId] ?: 0.0) + req.amountBase
            }
        }

        val ingredientsById = ingredients.associateBy { it.id }
        val categoriesById = ingredientCategories.associateBy { it.id }

        return totals.mapNotNull { (ingredientId, requiredBase) ->
            val ingredient = ingredientsById[ingredientId]
                ?: return@mapNotNull null
            if (!ShoppingIngredientRules.isAutomaticallyPurchased(ingredient)) return@mapNotNull null

            ShoppingItem(
                ingredient = ingredient,
                requiredBase = requiredBase,
                pantryBase = pantry[ingredientId] ?: 0.0
            )
        }.sortedWith(
            compareBy(
                {
                    categoriesById[it.ingredient.shoppingCategoryId]
                        ?.position
                        ?: Int.MAX_VALUE
                },
                {
                    categoriesById[it.ingredient.shoppingCategoryId]
                        ?.name
                        ?.lowercase()
                        ?: "andet"
                },
                { it.ingredient.name.lowercase() }
            )
        )
    }

    fun lunchRecipes(): List<Recipe> {
        val recipesById = recipes.associateBy { it.id }
        return selectedLunchRecipeIds.mapNotNull { recipeId ->
            recipesById[recipeId]
                ?.takeIf { it.mealType == MealType.LUNCH }
        }
    }

    fun addLunchRecipe(recipeId: String) {
        if (!canEditWeek()) return
        val recipe = recipes.find {
            it.id == recipeId && it.mealType == MealType.LUNCH
        } ?: return

        if (recipe.id in selectedLunchRecipeIds) return

        selectedLunchRecipeIds += recipe.id
        shoppingChecked.clear()
        shoppingIncluded.clear()

        val planId = weeklyPlanId ?: return
        enqueueMutations(
            listOf(
                QueuedMutation.ReplaceLunch(
                    weeklyPlanId = planId,
                    recipeIds = selectedLunchRecipeIds.toList()
                ),
                QueuedMutation.ClearShopping(planId)
            )
        )
    }

    fun removeLunchRecipe(recipeId: String) {
        if (!canEditWeek()) return
        if (!selectedLunchRecipeIds.remove(recipeId)) return

        shoppingChecked.clear()
        shoppingIncluded.clear()
        val planId = weeklyPlanId ?: return
        enqueueMutations(
            listOf(
                QueuedMutation.ReplaceLunch(
                    weeklyPlanId = planId,
                    recipeIds = selectedLunchRecipeIds.toList()
                ),
                QueuedMutation.ClearShopping(planId)
            )
        )
    }

    fun isShoppingIncluded(ingredientId: String): Boolean =
        shoppingIncluded[ingredientId] ?: true

    fun setShoppingIncluded(
        ingredientId: String,
        included: Boolean
    ) {
        if (!canEditWeek()) return
        shoppingIncluded[ingredientId] = included
        if (!included) {
            shoppingChecked[ingredientId] = false
        }

        val id = weeklyPlanId ?: return
        enqueueMutation(
            QueuedMutation.SetShoppingIncluded(
                weeklyPlanId = id,
                ingredientId = ingredientId,
                included = included
            )
        )
    }

    fun setShoppingChecked(
        ingredientId: String,
        checked: Boolean
    ) {
        if (!canEditWeek()) return
        if (!isShoppingIncluded(ingredientId) && checked) return
        shoppingChecked[ingredientId] = checked
        val id = weeklyPlanId ?: return
        enqueueMutation(
            QueuedMutation.SetShoppingChecked(
                weeklyPlanId = id,
                ingredientId = ingredientId,
                checked = checked
            )
        )
    }

    fun submitFeedback(
        message: String,
        onResult: (Boolean) -> Unit
    ) {
        val clean = message.trim()
        if (clean.length < 3) {
            onResult(false)
            return
        }

        scope.launch {
            try {
                repository.submitFeedback(clean)
                onResult(true)
            } catch (_: Exception) {
                onResult(false)
            }
        }
    }

    fun formatBaseAmount(
        ingredient: Ingredient,
        amountBase: Double
    ): String {
        val unit = ingredient.preferredUnit
        val value = unit.fromBase(amountBase)
        return "${formatNumber(value)} ${unit.labelFor(value)}"
    }

    /**
     * Navigate between server-backed weekly plans. All pending changes are
     * persisted before switching, and each week has its own plan/checks/locks.
     * The local-only mode keeps the existing single-week behavior for now.
     */
    fun changeDisplayedWeek(offsetWeeks: Long) {
        if (offsetWeeks == 0L || isWeekChanging || isPlanning || isLocalOnly) return
        val target = displayedWeekStart.plusWeeks(offsetWeeks)
        isWeekChanging = true
        scope.launch {
            try {
                mutationQueueMutex.withLock {
                    if (offlineSyncQueue.hasPendingMutations()) {
                        offlineSyncQueue.drainInApp(repository)
                    }
                    if (offlineSyncQueue.hasPendingMutations()) {
                        error("Pending offline mutations")
                    }
                    reloadMutex.withLock {
                        val week = repository.loadWeekSnapshot(target)
                        val shopping = repository.loadShoppingSnapshot(week.weeklyPlanId)
                        Snapshot.withMutableSnapshot {
                            applyWeekSnapshot(week)
                            applyShoppingSnapshot(shopping)
                            displayedWeekStart = target
                            editableCompletedDates.clear()
                            unlockedRegenerationWeeks.clear()
                        }
                        roomCache.save(currentSnapshot())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastError = "Ugen kunne ikke hentes. Tjek forbindelsen og prøv igen."
            } finally {
                isWeekChanging = false
            }
        }
    }

    fun showCurrentWeek() {
        if (isWeekChanging || isPlanning || isLocalOnly) return
        val current = currentWeekStart()
        val delta = java.time.temporal.ChronoUnit.WEEKS.between(displayedWeekStart, current)
        changeDisplayedWeek(delta)
    }

    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )

    private fun formatNumber(value: Double): String =
        amountFormatter.format(value)
}
