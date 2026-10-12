package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

object WeeklyPlanner {

    private const val TRIALS = 400
    private const val TOP_POOL = 64
    private const val LOCAL_SEARCH_STEPS = 18

    fun preferredSandwichDay(dayModes: Map<DayOfWeek, DayMode>): DayOfWeek? {
        val homeDays = orderedWeekDays.filter { dayModes[it] != DayMode.AWAY }
        return sandwichDayPreference.firstOrNull { it in homeDays }
    }

    fun generate(
        recipes: List<Recipe>,
        pantry: Map<String, Double>,
        dayModes: Map<DayOfWeek, DayMode>,
        maxPrepMinutesByDay: Map<DayOfWeek, Int?> = emptyMap(),
        weeklySandwichRecipeId: String?,
        currentPlan: Map<DayOfWeek, String?>,
        currentSandwichDay: DayOfWeek?,
        lockedDays: Set<DayOfWeek> = emptySet(),
        history: List<MealHistoryEntry> = emptyList(),
        proteinIngredientIds: Set<String> = emptySet(),
        weekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY),
        weights: PlannerWeights = PlannerWeights(),
        random: Random = Random.Default
    ): PlannerResult {
        val dinnerRecipes = recipes.filter {
            it.mealType == MealType.DINNER &&
                    it.autoRotationEnabled &&
                    hasRequiredPantryStock(it, pantry)
        }

        if (dinnerRecipes.isEmpty() && weeklySandwichRecipeId == null) {
            return PlannerResult(currentPlan, currentSandwichDay)
        }

        val recipesById = recipes.associateBy { it.id }
        val historyByRecipe = indexHistory(history)
        val homeDays = orderedWeekDays.filter { dayModes[it] != DayMode.AWAY }

        if (homeDays.isEmpty()) {
            return PlannerResult(currentPlan, null)
        }

        val sandwichRecipe = weeklySandwichRecipeId
            ?.let(recipesById::get)
            ?.takeIf { it.mealType == MealType.DINNER }
        val sandwichDay = when {
            sandwichRecipe == null -> null
            currentSandwichDay != null &&
                    currentSandwichDay in lockedDays &&
                    currentSandwichDay in homeDays -> currentSandwichDay

            else -> sandwichDayPreference.firstOrNull { day ->
                day in homeDays &&
                        fitsPrepLimit(
                            recipe = sandwichRecipe,
                            maxPrepMinutes = maxPrepMinutesByDay[day]
                        )
            }
        }

        // Preparation constraints do not change between trials. Avoid
        // refiltering all recipes on every simulated day (400 trials).
        val unlockedHomeDays = homeDays.filter { it !in lockedDays }
        val eligibleByDay = unlockedHomeDays.associateWith { day ->
            dinnerRecipes.filter { recipe ->
                fitsPrepLimit(recipe, maxPrepMinutesByDay[day])
            }
        }
        val candidates = ArrayList<Pair<Double, Map<DayOfWeek, String?>>>(TRIALS)

        repeat(TRIALS) {
            val chosen = currentPlan.toMutableMap()

            unlockedHomeDays.forEach { day -> chosen[day] = null }

            val usedIds = chosen
                .filterKeys { it in homeDays && it in lockedDays }
                .values
                .filterNotNull()
                .toMutableSet()

            unlockedHomeDays.forEach { day ->
                val eligible = eligibleByDay.getValue(day)

                val available = eligible.filter {
                    it.id != weeklySandwichRecipeId && it.id !in usedIds
                }.ifEmpty {
                    eligible.filter { it.id != weeklySandwichRecipeId }
                }.ifEmpty {
                    eligible
                }

                if (available.isEmpty()) return@forEach

                val ranked = available
                    .map { recipe ->
                        recipe to (
                                incrementalRecipeScore(
                                    recipe = recipe,
                                    chosenRecipeIds = chosen.values.filterNotNull(),
                                    recipesById = recipesById,
                                    pantry = pantry,
                                    historyByRecipe = historyByRecipe,
                                    proteinIngredientIds = proteinIngredientIds,
                                    targetDate = weekStart.plusDays(day.value.toLong() - 1),
                                    weights = weights
                                ) + randomJitter(weights.jitter, random)
                                )
                    }
                    .sortedByDescending { it.second }

                // Sample every eligible recipe; a top-four cutoff kept recycling
                // the same few meals, even when many more were available.
                val recipe = weightedPick(ranked, random, weights.jitter)
                chosen[day] = recipe.id
                usedIds += recipe.id
            }

            val improved = localImprove(
                plan = chosen,
                homeDays = homeDays,
                lockedDays = lockedDays,
                sandwichDay = sandwichDay,
                sandwichRecipeId = weeklySandwichRecipeId,
                recipesById = recipesById,
                maxPrepMinutesByDay = maxPrepMinutesByDay,
                pantry = pantry,
                historyByRecipe = historyByRecipe,
                proteinIngredientIds = proteinIngredientIds,
                weekStart = weekStart,
                previousPlan = currentPlan,
                weights = weights,
                random = random
            )

            candidates += scoreWholeWeek(
                underlyingPlan = improved,
                sandwichDay = sandwichDay,
                sandwichRecipeId = weeklySandwichRecipeId,
                dayModes = dayModes,
                recipesById = recipesById,
                pantry = pantry,
                historyByRecipe = historyByRecipe,
                proteinIngredientIds = proteinIngredientIds,
                weekStart = weekStart,
                previousPlan = currentPlan,
                weights = weights
            ) to improved
        }

        val top = candidates
            .sortedByDescending { it.first }
            // Different day orders of the same meals should not monopolize
            // the shortlist when a fresh collection of recipes is available.
            .distinctBy { (_, candidate) ->
                unlockedHomeDays
                    .filter { it != sandwichDay }
                    .mapNotNull { candidate[it] }
                    .sorted()
            }
            .take(TOP_POOL)

        val plan = if (top.isEmpty()) currentPlan else {
            weightedPickPlans(top, random, weights.jitter)
        }
        return PlannerResult(plan, sandwichDay)
    }

    fun rerollDay(
        day: DayOfWeek,
        recipes: List<Recipe>,
        pantry: Map<String, Double>,
        dayModes: Map<DayOfWeek, DayMode>,
        maxPrepMinutesByDay: Map<DayOfWeek, Int?> = emptyMap(),
        weeklySandwichRecipeId: String?,
        sandwichDay: DayOfWeek?,
        currentPlan: Map<DayOfWeek, String?>,
        history: List<MealHistoryEntry> = emptyList(),
        proteinIngredientIds: Set<String> = emptySet(),
        weekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY),
        weights: PlannerWeights = PlannerWeights(),
        random: Random = Random.Default
    ): Map<DayOfWeek, String?> {
        if (dayModes[day] == DayMode.AWAY || day == sandwichDay) return currentPlan

        val dinnerRecipes = recipes.filter {
            it.mealType == MealType.DINNER &&
                    it.autoRotationEnabled &&
                    hasRequiredPantryStock(it, pantry)
        }
        val recipesById = recipes.associateBy { it.id }
        val historyByRecipe = indexHistory(history)
        val oldId = currentPlan[day]
        val usedElsewhere = orderedWeekDays
            .filter { it != day && dayModes[it] != DayMode.AWAY }
            .mapNotNull { currentPlan[it] }
            .toSet()

        val eligible = dinnerRecipes.filter {
            fitsPrepLimit(
                recipe = it,
                maxPrepMinutes = maxPrepMinutesByDay[day]
            )
        }

        val alternatives = eligible.filter {
            it.id != weeklySandwichRecipeId &&
                    it.id != oldId &&
                    it.id !in usedElsewhere
        }.ifEmpty {
            eligible.filter {
                it.id != weeklySandwichRecipeId && it.id != oldId
            }
        }

        if (alternatives.isEmpty()) return currentPlan

        val ranked = alternatives
            .map { recipe ->
                val candidate = currentPlan.toMutableMap().apply {
                    this[day] = recipe.id
                }
                val score = scoreWholeWeek(
                    underlyingPlan = candidate,
                    sandwichDay = sandwichDay,
                    sandwichRecipeId = weeklySandwichRecipeId,
                    dayModes = dayModes,
                    recipesById = recipesById,
                    pantry = pantry,
                    historyByRecipe = historyByRecipe,
                    proteinIngredientIds = proteinIngredientIds,
                    weekStart = weekStart,
                    previousPlan = currentPlan,
                    weights = weights
                ) + randomJitter(weights.jitter, random)

                recipe to score
            }
            .sortedByDescending { it.second }

        val chosen = weightedPick(ranked, random, weights.jitter)
        return currentPlan.toMutableMap().apply { this[day] = chosen.id }
    }

    private fun localImprove(
        plan: Map<DayOfWeek, String?>,
        homeDays: List<DayOfWeek>,
        lockedDays: Set<DayOfWeek>,
        sandwichDay: DayOfWeek?,
        sandwichRecipeId: String?,
        recipesById: Map<String, Recipe>,
        maxPrepMinutesByDay: Map<DayOfWeek, Int?>,
        pantry: Map<String, Double>,
        historyByRecipe: Map<String, List<LocalDate>>,
        proteinIngredientIds: Set<String>,
        weekStart: LocalDate,
        previousPlan: Map<DayOfWeek, String?>,
        weights: PlannerWeights,
        random: Random
    ): Map<DayOfWeek, String?> {
        val swappable = homeDays.filter {
            it !in lockedDays && it != sandwichDay
        }
        if (swappable.size < 2) return plan
        val activeDayModes = orderedWeekDays.associateWith {
            if (it in homeDays) DayMode.AUTO else DayMode.AWAY
        }

        var best = plan.toMutableMap()
        var bestScore = scoreWholeWeek(
            best,
            sandwichDay,
            sandwichRecipeId,
            activeDayModes,
            recipesById,
            pantry,
            historyByRecipe,
            proteinIngredientIds,
            weekStart,
            previousPlan,
            weights
        )

        repeat(LOCAL_SEARCH_STEPS) {
            val a = swappable[random.nextInt(swappable.size)]
            val b = swappable[random.nextInt(swappable.size)]
            if (a == b) return@repeat

            val candidate = best.toMutableMap()
            val tmp = candidate[a]
            candidate[a] = candidate[b]
            candidate[b] = tmp

            val respectsTimeLimits = listOf(a, b).all { day ->
                val recipe = candidate[day]
                    ?.let(recipesById::get)
                recipe == null || fitsPrepLimit(
                    recipe = recipe,
                    maxPrepMinutes = maxPrepMinutesByDay[day]
                )
            }
            if (!respectsTimeLimits) return@repeat

            val score = scoreWholeWeek(
                candidate,
                sandwichDay,
                sandwichRecipeId,
                activeDayModes,
                recipesById,
                pantry,
                historyByRecipe,
                proteinIngredientIds,
                weekStart,
                previousPlan,
                weights
            )
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }

        return best
    }

    internal fun hasRequiredPantryStock(
        recipe: Recipe,
        pantry: Map<String, Double>
    ): Boolean =
        recipe.pantryPrerequisites.all { prerequisite ->
            val available = pantry[prerequisite.ingredientId] ?: 0.0
            available + 0.00001 >= prerequisite.minimumAmountBase
        }

    private fun fitsPrepLimit(
        recipe: Recipe,
        maxPrepMinutes: Int?
    ): Boolean {
        return (maxPrepMinutes == null || recipe.prepMinutes
            ?.let { it <= maxPrepMinutes }
                ?: false)
    }

    private fun incrementalRecipeScore(
        recipe: Recipe,
        chosenRecipeIds: List<String>,
        recipesById: Map<String, Recipe>,
        pantry: Map<String, Double>,
        historyByRecipe: Map<String, List<LocalDate>>,
        proteinIngredientIds: Set<String>,
        targetDate: LocalDate,
        weights: PlannerWeights
    ): Double {
        val chosenRecipes = chosenRecipeIds.mapNotNull(recipesById::get)
        var score = 0.0

        score += PantryRules.coveredIngredientUnits(recipe, pantry) * weights.pantryCoverage

        val alreadyNeeded = chosenRecipes
            .flatMap { it.ingredients }
            .map { it.ingredientId }
            .toSet()

        recipe.ingredients.forEach {
            if (it.ingredientId in alreadyNeeded) score += weights.ingredientReuse
        }

        val recipeProteins = proteinIds(
            recipe = recipe,
            proteinIngredientIds = proteinIngredientIds
        )
        if (recipeProteins.isNotEmpty()) {
            val sameProtein = chosenRecipes.count { chosen ->
                proteinIds(chosen, proteinIngredientIds)
                    .any { it in recipeProteins }
            }
            score -= sameProtein * weights.repeatedProtein
        }

        val recipeCategory = categoryKey(recipe)
        if (recipeCategory.isNotBlank()) {
            val sameCategory = chosenRecipes.count {
                categoryKey(it) == recipeCategory
            }
            score -= sameCategory * weights.repeatedCategory
        }

        score -= recencyPenalty(recipe.id, historyByRecipe, targetDate, weights)
        return score
    }

    private fun scoreWholeWeek(
        underlyingPlan: Map<DayOfWeek, String?>,
        sandwichDay: DayOfWeek?,
        sandwichRecipeId: String?,
        dayModes: Map<DayOfWeek, DayMode>,
        recipesById: Map<String, Recipe>,
        pantry: Map<String, Double>,
        historyByRecipe: Map<String, List<LocalDate>>,
        proteinIngredientIds: Set<String>,
        weekStart: LocalDate,
        previousPlan: Map<DayOfWeek, String?>,
        weights: PlannerWeights
    ): Double {
        val visible = orderedWeekDays.mapNotNull { day ->
            if (dayModes[day] == DayMode.AWAY) return@mapNotNull null
            val id = if (day == sandwichDay && sandwichRecipeId != null) {
                sandwichRecipeId
            } else {
                underlyingPlan[day]
            }
            id?.let { day to recipesById[it] }
        }

        if (visible.isEmpty()) return 0.0

        var score = 0.0
        val selected = visible.mapNotNull { it.second }

        val previousRecipeIds = orderedWeekDays
            .filter { dayModes[it] != DayMode.AWAY && it != sandwichDay }
            .mapNotNull { previousPlan[it] }
            .toSet()

        selected.forEach { recipe ->
            score += PantryRules.coveredIngredientUnits(recipe, pantry) * weights.pantryCoverage
            // Avoid returning the same collection of meals after "Ny madplan".
            // Keep the sandwich exempt because it is fixed by the user.
            if (recipe.id != sandwichRecipeId && recipe.id in previousRecipeIds) {
                score -= weights.previousPlanReuse
            }
        }

        selected
            .flatMap { it.ingredients }
            .groupingBy { it.ingredientId }
            .eachCount()
            .values
            .forEach { count ->
                if (count > 1) score += (count - 1) * weights.ingredientReuse
            }

        visible.forEachIndexed { index, (day, recipe) ->
            if (recipe == null) return@forEachIndexed
            score -= recencyPenalty(
                recipe.id,
                historyByRecipe,
                weekStart.plusDays(day.value.toLong() - 1),
                weights
            )

            if (index > 0) {
                val (previousDay, previous) = visible[index - 1]
                val isAdjacentCalendarDay = day.value == previousDay.value + 1

                if (
                    isAdjacentCalendarDay &&
                    previous != null &&
                    proteinIds(recipe, proteinIngredientIds)
                        .intersect(
                            proteinIds(previous, proteinIngredientIds)
                        )
                        .isNotEmpty()
                ) {
                    score -= weights.adjacentProtein
                }
                if (
                    isAdjacentCalendarDay &&
                    previous != null &&
                    categoryKey(recipe).isNotBlank() &&
                    categoryKey(recipe) == categoryKey(previous)
                ) {
                    score -= weights.adjacentCategory
                }
            }
        }

        selected
            .flatMap { proteinIds(it, proteinIngredientIds) }
            .groupingBy { it }
            .eachCount()
            .values
            .forEach { count ->
                if (count > 1) {
                    score -= (count - 1) * weights.repeatedProtein
                }
            }

        selected
            .map(::categoryKey)
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .values
            .forEach { count ->
                if (count > 1) score -= (count - 1) * weights.repeatedCategory
            }

        orderedWeekDays.forEach { day ->
            if (
                dayModes[day] != DayMode.AWAY &&
                underlyingPlan[day] != null &&
                underlyingPlan[day] == previousPlan[day]
            ) {
                score -= weights.keepSameDay
            }
        }

        val duplicateCount = selected.size - selected.map { it.id }.distinct().size
        score -= duplicateCount * weights.duplicateRecipe

        return score
    }

    private fun proteinIds(
        recipe: Recipe,
        proteinIngredientIds: Set<String>
    ): Set<String> =
        recipe.ingredients
            .asSequence()
            .map { it.ingredientId }
            .filter { it in proteinIngredientIds }
            .toSet()

    private fun categoryKey(recipe: Recipe): String =
        recipe.categoryId.orEmpty()

    // Build once per planning operation, rather than scanning all meal history
    // for every recipe in every search trial and scoring pass.
    private fun indexHistory(
        history: List<MealHistoryEntry>
    ): Map<String, List<LocalDate>> =
        history.groupBy { it.recipeId }
            .mapValues { (_, meals) ->
                meals.map { it.eatenOn }.sortedDescending()
            }

    private fun recencyPenalty(
        recipeId: String,
        historyByRecipe: Map<String, List<LocalDate>>,
        targetDate: LocalDate,
        weights: PlannerWeights
    ): Double {
        val latest = historyByRecipe[recipeId]
            ?.firstOrNull { !it.isAfter(targetDate) }
            ?: return 0.0

        return when (ChronoUnit.DAYS.between(latest, targetDate)) {
            in 0..7 -> weights.recency7Days
            in 8..14 -> weights.recency14Days
            in 15..30 -> weights.recency30Days
            else -> 0.0
        }
    }

    private fun randomJitter(
        maximum: Double,
        random: Random
    ): Double =
        if (maximum > 0.0) {
            random.nextDouble(0.0, maximum)
        } else {
            0.0
        }

    // Exponential score weights keep strongly disfavoured recipes rare while
    // still letting similarly scored recipes throughout the entire list compete.
    private fun scoreWeight(score: Double, best: Double, jitter: Double): Double {
        val temperature = max(1.0, jitter * 2.0)
        return exp(((score - best) / temperature).coerceAtLeast(-40.0))
    }

    private fun weightedPick(
        ranked: List<Pair<Recipe, Double>>,
        random: Random,
        jitter: Double
    ): Recipe {
        if (ranked.size == 1) return ranked.first().first
        val best = ranked.maxOf { it.second }
        val weights = ranked.map { (_, score) -> scoreWeight(score, best, jitter) }
        val total = weights.sum()
        var roll = random.nextDouble(0.0, total)

        ranked.indices.forEach { index ->
            roll -= weights[index]
            if (roll <= 0.0) return ranked[index].first
        }
        return ranked.last().first
    }

    private fun weightedPickPlans(
        ranked: List<Pair<Double, Map<DayOfWeek, String?>>>,
        random: Random,
        jitter: Double
    ): Map<DayOfWeek, String?> {
        if (ranked.size == 1) return ranked.first().second
        val best = ranked.maxOf { it.first }
        val weights = ranked.map { (score, _) -> scoreWeight(score, best, jitter) }
        val total = weights.sum()
        var roll = random.nextDouble(0.0, total)

        ranked.indices.forEach { index ->
            roll -= weights[index]
            if (roll <= 0.0) return ranked[index].second
        }
        return ranked.last().second
    }
}
