package com.example.mealplanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.NumberFormat
import java.time.DayOfWeek
import java.util.Locale

class MealPlannerState(context: Context) {

    private val storage = LocalStorage(context)

    val ingredients = mutableStateListOf<Ingredient>()
    val recipes = mutableStateListOf<Recipe>()
    val pantry = mutableStateMapOf<String, Double>()
    val dayModes = mutableStateMapOf<DayOfWeek, DayMode>()
    val plan = mutableStateMapOf<DayOfWeek, String?>()
    val shoppingChecked = mutableStateMapOf<String, Boolean>()

    var weeklySandwichRecipeId by mutableStateOf<String?>(null)
        private set

    init {
        orderedWeekDays.forEach { day ->
            dayModes[day] = DayMode.AUTO
            plan[day] = null
        }

        storage.load()?.let { snapshot ->
            ingredients.addAll(snapshot.ingredients)
            recipes.addAll(snapshot.recipes)
            pantry.putAll(snapshot.pantry)
            dayModes.putAll(snapshot.dayModes)
            plan.putAll(snapshot.plan)
            weeklySandwichRecipeId = snapshot.weeklySandwichRecipeId
            shoppingChecked.putAll(snapshot.shoppingChecked)
        }

        orderedWeekDays.forEach { day ->
            if (dayModes[day] == DayMode.FIXED) dayModes[day] = DayMode.AUTO
        }

        if (recipes.isEmpty()) createTestData()
        save()
    }

    fun addIngredient(name: String, type: QuantityType, imageUri: String? = null) {
        val clean = name.trim()
        if (clean.isBlank()) return
        if (ingredients.any { it.name.equals(clean, ignoreCase = true) }) return

        ingredients += Ingredient(
            name = clean,
            quantityType = type,
            preferredUnit = MeasurementUnit.defaultFor(type),
            imageUri = imageUri
        )
        save()
    }

    fun updateIngredient(
        id: String,
        name: String,
        type: QuantityType,
        preferredUnit: MeasurementUnit,
        imageUri: String? = null
    ) {
        val index = ingredients.indexOfFirst { it.id == id }
        if (index == -1) return
        val clean = name.trim()
        if (clean.isBlank()) return

        ingredients[index] = ingredients[index].copy(
            name = clean,
            quantityType = type,
            preferredUnit = preferredUnit,
            imageUri = imageUri
        )
        save()
    }

    fun deleteIngredient(ingredientId: String) {
        ingredients.removeAll { it.id == ingredientId }
        pantry.remove(ingredientId)
        shoppingChecked.remove(ingredientId)
        save()
    }

    fun addRecipe(
        name: String,
        protein: String,
        category: String,
        notes: String = "",
        imageUri: String? = null,
        requirements: List<DraftRequirement>
    ) {
        val clean = name.trim()
        if (clean.isBlank() || requirements.isEmpty()) return

        val converted = requirements.mapNotNull { draft ->
            val ingredient = ingredients.find { it.id == draft.ingredientId } ?: return@mapNotNull null
            if (draft.unit.quantityType != ingredient.quantityType) return@mapNotNull null
            RecipeIngredient(
                ingredientId = draft.ingredientId,
                amountBase = draft.unit.toBase(draft.amount)
            )
        }

        if (converted.isEmpty()) return
        recipes += Recipe(
            name = clean,
            proteinTag = protein.trim(),
            categoryTag = category.trim(),
            notes = notes.trim(),
            imageUri = imageUri,
            ingredients = converted
        )
        save()
    }

    fun updateRecipe(
        id: String,
        name: String,
        protein: String,
        category: String,
        notes: String = "",
        imageUri: String? = null,
        requirements: List<DraftRequirement>
    ) {
        val index = recipes.indexOfFirst { it.id == id }
        if (index == -1) return
        val clean = name.trim()
        if (clean.isBlank() || requirements.isEmpty()) return

        val converted = requirements.mapNotNull { draft ->
            val ingredient = ingredients.find { it.id == draft.ingredientId } ?: return@mapNotNull null
            if (draft.unit.quantityType != ingredient.quantityType) return@mapNotNull null
            RecipeIngredient(
                ingredientId = draft.ingredientId,
                amountBase = draft.unit.toBase(draft.amount)
            )
        }

        if (converted.isEmpty()) return
        recipes[index] = recipes[index].copy(
            name = clean,
            proteinTag = protein.trim(),
            categoryTag = category.trim(),
            notes = notes.trim(),
            imageUri = imageUri,
            ingredients = converted
        )
        save()
    }

    fun deleteRecipe(recipeId: String) {
        recipes.removeAll { it.id == recipeId }
        plan.keys.forEach { day -> if (plan[day] == recipeId) plan[day] = null }
        if (weeklySandwichRecipeId == recipeId) weeklySandwichRecipeId = null
        save()
    }

