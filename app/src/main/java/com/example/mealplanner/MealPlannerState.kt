package com.example.mealplanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

class MealPlannerState(
    context: Context,
    householdId: String,
    private val scope: CoroutineScope
) {
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
    private val reloadMutex = Mutex()
    private val mutationQueueMutex = Mutex()
    private var cachePersistJob: Job? = null
    private val syncTracker = SyncGenerationTracker()
    private var syncStatusClearJob: Job? = null

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

    var weeklySandwichRecipeId by mutableStateOf<String?>(null)
        private set

    var sandwichDay by mutableStateOf<DayOfWeek?>(null)
        private set

    var weeklyPlanId by mutableStateOf<String?>(null)
        private set

    var isLoading by mutableStateOf(true)
        private set

    var isPlanning by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    internal var syncIndicator by mutableStateOf(SyncIndicator.IDLE)
        private set

    init {
        orderedWeekDays.forEach { day ->
            dayModes[day] = DayMode.AUTO
            dayMaxPrepMinutes[day] = null
            plan[day] = null
        }

        scope.launch {
            observeSyncQueue()
        }

        scope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        var hasCachedSnapshot = false

        runCatching { roomCache.load() }
            .getOrNull()
            ?.let { cached ->
                applySnapshot(cached)
                hasCachedSnapshot = true
                isLoading = false
            }

        val hasPendingMutations =
            runCatching {
                offlineSyncQueue.hasPendingMutations()
            }.getOrDefault(false)

        if (hasPendingMutations) {
            offlineSyncQueue.schedule()
        }

        try {
            if (!hasPendingMutations || !hasCachedSnapshot) {
                reload(syncHistory = true)
            }
            repository.startRealtime(scope) { event ->
                handleRealtime(event)
            }
        } catch (e: Exception) {
            if (hasCachedSnapshot) {
                syncIndicator = if (hasPendingMutations) {
                    SyncIndicator.PENDING
                } else {
                    SyncIndicator.OFFLINE
                }
            } else {
                lastError = e.message ?: "Kunne ikke hente data."
            }
        } finally {
            isLoading = false
        }
    }

    private suspend fun observeSyncQueue() {
        var previousCount = 0

        combine(
            offlineSyncQueue.observePendingCount(),
            offlineSyncQueue.observeFailedCount()
        ) { count, failedCount ->
            count to failedCount
        }.collectLatest { (count, failedCount) ->
            when {
                failedCount > 0 -> {
                    syncStatusClearJob?.cancel()
                    syncIndicator = SyncIndicator.ERROR
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
                    delay(250L)

                    if (
                        !offlineSyncQueue.hasPendingMutations() &&
                        syncTracker.isCurrent(generationSnapshot)
                    ) {
                        runCatching {
                            reload(
                                syncHistory = false,
                                expectedGenerations =
                                    generationSnapshot
                            )
                        }
                    }

                    syncStatusClearJob?.cancel()
                    syncStatusClearJob = scope.launch {
                        delay(1200L)
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

    suspend fun close() {
        cachePersistJob?.join()
        repository.stopRealtime()
    }

    fun clearError() {
        lastError = null
    }

    private suspend fun reload(
        syncHistory: Boolean = false,
        expectedGenerations: Map<CloudSyncDomain, Long>? = null
    ) {
        val snapshot = repository.loadCurrent(
            syncHistory = syncHistory
        )

        var applied = false
        reloadMutex.withLock {
            if (
                expectedGenerations != null &&
                !syncTracker.isCurrent(expectedGenerations)
            ) {
                return@withLock
            }

            applySnapshot(snapshot)
            applied = true
        }

        if (applied) {
            runCatching {
                roomCache.save(snapshot)
            }
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
                    val week = repository.loadWeekSnapshot()
                    val shopping = repository.loadShoppingSnapshot(
                        week.weeklyPlanId
                    )
                    if (!syncTracker.isCurrent(generationSnapshot)) {
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

    private fun applySnapshot(snapshot: CloudSnapshot) {
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
        weeklySandwichRecipeId = snapshot.weeklySandwichRecipeId
        sandwichDay = snapshot.sandwichDay
    }

    private fun applyIngredientsSnapshot(
        snapshot: CloudIngredientsSnapshot
    ) {
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
    ) {
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
    ) {
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
    ) {
        syncMap(
            target = shoppingChecked,
            incoming = snapshot.checked
        )
        syncMap(
            target = shoppingIncluded,
            incoming = snapshot.included
        )
    }

    private fun currentSnapshot(): CloudSnapshot? {
        val planId = weeklyPlanId ?: return null

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
            history = history.toList()
        )
    }

    private fun scheduleCachePersist(
        delayMillis: Long = 100L
    ) {
        val snapshot = currentSnapshot() ?: return

        cachePersistJob?.cancel()
        cachePersistJob = scope.launch {
            delay(delayMillis)
            runCatching {
                roomCache.save(snapshot)
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

        scope.launch {
            mutationQueueMutex.withLock {
                try {
                    offlineSyncQueue.enqueue(
                        snapshot = snapshot,
                        mutations = mutations
                    )
                } catch (e: Exception) {
                    lastError =
                        e.message
                            ?: "Ændringen kunne ikke gemmes lokalt."
                    syncIndicator = SyncIndicator.ERROR
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

    fun toggleLockDay(day: DayOfWeek) {
        if (dayModes[day] == DayMode.AWAY || recipeFor(day) == null) return

        if (day in lockedDays) {
            lockedDays.remove(day)
        } else {
            lockedDays.add(day)
        }
        persistWeek()
    }

    fun setRecipeForDay(day: DayOfWeek, recipeId: String?) {
        if (day == sandwichDay) return

        plan[day] = recipeId
        if (recipeId != null) {
            dayModes[day] = DayMode.AUTO
        }
        shoppingChecked.clear()
        shoppingIncluded.clear()
        persistWeek(clearChecks = true)
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
        preferredUnit: MeasurementUnit = MeasurementUnit.defaultFor(type)
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
            imageUri = imageUri
        )

        ingredients += ingredient
        enqueueMutation(
            QueuedMutation.UpsertIngredient(ingredient)
        )
        return true
    }

    fun updateIngredient(
        id: String,
        name: String,
        type: QuantityType,
        shoppingCategoryId: String?,
        isProtein: Boolean,
        preferredUnit: MeasurementUnit,
        imageUri: String? = null
    ): Boolean {
        val index = ingredients.indexOfFirst { it.id == id }
        if (
            index < 0 ||
            name.isBlank() ||
            preferredUnit.quantityType != type
        ) return false

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

    fun recipeCategoryName(recipe: Recipe): String =
        recipeCategories
            .find { it.id == recipe.categoryId }
            ?.name
            ?: "Andet"

    fun recipeProteinNames(recipe: Recipe): List<String> {
        val ingredientsById = ingredients.associateBy { it.id }
        return recipe.ingredients
            .mapNotNull { ingredientsById[it.ingredientId] }
            .filter { it.isProtein }
            .map { it.name }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    fun deleteIngredient(ingredientId: String) {
        if (recipes.any { recipe ->
                recipe.ingredients.any { it.ingredientId == ingredientId } ||
                    recipe.pantryPrerequisites.any {
                        it.ingredientId == ingredientId
                    }
            }
        ) {
            lastError = "Ingrediensen bruges i en opskrift."
            return
        }

        val ingredient = ingredients.find { it.id == ingredientId } ?: return
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
        requirements: List<DraftRequirement>
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
            requirements = requirements
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
        requirements: List<DraftRequirement>
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
            requirements = requirements
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
        requirements: List<DraftRequirement>
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
            imageUri = imageUri,
            imagePath = imagePath,
            ingredients = converted,
            pantryPrerequisites = convertedPrerequisites
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
        if (weeklySandwichRecipeId == null && sandwichDay == null) return

        weeklySandwichRecipeId = null
        sandwichDay = null
        shoppingChecked.clear()
        shoppingIncluded.clear()

        persistWeek(clearChecks = true)
    }

    fun setWeeklySandwichRecipe(recipeId: String) {
        if (recipes.none { it.id == recipeId && it.mealType == MealType.DINNER }) return

        weeklySandwichRecipeId = recipeId
        if (
            sandwichDay == null ||
            sandwichDay?.let { dayModes[it] == DayMode.AWAY } == true
        ) {
            sandwichDay = WeeklyPlanner.preferredSandwichDay(dayModes)
        }

        shoppingChecked.clear()
        shoppingIncluded.clear()
        persistWeek(
            clearChecks = true,
            updateHouseholdSandwich = true
        )
    }

    fun toggleEatingAtHome(day: DayOfWeek) {
        val result = WeekRules.toggleHome(
            day = day,
            modes = dayModes.toMap(),
            currentSandwichDay = sandwichDay,
            hasSandwichRecipe = weeklySandwichRecipeId != null
        )

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

        val base = unit.toBase(amount.coerceAtLeast(0.0))
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
        if (unit.quantityType != ingredient.quantityType || amount <= 0.0) return

        val addedBase = unit.toBase(amount)
        val next = (pantry[ingredientId] ?: 0.0) + addedBase
        pantry[ingredientId] = next

        enqueueMutation(
            QueuedMutation.SetPantry(
                ingredientId = ingredientId,
                amountBase = next
            )
        )
    }

    fun consumeRecipeIngredients(recipeId: String) {
        val recipe = recipes.find { it.id == recipeId } ?: return
        val updates = recipe.ingredients.associate { requirement ->
            val current = pantry[requirement.ingredientId] ?: 0.0
            requirement.ingredientId to
                (current - requirement.amountBase).coerceAtLeast(0.0)
        }

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

    fun generateWeek() {
        if (isPlanning || weeklyPlanId == null) return

        val recipesSnapshot = recipes.toList()
        val pantrySnapshot = pantry.toMap()
        val modeSnapshot = dayModes.toMap()
        val maxPrepSnapshot = dayMaxPrepMinutes.toMap()
        val planSnapshot = plan.toMap()
        val lockSnapshot = lockedDays.toSet()
        val historySnapshot = history.toList()
        val proteinIngredientIds = ingredients
            .asSequence()
            .filter { it.isProtein }
            .map { it.id }
            .toSet()
        val sandwichRecipe = weeklySandwichRecipeId
        val currentSandwichDay = sandwichDay

        scope.launch {
            isPlanning = true
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
                        weekStart = currentWeekStart()
                    )
                }

                plan.putAll(result.plan)
                sandwichDay = result.sandwichDay
                shoppingChecked.clear()
                shoppingIncluded.clear()

                persistWeek(clearChecks = true)
            } catch (e: Exception) {
                lastError = e.message ?: "Madplanen kunne ikke opdateres."
            } finally {
                isPlanning = false
            }
        }
    }

    fun reroll(day: DayOfWeek) {
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
        val proteinIngredientIds = ingredients
            .asSequence()
            .filter { it.isProtein }
            .map { it.id }
            .toSet()

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
                        weekStart = currentWeekStart()
                    )
                }

                plan.putAll(updated)
                shoppingChecked.clear()
                shoppingIncluded.clear()
                persistWeek(clearChecks = true)
            } catch (e: Exception) {
                lastError = e.message ?: "Retten kunne ikke skiftes."
            }
        }
    }

    fun moveSandwich() {
        if (weeklySandwichRecipeId == null) return

        sandwichDay = WeekRules.nextSandwichDay(
            current = sandwichDay,
            modes = dayModes.toMap()
        )

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

            ShoppingItem(
                ingredient = ingredient,
                requiredBase = requiredBase,
                pantryBase = pantry[ingredientId] ?: 0.0
            )
        }.sortedWith(
            compareBy<ShoppingItem>(
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
            } catch (e: Exception) {
                lastError = e.message ?: "Forslaget kunne ikke sendes."
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

    fun formatEnteredAmount(
        amount: Double,
        unit: MeasurementUnit
    ): String =
        "${formatNumber(amount)} ${unit.labelFor(amount)}"

    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )

    private fun formatNumber(value: Double): String {
        val formatter = NumberFormat
            .getNumberInstance(Locale.forLanguageTag("da-DK"))
            .apply {
                maximumFractionDigits = 2
                minimumFractionDigits = 0
                isGroupingUsed = false
            }

        return formatter.format(value)
    }
}
