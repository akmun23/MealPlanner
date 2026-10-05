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

    val ingredients = mutableStateListOf<Ingredient>()
    val recipes = mutableStateListOf<Recipe>()
    val pantry = mutableStateMapOf<String, Double>()
    val dayModes = mutableStateMapOf<DayOfWeek, DayMode>()
    val plan = mutableStateMapOf<DayOfWeek, String?>()
    val lockedDays = mutableStateSetOf<DayOfWeek>()
    val shoppingChecked = mutableStateMapOf<String, Boolean>()
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

    init {
        orderedWeekDays.forEach { day ->
            dayModes[day] = DayMode.AUTO
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

            reload()
            repository.startRealtime(scope) {
                reload()
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

    private suspend fun reload() {
        reloadMutex.withLock {
            applySnapshot(repository.loadCurrent())
        }
    }

    private fun applySnapshot(snapshot: CloudSnapshot) {
        ingredients.clear()
        ingredients.addAll(snapshot.ingredients)

        recipes.clear()
        recipes.addAll(snapshot.recipes)

        pantry.clear()
        pantry.putAll(snapshot.pantry)

        dayModes.clear()
        orderedWeekDays.forEach { day ->
            dayModes[day] = snapshot.dayModes[day] ?: DayMode.AUTO
        }

        plan.clear()
        orderedWeekDays.forEach { day ->
            plan[day] = snapshot.plan[day]
        }

        lockedDays.clear()
        lockedDays.addAll(snapshot.lockedDays)

        shoppingChecked.clear()
        shoppingChecked.putAll(snapshot.shoppingChecked)

        selectedLunchRecipeIds.clear()
        selectedLunchRecipeIds.addAll(snapshot.selectedLunchRecipeIds)

        history.clear()
        history.addAll(snapshot.history)

        weeklyPlanId = snapshot.weeklyPlanId
        weeklySandwichRecipeId = snapshot.weeklySandwichRecipeId
        sandwichDay = snapshot.sandwichDay
    }

    private fun runMutation(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                lastError = e.message ?: "Ændringen kunne ikke gemmes."
                runCatching { reload() }
            }
        }
    }

    private fun persistWeek(clearChecks: Boolean = false) {
        val id = weeklyPlanId ?: return
        val planSnapshot = plan.toMap()
        val modesSnapshot = dayModes.toMap()
        val locksSnapshot = lockedDays.toSet()
        val sandwichRecipe = weeklySandwichRecipeId
        val selectedSandwichDay = sandwichDay

        runMutation {
            if (clearChecks) {
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
        persistWeek(clearChecks = true)
    }

    fun addIngredient(
        name: String,
        type: QuantityType,
        imageUri: String? = null,
        preferredUnit: MeasurementUnit = MeasurementUnit.defaultFor(type)
    ) {
        val clean = name.trim()
        if (
            clean.isBlank() ||
            preferredUnit.quantityType != type ||
            ingredients.any { it.name.equals(clean, ignoreCase = true) }
        ) return

        val ingredient = Ingredient(
            id = UUID.randomUUID().toString(),
            name = clean,
            quantityType = type,
            preferredUnit = preferredUnit,
            imageUri = imageUri
        )

        ingredients += ingredient

        runMutation {
            val saved = repository.upsertIngredient(ingredient)
            val index = ingredients.indexOfFirst { it.id == saved.id }
            if (index >= 0) ingredients[index] = saved
        }
    }

    fun updateIngredient(
        id: String,
        name: String,
        type: QuantityType,
        preferredUnit: MeasurementUnit,
        imageUri: String? = null
    ) {
        val index = ingredients.indexOfFirst { it.id == id }
        if (
            index < 0 ||
            name.isBlank() ||
            preferredUnit.quantityType != type
        ) return

        val updated = ingredients[index].copy(
            name = name.trim(),
            quantityType = type,
            preferredUnit = preferredUnit,
            imageUri = imageUri
        )
        ingredients[index] = updated

        runMutation {
            val saved = repository.upsertIngredient(updated)
            val current = ingredients.indexOfFirst { it.id == id }
            if (current >= 0) ingredients[current] = saved
        }
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

        runMutation {
            repository.deleteIngredient(
                id = ingredientId,
                imagePath = ingredient.imagePath
            )
        }
    }

    fun addRecipe(
        name: String,
        mealType: MealType,
        protein: String,
        category: RecipeCategory,
        notes: String = "",
        imageUri: String? = null,
        requirements: List<DraftRequirement>
    ) {
        val recipe = buildRecipe(
            id = UUID.randomUUID().toString(),
            name = name,
            mealType = mealType,
            protein = protein,
            category = category,
            notes = notes,
            imageUri = imageUri,
            imagePath = null,
            requirements = requirements
        ) ?: return

        recipes += recipe

        runMutation {
            val saved = repository.upsertRecipe(recipe)
            val index = recipes.indexOfFirst { it.id == saved.id }
            if (index >= 0) recipes[index] = saved
        }
    }

    fun updateRecipe(
        id: String,
        name: String,
        mealType: MealType,
        protein: String,
        category: RecipeCategory,
        notes: String = "",
        imageUri: String? = null,
        requirements: List<DraftRequirement>
    ) {
        val index = recipes.indexOfFirst { it.id == id }
        if (index < 0) return

        val existing = recipes[index]
        val updated = buildRecipe(
            id = id,
            name = name,
            mealType = mealType,
            protein = protein,
            category = category,
            notes = notes,
            imageUri = imageUri,
            imagePath = existing.imagePath,
            requirements = requirements
        ) ?: return

        recipes[index] = updated

        runMutation {
            val saved = repository.upsertRecipe(updated)
            val current = recipes.indexOfFirst { it.id == saved.id }
            if (current >= 0) recipes[current] = saved
        }
    }

    private fun buildRecipe(
        id: String,
        name: String,
        mealType: MealType,
        protein: String,
        category: RecipeCategory,
        notes: String,
        imageUri: String?,
        imagePath: String?,
        requirements: List<DraftRequirement>
    ): Recipe? {
        val clean = name.trim()
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
            proteinTag = protein.trim(),
            categoryTag = category.name,
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

        runMutation {
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

        runMutation {
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
        persistWeek(clearChecks = true)
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

        runMutation {
            repository.setPantry(ingredientId, base)
        }
    }

    fun generateWeek() {
        if (isPlanning || weeklyPlanId == null) return

        val recipesSnapshot = recipes.toList()
        val pantrySnapshot = pantry.toMap()
        val modeSnapshot = dayModes.toMap()
        val planSnapshot = plan.toMap()
        val lockSnapshot = lockedDays.toSet()
        val historySnapshot = history.toList()
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
                        weeklySandwichRecipeId = sandwichRecipe,
                        currentPlan = planSnapshot,
                        currentSandwichDay = currentSandwichDay,
                        lockedDays = lockSnapshot,
                        history = historySnapshot,
                        weekStart = currentWeekStart()
                    )
                }

                plan.putAll(result.plan)
                sandwichDay = result.sandwichDay
                shoppingChecked.clear()

                val id = weeklyPlanId ?: return@launch
                repository.clearShoppingChecks(id)
                repository.saveWeekState(
                    weeklyPlanId = id,
                    plan = plan.toMap(),
                    dayModes = dayModes.toMap(),
                    lockedDays = lockedDays.toSet(),
                    sandwichRecipeId = weeklySandwichRecipeId,
                    sandwichDay = sandwichDay
                )
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
        val planSnapshot = plan.toMap()
        val historySnapshot = history.toList()

        scope.launch {
            try {
                val updated = withContext(Dispatchers.Default) {
                    WeeklyPlanner.rerollDay(
                        day = day,
                        recipes = recipesSnapshot,
                        pantry = pantrySnapshot,
                        dayModes = modeSnapshot,
                        weeklySandwichRecipeId = weeklySandwichRecipeId,
                        sandwichDay = sandwichDay,
                        currentPlan = planSnapshot,
                        history = historySnapshot,
                        weekStart = currentWeekStart()
                    )
                }

                plan.putAll(updated)
                shoppingChecked.clear()
                val id = weeklyPlanId ?: return@launch
                repository.clearShoppingChecks(id)
                repository.saveWeekState(
                    weeklyPlanId = id,
                    plan = plan.toMap(),
                    dayModes = dayModes.toMap(),
                    lockedDays = lockedDays.toSet(),
                    sandwichRecipeId = weeklySandwichRecipeId,
                    sandwichDay = sandwichDay
                )
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
        persistWeek(clearChecks = true)
    }

    fun sandwichDay(): DayOfWeek? = sandwichDay

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

        orderedWeekDays.forEach { day ->
            if (dayModes[day] == DayMode.AWAY) return@forEach

            val recipe = recipeFor(day) ?: return@forEach
            recipe.ingredients.forEach { req ->
                totals[req.ingredientId] =
                    (totals[req.ingredientId] ?: 0.0) + req.amountBase
            }
        }

        lunchRecipes().forEach { recipe ->
            recipe.ingredients.forEach { req ->
                totals[req.ingredientId] =
                    (totals[req.ingredientId] ?: 0.0) + req.amountBase
            }
        }

        return totals.mapNotNull { (ingredientId, requiredBase) ->
            val ingredient = ingredients.find { it.id == ingredientId }
                ?: return@mapNotNull null

            ShoppingItem(
                ingredient = ingredient,
                requiredBase = requiredBase,
                pantryBase = pantry[ingredientId] ?: 0.0
            )
        }.sortedBy { it.ingredient.name.lowercase() }
    }

    fun lunchRecipes(): List<Recipe> =
        selectedLunchRecipeIds.mapNotNull { recipeId ->
            recipes.find {
                it.id == recipeId &&
                    it.mealType == MealType.LUNCH
            }
        }

    fun addLunchRecipe(recipeId: String) {
        val recipe = recipes.find {
            it.id == recipeId && it.mealType == MealType.LUNCH
        } ?: return

        if (recipe.id in selectedLunchRecipeIds) return

        selectedLunchRecipeIds += recipe.id
        shoppingChecked.clear()

        val planId = weeklyPlanId ?: return
        val position = selectedLunchRecipeIds.indexOf(recipe.id)

        runMutation {
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
        val planId = weeklyPlanId ?: return

        runMutation {
            repository.clearShoppingChecks(planId)
            repository.removeLunchRecipe(
                weeklyPlanId = planId,
                recipeId = recipeId
            )
        }
    }

    fun setShoppingChecked(
        ingredientId: String,
        checked: Boolean
    ) {
        shoppingChecked[ingredientId] = checked
        val id = weeklyPlanId ?: return

        runMutation {
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