    fun setWeeklySandwichRecipe(recipeId: String) {
        if (recipes.none { it.id == recipeId }) return
        val previousSandwichId = weeklySandwichRecipeId
        weeklySandwichRecipeId = recipeId

        if (previousSandwichId != null && previousSandwichId != recipeId) {
            plan.keys.forEach { day ->
                if (plan[day] == previousSandwichId) plan[day] = null
            }
        }

        if (plan.values.any { it != null }) repositionSandwichToPreferredDay(fillVacatedDay = true)
        save()
    }

    fun toggleEatingAtHome(day: DayOfWeek) {
        val wasSandwichDay = plan[day] == weeklySandwichRecipeId
        val newMode = if (dayModes[day] == DayMode.AWAY) DayMode.AUTO else DayMode.AWAY
        dayModes[day] = newMode

        if (newMode == DayMode.AWAY) {
            plan[day] = null
            if (wasSandwichDay) repositionSandwichToPreferredDay(fillVacatedDay = false)
        } else {
            if (plan.values.any { it != null } && weeklySandwichRecipeId != null) {
                repositionSandwichToPreferredDay(fillVacatedDay = true)
                if (plan[day] == null) fillDay(day)
            }
        }
        shoppingChecked.clear()
        save()
    }

    fun setPantry(ingredientId: String, amount: Double, unit: MeasurementUnit) {
        val ingredient = ingredients.find { it.id == ingredientId } ?: return
        if (unit.quantityType != ingredient.quantityType) return
        pantry[ingredientId] = unit.toBase(amount.coerceAtLeast(0.0))
        save()
    }

    fun generateWeek() {
        plan.putAll(
            WeeklyPlanner.generate(
                recipes = recipes,
                pantry = pantry,
                dayModes = dayModes,
                weeklySandwichRecipeId = weeklySandwichRecipeId,
                currentPlan = plan
            )
        )
        shoppingChecked.clear()
        save()
    }

    fun reroll(day: DayOfWeek) {
        plan.putAll(
            WeeklyPlanner.rerollDay(
                day = day,
                recipes = recipes,
                pantry = pantry,
                dayModes = dayModes,
                weeklySandwichRecipeId = weeklySandwichRecipeId,
                currentPlan = plan
            )
        )
        shoppingChecked.clear()
        save()
    }

    fun moveSandwich() {
        val sandwichId = weeklySandwichRecipeId ?: return
        val currentDay = sandwichDay() ?: return
        val candidateDays = sandwichDayPreference.filter {
            it != currentDay && dayModes[it] != DayMode.AWAY
        }
        val target = candidateDays.firstOrNull() ?: return

        val targetRecipe = plan[target]
        plan[target] = sandwichId
        plan[currentDay] = targetRecipe
        if (plan[currentDay] == null && dayModes[currentDay] != DayMode.AWAY) fillDay(currentDay)
        shoppingChecked.clear()
        save()
    }

    fun sandwichDay(): DayOfWeek? {
        val sandwichId = weeklySandwichRecipeId ?: return null
        return orderedWeekDays.firstOrNull { plan[it] == sandwichId && dayModes[it] != DayMode.AWAY }
    }

    fun recipeFor(day: DayOfWeek): Recipe? =
        plan[day]?.let { id -> recipes.find { it.id == id } }

    fun shoppingItems(): List<ShoppingItem> {
        val totals = mutableMapOf<String, Double>()
        orderedWeekDays.forEach { day ->
            val recipe = recipeFor(day) ?: return@forEach
            recipe.ingredients.forEach { req ->
                totals[req.ingredientId] = (totals[req.ingredientId] ?: 0.0) + req.amountBase
            }
        }

        return totals.mapNotNull { (ingredientId, requiredBase) ->
            val ingredient = ingredients.find { it.id == ingredientId } ?: return@mapNotNull null
            ShoppingItem(
                ingredient = ingredient,
                requiredBase = requiredBase,
                pantryBase = pantry[ingredientId] ?: 0.0
            )
        }.sortedBy { it.ingredient.name.lowercase() }
    }

    fun setShoppingChecked(ingredientId: String, checked: Boolean) {
        shoppingChecked[ingredientId] = checked
        save()
    }

    fun formatBaseAmount(ingredient: Ingredient, amountBase: Double): String {
        val unit = ingredient.preferredUnit
        val value = unit.fromBase(amountBase)
        return "${formatNumber(value)} ${unit.labelFor(value)}"
    }

    fun formatEnteredAmount(amount: Double, unit: MeasurementUnit): String =
        "${formatNumber(amount)} ${unit.labelFor(amount)}"

