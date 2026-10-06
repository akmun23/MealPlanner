package com.example.mealplanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val legacyStorage = LocalStorage(context.applicationContext)
    private val reloadMutex = Mutex()
    private val syncTracker = SyncGenerationTracker()
    private val coalescedMutations = mutableMapOf<String, Job>()
    private var pendingSaveCount = 0
    private var syncHadFailure = false
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

    var syncIndicator by mutableStateOf(SyncIndicator.IDLE)
        private set

    init {
        orderedWeekDays.forEach { day ->
            dayModes[day] = DayMode.AUTO
            dayMaxPrepMinutes[day] = null
            plan[day] = null
        }

        scope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        try {
            if (repository.needsLocalImport()) {
                val localSnapshot = legacyStorage.load()
                if (localSnapshot != null) {
                    repository.importLocal(localSnapshot)
                } else {
                    repository.markLocalImportCompleted()
                }
            }

            reload(syncHistory = true)
            repository.startRealtime(scope) { event ->
                handleRealtime(event)
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Kunne ikke hente data."
        } finally {
            isLoading = false
        }
    }

    suspend fun close() {
        repository.stopRealtime()
    }

    fun clearError() {
        lastError = null
    }

    private suspend fun reload(
        syncHistory: Boolean = false
    ) {
        reloadMutex.withLock {
            applySnapshot(
                repository.loadCurrent(syncHistory = syncHistory)
            )
        }
    }

    private suspend fun reload(domain: CloudSyncDomain) {
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
                }

                CloudSyncDomain.RECIPES -> {
                    val snapshot = repository.loadRecipesSnapshot()
                    if (!syncTracker.isCurrent(generationSnapshot)) {
                        return@withLock
                    }
                    applyRecipesSnapshot(snapshot)
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
                }
            }
        }
    }

    private suspend fun handleRealtime(
        event: CloudRealtimeEvent
    ) {
        when (event) {
            is CloudRealtimeEvent.DomainChanged -> {
                reload(event.domain)
            }

            is CloudRealtimeEvent.PantryChanged -> {
                val key = SyncMutationKey(
                    domain = CloudSyncDomain.PANTRY,
                    entityId = "pantry:${event.ingredientId}"
                )
                if (syncTracker.isPending(key)) return

                if (event.amountBase == null) {
                    pantry.remove(event.ingredientId)
                } else if (
                    pantry[event.ingredientId] != event.amountBase
                ) {
                    pantry[event.ingredientId] = event.amountBase
                }
            }

            is CloudRealtimeEvent.ShoppingCheckedChanged -> {
                if (event.weeklyPlanId != weeklyPlanId) return
                val key = SyncMutationKey(
                    domain = CloudSyncDomain.SHOPPING,
                    entityId = "checked:${event.ingredientId}"
                )
                if (syncTracker.isPending(key)) return

                if (event.checked == null) {
                    shoppingChecked.remove(event.ingredientId)
                } else if (
                    shoppingChecked[event.ingredientId] != event.checked
                ) {
                    shoppingChecked[event.ingredientId] =
                        event.checked
                }
            }

            is CloudRealtimeEvent.ShoppingIncludedChanged -> {
                if (event.weeklyPlanId != weeklyPlanId) return
                val key = SyncMutationKey(
                    domain = CloudSyncDomain.SHOPPING,
                    entityId = "included:${event.ingredientId}"
                )
                if (
                    syncTracker.isPending(key) ||
                    syncTracker.isDomainPending(
                        CloudSyncDomain.SHOPPING
                    )
                ) return

                if (event.included == null) {
                    shoppingIncluded.remove(event.ingredientId)
                } else if (
                    shoppingIncluded[event.ingredientId] !=
                    event.included
                ) {
                    shoppingIncluded[event.ingredientId] =
                        event.included
                }
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

    private fun beginSave() {
        syncStatusClearJob?.cancel()
        syncStatusClearJob = null
        if (pendingSaveCount == 0) {
            syncHadFailure = false
        }
        pendingSaveCount += 1
        syncIndicator = SyncIndicator.SAVING
    }

    private fun finishSave(success: Boolean?) {
        if (success == false) {
            syncHadFailure = true
        }
        pendingSaveCount =
            (pendingSaveCount - 1).coerceAtLeast(0)

        if (pendingSaveCount > 0) {
            syncIndicator = SyncIndicator.SAVING
            return
        }

        syncIndicator = if (syncHadFailure) {
            SyncIndicator.ERROR
        } else {
            SyncIndicator.SAVED
        }

        syncStatusClearJob = scope.launch {
            delay(if (syncHadFailure) 2500L else 1200L)
            if (pendingSaveCount == 0) {
                syncIndicator = SyncIndicator.IDLE
            }
        }
    }

    private fun runMutation(
        domains: Set<CloudSyncDomain>,
        keys: Set<SyncMutationKey> = emptySet(),
        block: suspend () -> Unit
    ) {
        syncTracker.markLocalChange(domains)
        syncTracker.beginPending(keys)
        beginSave()

        scope.launch {
            var success: Boolean? = false
            try {
                block()
                success = true
            } catch (e: CancellationException) {
                success = null
                throw e
            } catch (e: Exception) {
                lastError =
                    e.message ?: "Ændringen kunne ikke gemmes."
                domains.forEach { domain ->
                    runCatching { reload(domain) }
                }
            } finally {
                syncTracker.endPending(keys)
                finishSave(success)
            }
        }
    }

    private fun scheduleMutation(
        coalesceKey: String,
        domains: Set<CloudSyncDomain>,
        keys: Set<SyncMutationKey> = emptySet(),
        delayMillis: Long = 180L,
        block: suspend () -> Unit
    ) {
        syncTracker.markLocalChange(domains)
        syncTracker.beginPending(keys)
        beginSave()

        val previous = coalescedMutations.remove(coalesceKey)

        val job = scope.launch {
            var success: Boolean? = false
            try {
                delay(delayMillis)
                block()
                success = true
            } catch (e: CancellationException) {
                success = null
                throw e
            } catch (e: Exception) {
                lastError =
                    e.message ?: "Ændringen kunne ikke gemmes."
                domains.forEach { domain ->
                    runCatching { reload(domain) }
                }
            } finally {
                syncTracker.endPending(keys)
                finishSave(success)
            }
        }

        coalescedMutations[coalesceKey] = job
        job.invokeOnCompletion {
            scope.launch {
                if (coalescedMutations[coalesceKey] === job) {
                    coalescedMutations.remove(coalesceKey)
                }
            }
        }
        previous?.cancel()
    }

    private fun persistWeek(clearChecks: Boolean = false) {
        val id = weeklyPlanId ?: return
        val planSnapshot = plan.toMap()
        val modesSnapshot = dayModes.toMap()
        val locksSnapshot = lockedDays.toSet()
        val sandwichRecipe = weeklySandwichRecipeId
        val selectedSandwichDay = sandwichDay

        scheduleMutation(
            coalesceKey = "week-state",
            domains = if (clearChecks) {
                setOf(
                    CloudSyncDomain.WEEK,
                    CloudSyncDomain.SHOPPING
                )
            } else {
                setOf(CloudSyncDomain.WEEK)
            },
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.WEEK,
                    entityId = "week:" + id
                )
            )
        ) {
            if (clearChecks) {
                shoppingIncluded.clear()
                repository.clearShoppingChecks(id)
            }
            repository.saveWeekState(
                weeklyPlanId = id,
                plan = planSnapshot,
                dayModes = modesSnapshot,
                lockedDays = locksSnapshot,
                sandwichRecipeId = sandwichRecipe,
                sandwichDay = selectedSandwichDay
            )
        }
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

        runMutation(
            domains = setOf(CloudSyncDomain.INGREDIENTS),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.INGREDIENTS,
                    entityId = "ingredient:" + ingredient.id
                )
            )
        ) {
            val saved = repository.upsertIngredient(ingredient)
            val index = ingredients.indexOfFirst { it.id == saved.id }
            if (index >= 0) ingredients[index] = saved
        }
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

        runMutation(
            domains = setOf(CloudSyncDomain.INGREDIENTS),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.INGREDIENTS,
                    entityId = "ingredient:" + id
                )
            )
        ) {
            val saved = repository.upsertIngredient(updated)
            val current = ingredients.indexOfFirst { it.id == id }
            if (current >= 0) ingredients[current] = saved
        }
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

        val nextPosition =
            (ingredientCategories.maxOfOrNull { it.position } ?: -1) + 1

        runMutation(
            domains = setOf(CloudSyncDomain.INGREDIENTS)
        ) {
            val saved = repository.createIngredientCategory(
                name = clean,
                position = nextPosition
            )
            if (ingredientCategories.none { it.id == saved.id }) {
                ingredientCategories += saved
                ingredientCategories.sortWith(
                    compareBy<IngredientCategory> { it.position }
                        .thenBy { it.name.lowercase() }
                )
            }
        }
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

        ingredientCategories[index] =
            ingredientCategories[index].copy(name = clean)

        runMutation(
            domains = setOf(CloudSyncDomain.INGREDIENTS),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.INGREDIENTS,
                    entityId = "ingredient-category:" + categoryId
                )
            )
        ) {
            repository.renameIngredientCategory(
                categoryId = categoryId,
                name = clean
            )
        }
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

        val nextPosition =
            (recipeCategories.maxOfOrNull { it.position } ?: -1) + 1

        runMutation(
            domains = setOf(CloudSyncDomain.RECIPES)
        ) {
            val saved = repository.createRecipeCategory(
                name = clean,
                position = nextPosition
            )
            if (recipeCategories.none { it.id == saved.id }) {
                recipeCategories += saved
                recipeCategories.sortWith(
                    compareBy<RecipeCategoryOption> { it.position }
                        .thenBy { it.name.lowercase() }
                )
            }
        }
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

        recipeCategories[index] =
            recipeCategories[index].copy(name = clean)

        runMutation(
            domains = setOf(CloudSyncDomain.RECIPES),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.RECIPES,
                    entityId = "recipe-category:" + categoryId
                )
            )
        ) {
            repository.renameRecipeCategory(
                categoryId = categoryId,
                name = clean
            )
        }
        return true
    }

    fun recipeCategoryName(recipe: Recipe): String =
        recipeCategories
            .find { it.id == recipe.categoryId }
            ?.name
            ?: recipe.categoryTag
                .toRecipeCategory()
                .danishName()

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
                recipe.ingredients.any { it.ingredientId == ingredientId }
            }
        ) {
            lastError = "Ingrediensen bruges i en opskrift."
            return
        }

        val ingredient = ingredients.find { it.id == ingredientId } ?: return
        ingredients.removeAll { it.id == ingredientId }
        pantry.remove(ingredientId)
        shoppingChecked.remove(ingredientId)

        runMutation(
            domains = setOf(
                CloudSyncDomain.INGREDIENTS,
                CloudSyncDomain.PANTRY,
                CloudSyncDomain.SHOPPING
            ),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.INGREDIENTS,
                    entityId = "ingredient:" + ingredientId
                ),
                SyncMutationKey(
                    domain = CloudSyncDomain.PANTRY,
                    entityId = "pantry:" + ingredientId
                )
            )
        ) {
            repository.deleteIngredient(
                id = ingredientId,
                imagePath = ingredient.imagePath
            )
        }
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
            requirements = requirements
        ) ?: return null

        recipes += recipe

        runMutation(
            domains = setOf(CloudSyncDomain.RECIPES),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.RECIPES,
                    entityId = "recipe:" + recipe.id
                )
            )
        ) {
            val saved = repository.upsertRecipe(recipe)
            val index = recipes.indexOfFirst { it.id == saved.id }
            if (index >= 0) recipes[index] = saved
        }
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

        runMutation(
            domains = buildSet {
                add(CloudSyncDomain.RECIPES)
                if (weekChanged) {
                    add(CloudSyncDomain.WEEK)
                    add(CloudSyncDomain.SHOPPING)
                }
            },
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.RECIPES,
                    entityId = "recipe:" + id
                )
            )
        ) {
            val saved = repository.upsertRecipe(updated)
            val current = recipes.indexOfFirst { it.id == saved.id }
            if (current >= 0) recipes[current] = saved

            val currentPlanId = weeklyPlanId
            if (removedFromLunch && currentPlanId != null) {
                repository.removeLunchRecipe(
                    weeklyPlanId = currentPlanId,
                    recipeId = id
                )
            }

            if (weekChanged && currentPlanId != null) {
                repository.setHouseholdSandwich(weeklySandwichRecipeId)
                repository.clearShoppingChecks(currentPlanId)
                repository.saveWeekState(
                    weeklyPlanId = currentPlanId,
                    plan = plan.toMap(),
                    dayModes = dayModes.toMap(),
                    lockedDays = lockedDays.toSet(),
                    sandwichRecipeId = weeklySandwichRecipeId,
                    sandwichDay = sandwichDay
                )
            }
        }
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
        requirements: List<DraftRequirement>
    ): Recipe? {
        val clean = name.normalizedDisplayName()
        val cleanPrepMinutes = prepMinutes?.takeIf { it in 1..1440 }
        val selectedCategory = recipeCategories.find { it.id == categoryId }
        if (clean.isBlank() || requirements.isEmpty()) return null

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

        return Recipe(
            id = id,
            name = clean,
            mealType = mealType,
            autoRotationEnabled = autoRotationEnabled,
            prepMinutes = cleanPrepMinutes,
            proteinTag = "",
            categoryTag = selectedCategory?.name ?: "Andet",
            categoryId = selectedCategory?.id,
            notes = notes.trim(),
            imageUri = imageUri,
            imagePath = imagePath,
            ingredients = converted
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

        runMutation(
            domains = setOf(
                CloudSyncDomain.RECIPES,
                CloudSyncDomain.WEEK,
                CloudSyncDomain.SHOPPING
            ),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.RECIPES,
                    entityId = "recipe:" + recipeId
                )
            )
        ) {
            repository.deleteRecipe(
                id = recipeId,
                imagePath = recipe.imagePath
            )
            repository.setHouseholdSandwich(weeklySandwichRecipeId)
            weeklyPlanId?.let {
                repository.clearShoppingChecks(it)
                repository.saveWeekState(
                    weeklyPlanId = it,
                    plan = plan.toMap(),
                    dayModes = dayModes.toMap(),
                    lockedDays = lockedDays.toSet(),
                    sandwichRecipeId = weeklySandwichRecipeId,
                    sandwichDay = sandwichDay
                )
            }
        }
    }

    fun removeSandwichThisWeek() {
        if (weeklySandwichRecipeId == null && sandwichDay == null) return

        weeklySandwichRecipeId = null
        sandwichDay = null
        shoppingChecked.clear()
        shoppingIncluded.clear()

        val id = weeklyPlanId ?: return
        runMutation(
            domains = setOf(
                CloudSyncDomain.WEEK,
                CloudSyncDomain.SHOPPING
            ),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.WEEK,
                    entityId = "week:" + id
                )
            )
        ) {
            repository.clearShoppingChecks(id)
            repository.saveWeekState(
                weeklyPlanId = id,
                plan = plan.toMap(),
                dayModes = dayModes.toMap(),
                lockedDays = lockedDays.toSet(),
                sandwichRecipeId = null,
                sandwichDay = null
            )
        }
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

        runMutation(
            domains = setOf(
                CloudSyncDomain.WEEK,
                CloudSyncDomain.SHOPPING
            )
        ) {
            repository.setHouseholdSandwich(recipeId)
            weeklyPlanId?.let {
                repository.clearShoppingChecks(it)
                repository.saveWeekState(
                    weeklyPlanId = it,
                    plan = plan.toMap(),
                    dayModes = dayModes.toMap(),
                    lockedDays = lockedDays.toSet(),
                    sandwichRecipeId = weeklySandwichRecipeId,
                    sandwichDay = sandwichDay
                )
            }
        }
    }

    fun toggleEatingAtHome(day: DayOfWeek) {
        val result = WeekRules.toggleHome(
            day = day,
            modes = dayModes.toMap(),
            currentSandwichDay = sandwichDay,
            hasSandwichRecipe = weeklySandwichRecipeId != null
        )

        dayModes.clear()
        dayModes.putAll(result.modes)
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
        scheduleMutation(
            coalesceKey = "day-prep:" + day.value,
            domains = setOf(CloudSyncDomain.WEEK),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.WEEK,
                    entityId = "day-prep:" + day.value
                )
            )
        ) {
            repository.setDayPrepLimit(
                weeklyPlanId = planId,
                day = day,
                maxPrepMinutes = normalized
            )
        }
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

        scheduleMutation(
            coalesceKey = "pantry:" + ingredientId,
            domains = setOf(CloudSyncDomain.PANTRY),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.PANTRY,
                    entityId = "pantry:" + ingredientId
                )
            )
        ) {
            repository.setPantry(ingredientId, base)
        }
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

        scheduleMutation(
            coalesceKey = "pantry:" + ingredientId,
            domains = setOf(CloudSyncDomain.PANTRY),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.PANTRY,
                    entityId = "pantry:" + ingredientId
                )
            )
        ) {
            repository.setPantry(ingredientId, next)
        }
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

        runMutation(
            domains = setOf(CloudSyncDomain.PANTRY),
            keys = updates.keys.mapTo(mutableSetOf()) {
                SyncMutationKey(
                    domain = CloudSyncDomain.PANTRY,
                    entityId = "pantry:" + it
                )
            }
        ) {
            repository.setPantryBatch(updates)
        }
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

                val id = weeklyPlanId ?: return@launch
                runMutation(
                    domains = setOf(
                        CloudSyncDomain.WEEK,
                        CloudSyncDomain.SHOPPING
                    ),
                    keys = setOf(
                        SyncMutationKey(
                            domain = CloudSyncDomain.WEEK,
                            entityId = "week:" + id
                        )
                    )
                ) {
                    repository.clearShoppingChecks(id)
                    repository.saveWeekState(
                        weeklyPlanId = id,
                        plan = plan.toMap(),
                        dayModes = dayModes.toMap(),
                        lockedDays = lockedDays.toSet(),
                        sandwichRecipeId = weeklySandwichRecipeId,
                        sandwichDay = sandwichDay
                    )
                }
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
                val id = weeklyPlanId ?: return@launch
                runMutation(
                    domains = setOf(
                        CloudSyncDomain.WEEK,
                        CloudSyncDomain.SHOPPING
                    ),
                    keys = setOf(
                        SyncMutationKey(
                            domain = CloudSyncDomain.WEEK,
                            entityId = "week:" + id
                        )
                    )
                ) {
                    repository.clearShoppingChecks(id)
                    repository.saveWeekState(
                        weeklyPlanId = id,
                        plan = plan.toMap(),
                        dayModes = dayModes.toMap(),
                        lockedDays = lockedDays.toSet(),
                        sandwichRecipeId = weeklySandwichRecipeId,
                        sandwichDay = sandwichDay
                    )
                }
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
        val position = selectedLunchRecipeIds.indexOf(recipe.id)

        runMutation(
            domains = setOf(
                CloudSyncDomain.WEEK,
                CloudSyncDomain.SHOPPING
            )
        ) {
            repository.clearShoppingChecks(planId)
            repository.addLunchRecipe(
                weeklyPlanId = planId,
                recipeId = recipe.id,
                position = position
            )
        }
    }

    fun removeLunchRecipe(recipeId: String) {
        if (!selectedLunchRecipeIds.remove(recipeId)) return

        shoppingChecked.clear()
        shoppingIncluded.clear()
        val planId = weeklyPlanId ?: return

        runMutation(
            domains = setOf(
                CloudSyncDomain.WEEK,
                CloudSyncDomain.SHOPPING
            )
        ) {
            repository.clearShoppingChecks(planId)
            repository.removeLunchRecipe(
                weeklyPlanId = planId,
                recipeId = recipeId
            )
        }
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
        scheduleMutation(
            coalesceKey = "shopping-included:" + ingredientId,
            domains = setOf(CloudSyncDomain.SHOPPING),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.SHOPPING,
                    entityId = "included:" + ingredientId
                ),
                SyncMutationKey(
                    domain = CloudSyncDomain.SHOPPING,
                    entityId = "checked:" + ingredientId
                )
            )
        ) {
            repository.setShoppingIncluded(
                weeklyPlanId = id,
                ingredientId = ingredientId,
                included = included
            )
            if (!included) {
                repository.setShoppingChecked(
                    weeklyPlanId = id,
                    ingredientId = ingredientId,
                    checked = false
                )
            }
        }
    }

    fun setShoppingChecked(
        ingredientId: String,
        checked: Boolean
    ) {
        if (!isShoppingIncluded(ingredientId) && checked) return
        shoppingChecked[ingredientId] = checked
        val id = weeklyPlanId ?: return

        scheduleMutation(
            coalesceKey = "shopping-checked:" + ingredientId,
            domains = setOf(CloudSyncDomain.SHOPPING),
            keys = setOf(
                SyncMutationKey(
                    domain = CloudSyncDomain.SHOPPING,
                    entityId = "checked:" + ingredientId
                )
            )
        ) {
            repository.setShoppingChecked(
                weeklyPlanId = id,
                ingredientId = ingredientId,
                checked = checked
            )
        }
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
