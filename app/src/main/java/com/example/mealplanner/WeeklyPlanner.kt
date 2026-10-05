package com.example.mealplanner

import java.time.DayOfWeek
import kotlin.math.max
import kotlin.random.Random

object WeeklyPlanner {

    private const val TRIALS = 700
    private const val TOP_POOL = 10

    fun preferredSandwichDay(dayModes: Map<DayOfWeek, DayMode>): DayOfWeek? {
        val homeDays = orderedWeekDays.filter { dayModes[it] != DayMode.AWAY }
        return sandwichDayPreference.firstOrNull { it in homeDays }
    }

    fun generate(
        recipes: List<Recipe>,
        pantry: Map<String, Double>,
        dayModes: Map<DayOfWeek, DayMode>,
        weeklySandwichRecipeId: String?,
        currentPlan: Map<DayOfWeek, String?>,
        random: Random = Random.Default
    ): Map<DayOfWeek, String?> {
        if (recipes.isEmpty()) return currentPlan

        val sandwichRecipe = weeklySandwichRecipeId?.let { id -> recipes.find { it.id == id } }
        val sandwichDay = preferredSandwichDay(dayModes)
        val homeDays = orderedWeekDays.filter { dayModes[it] != DayMode.AWAY }

        if (homeDays.isEmpty()) {
            return orderedWeekDays.associateWith { null }
        }

        val candidates = mutableListOf<Pair<Double, Map<DayOfWeek, String?>>>()

        repeat(TRIALS) {
            val chosen = mutableMapOf<DayOfWeek, String?>().apply {
                orderedWeekDays.forEach { this[it] = null }
            }
            val usedIds = mutableSetOf<String>()

            if (sandwichRecipe != null && sandwichDay != null) {
                chosen[sandwichDay] = sandwichRecipe.id
                usedIds += sandwichRecipe.id
            }

            val autoDays = homeDays.filter { it != sandwichDay }

            autoDays.forEach { day ->
                val available = recipes.filter {
                    it.id != weeklySandwichRecipeId && it.id !in usedIds
                }.ifEmpty {
                    recipes.filter { it.id != weeklySandwichRecipeId }
                }.ifEmpty {
                    recipes
                }

                val ranked = available
                    .map { recipe ->
                        val incremental = incrementalRecipeScore(
                            recipe = recipe,
                            chosenRecipeIds = chosen.values.filterNotNull(),
                            allRecipes = recipes,
                            pantry = pantry
                        )
                        val jitter = random.nextDouble(0.0, 16.0)
                        recipe to (incremental + jitter)
                    }
                    .sortedByDescending { it.second }

                val choicePool = ranked.take(max(1, minOf(5, ranked.size)))
                val chosenRecipe = weightedPick(choicePool, random)
                chosen[day] = chosenRecipe.id
                usedIds += chosenRecipe.id
            }

            val totalScore = scoreWholeWeek(
                plan = chosen,
                recipes = recipes,
                pantry = pantry,
                previousPlan = currentPlan
            )
            candidates += totalScore to chosen.toMap()
        }

        val top = candidates.sortedByDescending { it.first }.take(TOP_POOL)
        return if (top.isEmpty()) currentPlan else weightedPickPlans(top, random)
    }