    private fun formatNumber(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale("da", "DK")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
            isGroupingUsed = false
        }
        return formatter.format(value)
    }

    private fun repositionSandwichToPreferredDay(fillVacatedDay: Boolean) {
        val sandwichId = weeklySandwichRecipeId ?: return
        val target = WeeklyPlanner.preferredSandwichDay(dayModes) ?: return
        val current = sandwichDay()

        if (current == target) return

        val targetRecipe = plan[target]
        plan[target] = sandwichId

        if (current != null) {
            plan[current] = targetRecipe
            if (fillVacatedDay && plan[current] == null && dayModes[current] != DayMode.AWAY) {
                fillDay(current)
            }
        }
    }

    private fun fillDay(day: DayOfWeek) {
        if (dayModes[day] == DayMode.AWAY) return
        if (plan[day] != null) return
        plan.putAll(
            WeeklyPlanner.rerollDay(
                day = day,
                recipes = recipes,
                pantry = pantry,
                dayModes = dayModes,
                weeklySandwichRecipeId = weeklySandwichRecipeId,
                currentPlan = plan
            )
        )
    }

    private fun createTestData() {
        ingredients.clear()
        recipes.clear()
        pantry.clear()

        fun ingredient(
            name: String,
            type: QuantityType,
            preferredUnit: MeasurementUnit = MeasurementUnit.defaultFor(type)
        ): Ingredient {
            val item = Ingredient(name = name, quantityType = type, preferredUnit = preferredUnit)
            ingredients += item
            return item
        }

        val hakketOkse = ingredient("Hakket oksekød", QuantityType.MASS, MeasurementUnit.GRAM)
        val kylling = ingredient("Kyllingebryst", QuantityType.MASS, MeasurementUnit.GRAM)
        val bacon = ingredient("Bacon", QuantityType.MASS, MeasurementUnit.GRAM)
        val pasta = ingredient("Pasta", QuantityType.MASS, MeasurementUnit.GRAM)
        val ris = ingredient("Ris", QuantityType.MASS, MeasurementUnit.GRAM)
        val kartofler = ingredient("Kartofler", QuantityType.MASS, MeasurementUnit.GRAM)
        val revetOst = ingredient("Revet ost", QuantityType.MASS, MeasurementUnit.GRAM)
        val floede = ingredient("Fløde", QuantityType.VOLUME, MeasurementUnit.MILLILITRE)
        val olie = ingredient("Olivenolie", QuantityType.VOLUME, MeasurementUnit.MILLILITRE)
        val tomat = ingredient("Tomat", QuantityType.COUNT, MeasurementUnit.PIECE)
        val loeg = ingredient("Løg", QuantityType.COUNT, MeasurementUnit.PIECE)
        val hvidloeg = ingredient("Hvidløg", QuantityType.COUNT, MeasurementUnit.PIECE)
        val peberfrugt = ingredient("Peberfrugt", QuantityType.COUNT, MeasurementUnit.PIECE)
        val aeg = ingredient("Æg", QuantityType.COUNT, MeasurementUnit.PIECE)
        val tortilla = ingredient("Tortilla", QuantityType.COUNT, MeasurementUnit.PIECE)
        val burgerbolle = ingredient("Burgerbolle", QuantityType.COUNT, MeasurementUnit.PIECE)
        val sandwichbroed = ingredient("Sandwichbrød", QuantityType.COUNT, MeasurementUnit.PIECE)
        val hakketTomat = ingredient("Hakkede tomater", QuantityType.PACKAGE, MeasurementUnit.CAN)
        val kokosmaelk = ingredient("Kokosmælk", QuantityType.PACKAGE, MeasurementUnit.CAN)
        val kidneyboenner = ingredient("Kidneybønner", QuantityType.PACKAGE, MeasurementUnit.CAN)

        fun req(item: Ingredient, amount: Double, unit: MeasurementUnit) =
            RecipeIngredient(item.id, unit.toBase(amount))

        val sandwich = Recipe(
            name = "Sandwich med kylling og bacon",
            proteinTag = "kylling",
            categoryTag = "sandwich",
            ingredients = listOf(
                req(sandwichbroed, 4.0, MeasurementUnit.PIECE),
                req(kylling, 300.0, MeasurementUnit.GRAM),
                req(bacon, 100.0, MeasurementUnit.GRAM),
                req(tomat, 2.0, MeasurementUnit.PIECE),
                req(revetOst, 100.0, MeasurementUnit.GRAM)
            )
        )
        recipes += sandwich

        recipes += Recipe(
            name = "Spaghetti bolognese", proteinTag = "oksekød", categoryTag = "pasta",
            ingredients = listOf(
                req(hakketOkse, 500.0, MeasurementUnit.GRAM),
                req(pasta, 400.0, MeasurementUnit.GRAM),
                req(hakketTomat, 2.0, MeasurementUnit.CAN),
                req(loeg, 1.0, MeasurementUnit.PIECE),
                req(hvidloeg, 2.0, MeasurementUnit.PIECE)
            )
        )

        recipes += Recipe(
            name = "Kylling i karry", proteinTag = "kylling", categoryTag = "karry",
            ingredients = listOf(
                req(kylling, 500.0, MeasurementUnit.GRAM),
                req(ris, 350.0, MeasurementUnit.GRAM),
                req(kokosmaelk, 1.0, MeasurementUnit.CAN),
                req(loeg, 1.0, MeasurementUnit.PIECE)
            )
        )

        recipes += Recipe(
            name = "Tacos med oksekød", proteinTag = "oksekød", categoryTag = "tacos",
            ingredients = listOf(
                req(hakketOkse, 400.0, MeasurementUnit.GRAM),
                req(tortilla, 8.0, MeasurementUnit.PIECE),
                req(tomat, 3.0, MeasurementUnit.PIECE),
                req(loeg, 1.0, MeasurementUnit.PIECE),
                req(revetOst, 150.0, MeasurementUnit.GRAM)
            )
        )

        recipes += Recipe(
            name = "Pasta carbonara", proteinTag = "svinekød", categoryTag = "pasta",
            ingredients = listOf(
                req(pasta, 400.0, MeasurementUnit.GRAM),
                req(bacon, 200.0, MeasurementUnit.GRAM),
                req(aeg, 4.0, MeasurementUnit.PIECE),
                req(revetOst, 100.0, MeasurementUnit.GRAM)
            )
        )

        recipes += Recipe(
            name = "Chili con carne", proteinTag = "oksekød", categoryTag = "gryderet",
            ingredients = listOf(
                req(hakketOkse, 500.0, MeasurementUnit.GRAM),
                req(ris, 300.0, MeasurementUnit.GRAM),
                req(hakketTomat, 2.0, MeasurementUnit.CAN),
                req(kidneyboenner, 2.0, MeasurementUnit.CAN),
                req(loeg, 1.0, MeasurementUnit.PIECE)
            )
        )

        recipes += Recipe(
            name = "Kyllingefajitas", proteinTag = "kylling", categoryTag = "tacos",
            ingredients = listOf(
                req(kylling, 500.0, MeasurementUnit.GRAM),
                req(tortilla, 8.0, MeasurementUnit.PIECE),
                req(peberfrugt, 3.0, MeasurementUnit.PIECE),
                req(loeg, 1.0, MeasurementUnit.PIECE)
            )
        )

        recipes += Recipe(
            name = "Hjemmelavede burgere", proteinTag = "oksekød", categoryTag = "burger",
            ingredients = listOf(
                req(hakketOkse, 500.0, MeasurementUnit.GRAM),
                req(burgerbolle, 4.0, MeasurementUnit.PIECE),
                req(tomat, 2.0, MeasurementUnit.PIECE),
                req(loeg, 1.0, MeasurementUnit.PIECE),
                req(revetOst, 100.0, MeasurementUnit.GRAM)
            )
        )

        recipes += Recipe(
            name = "Kylling med ovnkartofler", proteinTag = "kylling", categoryTag = "ovnret",
            ingredients = listOf(
                req(kylling, 600.0, MeasurementUnit.GRAM),
                req(kartofler, 1000.0, MeasurementUnit.GRAM),
                req(olie, 30.0, MeasurementUnit.MILLILITRE),
                req(hvidloeg, 2.0, MeasurementUnit.PIECE)
            )
        )

        recipes += Recipe(
            name = "Cremet tomatpasta", proteinTag = "vegetarisk", categoryTag = "pasta",
            ingredients = listOf(
                req(pasta, 400.0, MeasurementUnit.GRAM),
                req(hakketTomat, 2.0, MeasurementUnit.CAN),
                req(floede, 250.0, MeasurementUnit.MILLILITRE),
                req(loeg, 1.0, MeasurementUnit.PIECE),
                req(hvidloeg, 2.0, MeasurementUnit.PIECE)
            )
        )

        pantry[ris.id] = MeasurementUnit.GRAM.toBase(700.0)
        pantry[pasta.id] = MeasurementUnit.GRAM.toBase(500.0)
        pantry[loeg.id] = MeasurementUnit.PIECE.toBase(4.0)
        pantry[hakketTomat.id] = MeasurementUnit.CAN.toBase(2.0)

        weeklySandwichRecipeId = sandwich.id
        save()
    }

    private fun save() {
        storage.save(
            LocalStorage.Snapshot(
                ingredients = ingredients.toList(),
                recipes = recipes.toList(),
                pantry = pantry.toMap(),
                dayModes = dayModes.toMap(),
                plan = plan.toMap(),
                weeklySandwichRecipeId = weeklySandwichRecipeId,
                shoppingChecked = shoppingChecked.toMap()
            )
        )
    }
}
