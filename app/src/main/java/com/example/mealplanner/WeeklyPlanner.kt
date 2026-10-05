package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.random.Random

object WeeklyPlanner {

    private const val TRIALS = 400
    private const val TOP_POOL = 8
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
                it.autoRotationEnabled
        }

        if (dinnerRecipes.isEmpty() && weeklySandwichRecipeId == null) {
            return PlannerResult(currentPlan, currentSandwichDay)
        }

        val recipesById = recipes.associateBy { it.id }
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

        val candidates = ArrayList<Pair<Double, Map<DayOfWeek, String?>>>(TRIALS)

        repeat(TRIALS) {
            val chosen = currentPlan.toMutableMap()

            homeDays.forEach { day ->
                if (day !in lockedDays) chosen[day] = null
            }

            val usedIds = chosen
                .filterKeys { it in homeDays && it in lockedDays }
                .values
                .filterNotNull()
                .toMutableSet()

            homeDays
                .filter { it !in lockedDays }
                .forEach { day ->
                    val eligible = dinnerRecipes.filter { recipe ->
                        fitsPrepLimit(
                            recipe = recipe,
                            maxPrepMinutes = maxPrepMinutesByDay[day]
                        )
                    }

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
                                    history = history,
                                    proteinIngredientIds = proteinIngredientIds,
                                    targetDate = weekStart.plusDays(day.value.toLong() - 1),
                                    weights = weights
                                ) + randomJitter(weights.jitter, random)
                            )
                        }
                        .sortedByDescending { it.second }

                    val choicePool = ranked.take(max(1, minOf(4, ranked.size)))
                    val recipe = weightedPick(choicePool, random)
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
                history = history,
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
                history = history,
                proteinIngredientIds = proteinIngredientIds,
                weekStart = weekStart,
                previousPlan = currentPlan,
                weights = weights
            ) to improved
        }

        val top = candidates
            .sortedByDescending { it.first }
            .take(TOP_POOL)

        val plan = if (top.isEmpty()) currentPlan else weightedPickPlans(top, random)
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
                it.autoRotationEnabled
        }
        val recipesById = recipes.associateBy { it.id }
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
                    history = history,
                    proteinIngredientIds = proteinIngredientIds,
                    weekStart = weekStart,
                    previousPlan = currentPlan,
                    weights = weights
                ) + randomJitter(weights.jitter, random)

                recipe to score
            }
            .sortedByDescending { it.second }

        val chosen = weightedPick(ranked.take(minOf(4, ranked.size)), random)
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
        history: List<MealHistoryEntry>,
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

        var best = plan.toMutableMap()
        var bestScore = scoreWholeWeek(
            best, sandwichDay, sandwichRecipeId,
            orderedWeekDays.associateWith { if (it in homeDays) DayMode.AUTO else DayMode.AWAY },
            recipesById, pantry, history, proteinIngredientIds, weekStart, previousPlan, weights
        )

        repeat(LOCAL_SEARCH_STEPS) {
            val a = swappable[random.nextInt(swappable.size)]
            var b = swappable[random.nextInt(swappable.size)]
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
                candidate, sandwichDay, sandwichRecipeId,
                orderedWeekDays.associateWith { if (it in homeDays) DayMode.AUTO else DayMode.AWAY },
                recipesById, pantry, history, proteinIngredientIds, weekStart, previousPlan, weights
            )
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }

        return best
    }

    private fun fitsPrepLimit(
        recipe: Recipe,
        maxPrepMinutes: Int?
    ): Boolean {
        if (maxPrepMinutes == null) return true
        return recipe.prepMinutes
            ?.let { it <= maxPrepMinutes }
            ?: false
    }

    private fun incrementalRecipeScore(
        recipe: Recipe,
        chosenRecipeIds: List<String>,
        recipesById: Map<String, Recipe>,
        pantry: Map<String, Double>,
        history: List<MealHistoryEntry>,
        proteinIngredientIds: Set<String>,
        targetDate: LocalDate,
        weights: PlannerWeights
    ): Double {
        val chosenRecipes = chosenRecipeIds.mapNotNull(recipesById::get)
        var score = 0.0

        recipe.ingredients.forEach { req ->
            val available = pantry[req.ingredientId] ?: 0.0
            if (req.amountBase > 0.0) {
                score += minOf(available / req.amountBase, 1.0) * weights.pantryCoverage
            }
        }

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

        score -= recencyPenalty(recipe.id, history, targetDate, weights)
        return score
    }

    private fun scoreWholeWeek(
        underlyingPlan: Map<DayOfWeek, String?>,
        sandwichDay: DayOfWeek?,
        sandwichRecipeId: String?,
        dayModes: Map<DayOfWeek, DayMode>,
        recipesById: Map<String, Recipe>,
        pantry: Map<String, Double>,
        history: List<MealHistoryEntry>,
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

        selected.forEach { recipe ->
            recipe.ingredients.forEach { req ->
                val available = pantry[req.ingredientId] ?: 0.0
                if (req.amountBase > 0.0) {
                    score += minOf(available / req.amountBase, 1.0) * weights.pantryCoverage
                }
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
                history,
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
        recipe.categoryId
            ?: recipe.categoryTag.trim().lowercase()

    private fun recencyPenalty(
        recipeId: String,
        history: List<MealHistoryEntry>,
        targetDate: LocalDate,
        weights: PlannerWeights
    ): Double {
        val latest = history
            .asSequence()
            .filter { it.recipeId == recipeId && !it.eatenOn.isAfter(targetDate) }
            .maxByOrNull { it.eatenOn }
            ?: return 0.0

        return when (ChronoUnit.DAYS.between(latest.eatenOn, targetDate)) {
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

    private fun weightedPick(
        ranked: List<Pair<Recipe, Double>>,
        random: Random
    ): Recipe {
        if (ranked.size == 1) return ranked.first().first
        val minimum = ranked.minOf { it.second }
        val weights = ranked.map { (_, score) -> (score - minimum) + 1.0 }
        val total = weights.sum()
        var roll = random.nextDouble(0.0, total)

        ranked.indices.forEach { index ->
            roll -= weights[index]
            if (roll <= 0.0) return ranked[index].first
        }
        return ranked.first().first
    }

    private fun weightedPickPlans(
        ranked: List<Pair<Double, Map<DayOfWeek, String?>>>,
        random: Random
    ): Map<DayOfWeek, String?> {
        if (ranked.size == 1) return ranked.first().second
        val minimum = ranked.minOf { it.first }
        val weights = ranked.map { (score, _) -> (score - minimum) + 1.0 }
        val total = weights.sum()
        var roll = random.nextDouble(0.0, total)

        ranked.indices.forEach { index ->
            roll -= weights[index]
            if (roll <= 0.0) return ranked[index].second
        }
        return ranked.first().second
    }
}