    fun rerollDay(
        day: DayOfWeek,
        recipes: List<Recipe>,
        pantry: Map<String, Double>,
        dayModes: Map<DayOfWeek, DayMode>,
        weeklySandwichRecipeId: String?,
        currentPlan: Map<DayOfWeek, String?>,
        random: Random = Random.Default
    ): Map<DayOfWeek, String?> {
        if (dayModes[day] == DayMode.AWAY) return currentPlan
        if (currentPlan[day] == weeklySandwichRecipeId) return currentPlan

        val oldId = currentPlan[day]
        val idsUsedElsewhere = currentPlan
            .filterKeys { it != day }
            .values
            .filterNotNull()
            .toSet()

        val alternatives = recipes.filter {
            it.id != weeklySandwichRecipeId && it.id != oldId && it.id !in idsUsedElsewhere
        }.ifEmpty {
            recipes.filter { it.id != weeklySandwichRecipeId && it.id != oldId }
        }.ifEmpty {
            recipes.filter { it.id != weeklySandwichRecipeId }
        }

        if (alternatives.isEmpty()) return currentPlan

        val ranked = alternatives.map { recipe ->
            val candidate = currentPlan.toMutableMap()
            candidate[day] = recipe.id
            val score = scoreWholeWeek(candidate, recipes, pantry, currentPlan) +
                random.nextDouble(0.0, 8.0)
            score to recipe
        }.sortedByDescending { it.first }

        val choice = weightedPick(
            ranked.take(minOf(5, ranked.size)).map { it.second to it.first },
            random
        )

        return currentPlan.toMutableMap().apply { this[day] = choice.id }
    }

    private fun incrementalRecipeScore(
        recipe: Recipe,
        chosenRecipeIds: List<String>,
        allRecipes: List<Recipe>,
        pantry: Map<String, Double>
    ): Double {
        val chosenRecipes = chosenRecipeIds.mapNotNull { id -> allRecipes.find { it.id == id } }
        var score = 0.0

        recipe.ingredients.forEach { req ->
            val available = pantry[req.ingredientId] ?: 0.0
            if (req.amountBase > 0.0) {
                score += minOf(available / req.amountBase, 1.0) * 24.0
            }
        }

        val ingredientsAlreadyNeeded = chosenRecipes
            .flatMap { it.ingredients }
            .map { it.ingredientId }
            .toSet()

        recipe.ingredients.forEach {
            if (it.ingredientId in ingredientsAlreadyNeeded) score += 8.0
        }

        if (recipe.proteinTag.isNotBlank()) {
            val sameProtein = chosenRecipes.count {
                it.proteinTag.equals(recipe.proteinTag, ignoreCase = true)
            }
            score -= sameProtein * 15.0
        }

        if (recipe.categoryTag.isNotBlank()) {
            val sameCategory = chosenRecipes.count {
                it.categoryTag.equals(recipe.categoryTag, ignoreCase = true)
            }
            score -= sameCategory * 10.0
        }

        return score
    }

    private fun scoreWholeWeek(
        plan: Map<DayOfWeek, String?>,
        recipes: List<Recipe>,
        pantry: Map<String, Double>,
        previousPlan: Map<DayOfWeek, String?>
    ): Double {
        val selected = plan.values.filterNotNull().mapNotNull { id -> recipes.find { it.id == id } }
        if (selected.isEmpty()) return 0.0

        var score = 0.0

        selected.forEach { recipe ->
            recipe.ingredients.forEach { req ->
                val available = pantry[req.ingredientId] ?: 0.0
                if (req.amountBase > 0.0) {
                    score += minOf(available / req.amountBase, 1.0) * 18.0
                }
            }
        }

        val ingredientFrequency = selected
            .flatMap { it.ingredients }
            .groupingBy { it.ingredientId }
            .eachCount()
        ingredientFrequency.values.forEach { count ->
            if (count > 1) score += (count - 1) * 11.0
        }

        val proteinCounts = selected
            .filter { it.proteinTag.isNotBlank() }
            .groupingBy { it.proteinTag.lowercase() }
            .eachCount()
        proteinCounts.values.forEach { count ->
            if (count > 1) score -= (count - 1) * 13.0
        }

        val categoryCounts = selected
            .filter { it.categoryTag.isNotBlank() }
            .groupingBy { it.categoryTag.lowercase() }
            .eachCount()
        categoryCounts.values.forEach { count ->
            if (count > 1) score -= (count - 1) * 9.0
        }

        orderedWeekDays.forEach { day ->
            if (plan[day] != null && plan[day] == previousPlan[day]) score -= 5.0
        }

        val duplicateCount = selected.size - selected.map { it.id }.distinct().size
        score -= duplicateCount * 80.0
        return score
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
