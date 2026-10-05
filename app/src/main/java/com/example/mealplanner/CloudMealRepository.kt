package com.example.mealplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.time.Duration.Companion.hours

@Serializable
private data class IngredientRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    @SerialName("quantity_type") val quantityType: String,
    @SerialName("preferred_unit") val preferredUnit: String,
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
private data class RecipeRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    @SerialName("protein_tag") val proteinTag: String = "",
    @SerialName("category_tag") val categoryTag: String = "",
    val notes: String = "",
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
private data class RecipeIngredientRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("amount_base") val amountBase: Double
)

@Serializable
private data class PantryRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("amount_base") val amountBase: Double
)

@Serializable
private data class SettingsRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("sandwich_recipe_id") val sandwichRecipeId: String? = null,
    @SerialName("preferred_sandwich_day") val preferredSandwichDay: Int = 5,
    @SerialName("local_import_completed") val localImportCompleted: Boolean = false
)

@Serializable
private data class WeeklyPlanRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("week_start") val weekStart: String,
    @SerialName("sandwich_day") val sandwichDay: Int? = null,
    @SerialName("sandwich_recipe_id") val sandwichRecipeId: String? = null
)

@Serializable
private data class PlannedMealRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    val mode: String,
    @SerialName("recipe_id") val recipeId: String? = null,
    val locked: Boolean = false
)

@Serializable
private data class ShoppingCheckRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    val checked: Boolean
)

@Serializable
private data class MealHistoryRow(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("household_id") val householdId: String,
    @SerialName("eaten_on") val eatenOn: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("source_weekly_plan_id") val sourceWeeklyPlanId: String? = null
)

@Serializable
private data class FeedbackRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String,
    val message: String
)

data class CloudSnapshot(
    val ingredients: List<Ingredient>,
    val recipes: List<Recipe>,
    val pantry: Map<String, Double>,
    val dayModes: Map<DayOfWeek, DayMode>,
    val plan: Map<DayOfWeek, String?>,
    val lockedDays: Set<DayOfWeek>,
    val shoppingChecked: Map<String, Boolean>,
    val weeklyPlanId: String,
    val weeklySandwichRecipeId: String?,
    val sandwichDay: DayOfWeek?,
    val history: List<MealHistoryEntry>
)

class CloudMealRepository(
    private val context: Context,
    private val householdId: String
) {
    private val supabase = SupabaseProvider.client
    private val signedImageUrls = mutableMapOf<String, String>()
    private var realtimeChannel: RealtimeChannel? = null
    private var realtimeJobs: List<Job> = emptyList()

    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    suspend fun loadCurrent(): CloudSnapshot = withContext(Dispatchers.IO) {
        syncRecentHistory()

        coroutineScope {
            val ingredientsDeferred = async {
                supabase.from("ingredients").select {
                    filter { eq("household_id", householdId) }
                }.decodeList<IngredientRow>()
            }
            val recipesDeferred = async {
                supabase.from("recipes").select {
                    filter { eq("household_id", householdId) }
                }.decodeList<RecipeRow>()
            }
            val recipeIngredientsDeferred = async {
                supabase.from("recipe_ingredients").select {
                    filter { eq("household_id", householdId) }
                }.decodeList<RecipeIngredientRow>()
            }
            val pantryDeferred = async {
                supabase.from("pantry_items").select {
                    filter { eq("household_id", householdId) }
                }.decodeList<PantryRow>()
            }
            val settingsDeferred = async {
                supabase.from("household_settings").select {
                    filter { eq("household_id", householdId) }
                }.decodeList<SettingsRow>().firstOrNull()
            }
            val historyDeferred = async {
                supabase.from("meal_history").select {
                    filter {
                        eq("household_id", householdId)
                        gte("eaten_on", LocalDate.now().minusDays(90).toString())
                    }
                }.decodeList<MealHistoryRow>()
            }

            val ingredientRows = ingredientsDeferred.await()
            val recipeRows = recipesDeferred.await()
            val recipeIngredientRows = recipeIngredientsDeferred.await()
            val pantryRows = pantryDeferred.await()
            val settings = settingsDeferred.await()
            val historyRows = historyDeferred.await()

            val weeklyPlan = ensureWeek(
                weekStart = currentWeekStart(),
                sandwichRecipeId = settings?.sandwichRecipeId
            )

            val mealsDeferred = async {
                supabase.from("planned_meals").select {
                    filter {
                        eq("household_id", householdId)
                        eq("weekly_plan_id", weeklyPlan.id)
                    }
                }.decodeList<PlannedMealRow>()
            }
            val shoppingDeferred = async {
                supabase.from("shopping_checks").select {
                    filter {
                        eq("household_id", householdId)
                        eq("weekly_plan_id", weeklyPlan.id)
                    }
                }.decodeList<ShoppingCheckRow>()
            }

            val mealRows = mealsDeferred.await()
            val shoppingRows = shoppingDeferred.await()

            val imagePaths = buildSet {
                ingredientRows.mapNotNullTo(this) { it.imagePath }
                recipeRows.mapNotNullTo(this) { it.imagePath }
            }
            val urls = imagePaths
                .map { path ->
                    async { path to signedImageUrl(path) }
                }
                .awaitAll()
                .toMap()

            val ingredients = ingredientRows.mapNotNull { row ->
                val type = runCatching { QuantityType.valueOf(row.quantityType) }.getOrNull()
                    ?: return@mapNotNull null
                val unit = runCatching { MeasurementUnit.valueOf(row.preferredUnit) }.getOrNull()
                    ?: return@mapNotNull null

                Ingredient(
                    id = row.id,
                    name = row.name,
                    quantityType = type,
                    preferredUnit = unit,
                    imageUri = row.imagePath?.let(urls::get),
                    imagePath = row.imagePath
                )
            }

            val requirements = recipeIngredientRows.groupBy { it.recipeId }
            val recipes = recipeRows.map { row ->
                Recipe(
                    id = row.id,
                    name = row.name,
                    proteinTag = row.proteinTag,
                    categoryTag = row.categoryTag,
                    notes = row.notes,
                    imageUri = row.imagePath?.let(urls::get),
                    imagePath = row.imagePath,
                    ingredients = requirements[row.id].orEmpty().map {
                        RecipeIngredient(
                            ingredientId = it.ingredientId,
                            amountBase = it.amountBase
                        )
                    }
                )
            }

            val modes = orderedWeekDays.associateWith { DayMode.AUTO }.toMutableMap()
            val plan = orderedWeekDays.associateWith { null as String? }.toMutableMap()
            val locked = mutableSetOf<DayOfWeek>()

            mealRows.forEach { row ->
                val day = DayOfWeek.of(row.dayOfWeek)
                modes[day] = if (row.mode == DayMode.AWAY.name) DayMode.AWAY else DayMode.AUTO
                plan[day] = row.recipeId
                if (row.locked) locked += day
            }

            CloudSnapshot(
                ingredients = ingredients,
                recipes = recipes,
                pantry = pantryRows.associate { it.ingredientId to it.amountBase },
                dayModes = modes,
                plan = plan,
                lockedDays = locked,
                shoppingChecked = shoppingRows.associate { it.ingredientId to it.checked },
                weeklyPlanId = weeklyPlan.id,
                weeklySandwichRecipeId = weeklyPlan.sandwichRecipeId ?: settings?.sandwichRecipeId,
                sandwichDay = weeklyPlan.sandwichDay?.let(DayOfWeek::of),
                history = historyRows.map {
                    MealHistoryEntry(
                        recipeId = it.recipeId,
                        eatenOn = LocalDate.parse(it.eatenOn)
                    )
                }
            )
        }
    }

    suspend fun needsLocalImport(): Boolean = withContext(Dispatchers.IO) {
        val settings = supabase.from("household_settings").select {
            filter { eq("household_id", householdId) }
            limit(1)
        }.decodeList<SettingsRow>().firstOrNull()

        settings?.localImportCompleted != true
    }

    suspend fun markLocalImportCompleted() = withContext(Dispatchers.IO) {
        supabase.from("household_settings").update(
            {
                set("local_import_completed", true)
            }
        ) {
            filter { eq("household_id", householdId) }
        }
    }

    suspend fun importLocal(snapshot: LocalStorage.Snapshot) = withContext(Dispatchers.IO) {

        snapshot.ingredients.forEach { ingredient ->
            upsertIngredient(ingredient)
        }

        snapshot.recipes.forEach { recipe ->
            upsertRecipe(recipe)
        }

        if (snapshot.pantry.isNotEmpty()) {
            supabase.from("pantry_items").upsert(
                snapshot.pantry.map { (ingredientId, amount) ->
                    PantryRow(householdId, ingredientId, amount)
                }
            ) {
                onConflict = "household_id,ingredient_id"
            }
        }

        supabase.from("household_settings").upsert(
            SettingsRow(
                householdId = householdId,
                sandwichRecipeId = snapshot.weeklySandwichRecipeId,
                localImportCompleted = false
            )
        ) {
            onConflict = "household_id"
        }

        val week = ensureWeek(
            currentWeekStart(),
            snapshot.weeklySandwichRecipeId
        )

        val activeSandwichDay = orderedWeekDays.firstOrNull { day ->
            snapshot.dayModes[day] != DayMode.AWAY &&
                snapshot.plan[day] == snapshot.weeklySandwichRecipeId
        } ?: WeeklyPlanner.preferredSandwichDay(snapshot.dayModes)

        val meals = orderedWeekDays.map { day ->
            PlannedMealRow(
                householdId = householdId,
                weeklyPlanId = week.id,
                dayOfWeek = day.value,
                mode = if (snapshot.dayModes[day] == DayMode.AWAY) "AWAY" else "AUTO",
                recipeId = if (day == activeSandwichDay) null else snapshot.plan[day],
                locked = false
            )
        }
        supabase.from("planned_meals").upsert(meals) {
            onConflict = "weekly_plan_id,day_of_week"
        }

        saveWeekHeader(
            weeklyPlanId = week.id,
            sandwichRecipeId = snapshot.weeklySandwichRecipeId,
            sandwichDay = activeSandwichDay
        )

        val checks = snapshot.shoppingChecked.map { (ingredientId, checked) ->
            ShoppingCheckRow(
                householdId = householdId,
                weeklyPlanId = week.id,
                ingredientId = ingredientId,
                checked = checked
            )
        }
        if (checks.isNotEmpty()) {
            supabase.from("shopping_checks").upsert(checks) {
                onConflict = "weekly_plan_id,ingredient_id"
            }
        }

        markLocalImportCompleted()
    }

    suspend fun upsertIngredient(ingredient: Ingredient): Ingredient = withContext(Dispatchers.IO) {
        val finalImage = resolveImage(
            kind = "ingredients",
            objectId = ingredient.id,
            imageUri = ingredient.imageUri,
            existingPath = ingredient.imagePath
        )

        supabase.from("ingredients").upsert(
            IngredientRow(
                id = ingredient.id,
                householdId = householdId,
                name = ingredient.name,
                quantityType = ingredient.quantityType.name,
                preferredUnit = ingredient.preferredUnit.name,
                imagePath = finalImage.first
            )
        ) {
            onConflict = "id"
        }

        ingredient.copy(
            imagePath = finalImage.first,
            imageUri = finalImage.second
        )
    }

    suspend fun deleteIngredient(id: String, imagePath: String?) = withContext(Dispatchers.IO) {
        supabase.from("ingredients").delete {
            filter {
                eq("household_id", householdId)
                eq("id", id)
            }
        }
        deleteImage(imagePath)
    }

    suspend fun upsertRecipe(recipe: Recipe): Recipe = withContext(Dispatchers.IO) {
        val finalImage = resolveImage(
            kind = "recipes",
            objectId = recipe.id,
            imageUri = recipe.imageUri,
            existingPath = recipe.imagePath
        )

        supabase.from("recipes").upsert(
            RecipeRow(
                id = recipe.id,
                householdId = householdId,
                name = recipe.name,
                proteinTag = recipe.proteinTag,
                categoryTag = recipe.categoryTag,
                notes = recipe.notes,
                imagePath = finalImage.first
            )
        ) {
            onConflict = "id"
        }

        supabase.from("recipe_ingredients").delete {
            filter {
                eq("household_id", householdId)
                eq("recipe_id", recipe.id)
            }
        }

        if (recipe.ingredients.isNotEmpty()) {
            supabase.from("recipe_ingredients").insert(
                recipe.ingredients.map {
                    RecipeIngredientRow(
                        householdId = householdId,
                        recipeId = recipe.id,
                        ingredientId = it.ingredientId,
                        amountBase = it.amountBase
                    )
                }
            )
        }

        recipe.copy(
            imagePath = finalImage.first,
            imageUri = finalImage.second
        )
    }

    suspend fun deleteRecipe(id: String, imagePath: String?) = withContext(Dispatchers.IO) {
        supabase.from("recipes").delete {
            filter {
                eq("household_id", householdId)
                eq("id", id)
            }
        }
        deleteImage(imagePath)
    }

    suspend fun setPantry(ingredientId: String, amountBase: Double) = withContext(Dispatchers.IO) {
        supabase.from("pantry_items").upsert(
            PantryRow(
                householdId = householdId,
                ingredientId = ingredientId,
                amountBase = amountBase
            )
        ) {
            onConflict = "household_id,ingredient_id"
        }
    }

    suspend fun setHouseholdSandwich(recipeId: String?) = withContext(Dispatchers.IO) {
        supabase.from("household_settings").update(
            {
                set("sandwich_recipe_id", recipeId)
            }
        ) {
            filter { eq("household_id", householdId) }
        }
    }

    suspend fun saveWeekState(
        weeklyPlanId: String,
        plan: Map<DayOfWeek, String?>,
        dayModes: Map<DayOfWeek, DayMode>,
        lockedDays: Set<DayOfWeek>,
        sandwichRecipeId: String?,
        sandwichDay: DayOfWeek?
    ) = withContext(Dispatchers.IO) {
        saveWeekHeader(weeklyPlanId, sandwichRecipeId, sandwichDay)

        supabase.from("planned_meals").upsert(
            orderedWeekDays.map { day ->
                PlannedMealRow(
                    householdId = householdId,
                    weeklyPlanId = weeklyPlanId,
                    dayOfWeek = day.value,
                    mode = if (dayModes[day] == DayMode.AWAY) "AWAY" else "AUTO",
                    recipeId = plan[day],
                    locked = day in lockedDays
                )
            }
        ) {
            onConflict = "weekly_plan_id,day_of_week"
        }
    }

    suspend fun setShoppingChecked(
        weeklyPlanId: String,
        ingredientId: String,
        checked: Boolean
    ) = withContext(Dispatchers.IO) {
        supabase.from("shopping_checks").upsert(
            ShoppingCheckRow(
                householdId = householdId,
                weeklyPlanId = weeklyPlanId,
                ingredientId = ingredientId,
                checked = checked
            )
        ) {
            onConflict = "weekly_plan_id,ingredient_id"
        }
    }

    suspend fun clearShoppingChecks(weeklyPlanId: String) = withContext(Dispatchers.IO) {
        supabase.from("shopping_checks").delete {
            filter {
                eq("household_id", householdId)
                eq("weekly_plan_id", weeklyPlanId)
            }
        }
    }

    suspend fun submitFeedback(message: String) = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("Du er ikke logget ind.")

        supabase.from("app_feedback").insert(
            FeedbackRow(
                householdId = householdId,
                userId = userId,
                message = message.trim()
            )
        )
    }

    suspend fun startRealtime(
        scope: CoroutineScope,
        onChanged: suspend () -> Unit
    ) {
        stopRealtime()

        val channel = supabase.realtime.channel("household-$householdId")
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 32)

        val watchedTables = listOf(
            "ingredients",
            "recipes",
            "recipe_ingredients",
            "pantry_items",
            "household_settings",
            "weekly_plans",
            "planned_meals",
            "shopping_checks",
            "meal_history"
        )

        realtimeJobs = watchedTables.map { table ->
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                this.table = table
            }.onEach {
                changes.tryEmit(Unit)
            }.launchIn(scope)
        } + changes
            .debounce(250)
            .onEach { onChanged() }
            .launchIn(scope)

        realtimeChannel = channel
        channel.subscribe()
    }

    suspend fun stopRealtime() {
        realtimeJobs.forEach { it.cancel() }
        realtimeJobs = emptyList()
        realtimeChannel?.unsubscribe()
        realtimeChannel = null
    }

    private suspend fun ensureWeek(
        weekStart: LocalDate,
        sandwichRecipeId: String?
    ): WeeklyPlanRow {
        val existing = supabase.from("weekly_plans").select {
            filter {
                eq("household_id", householdId)
                eq("week_start", weekStart.toString())
            }
        }.decodeList<WeeklyPlanRow>().firstOrNull()

        if (existing != null) return existing

        val row = WeeklyPlanRow(
            id = UUID.randomUUID().toString(),
            householdId = householdId,
            weekStart = weekStart.toString(),
            sandwichRecipeId = sandwichRecipeId
        )

        return supabase.from("weekly_plans").upsert(row) {
            onConflict = "household_id,week_start"
            select()
        }.decodeList<WeeklyPlanRow>().firstOrNull() ?: row
    }

    private suspend fun saveWeekHeader(
        weeklyPlanId: String,
        sandwichRecipeId: String?,
        sandwichDay: DayOfWeek?
    ) {
        supabase.from("weekly_plans").update(
            {
                set("sandwich_recipe_id", sandwichRecipeId)
                set("sandwich_day", sandwichDay?.value)
            }
        ) {
            filter {
                eq("household_id", householdId)
                eq("id", weeklyPlanId)
            }
        }
    }

    private suspend fun syncRecentHistory() {
        val cutoff = LocalDate.now().minusDays(60)
        val today = LocalDate.now()

        val weeks = supabase.from("weekly_plans").select {
            filter {
                eq("household_id", householdId)
                gte("week_start", cutoff.toString())
            }
        }.decodeList<WeeklyPlanRow>()

        if (weeks.isEmpty()) return

        val meals = supabase.from("planned_meals").select {
            filter { eq("household_id", householdId) }
        }.decodeList<PlannedMealRow>()

        val mealsByWeek = meals.groupBy { it.weeklyPlanId }
        val rows = weeks.flatMap { week ->
            val start = LocalDate.parse(week.weekStart)
            mealsByWeek[week.id].orEmpty().mapNotNull { meal ->
                val date = start.plusDays(meal.dayOfWeek.toLong() - 1)
                if (date >= today || meal.mode == "AWAY") return@mapNotNull null

                val recipeId = if (
                    week.sandwichDay == meal.dayOfWeek &&
                    week.sandwichRecipeId != null
                ) {
                    week.sandwichRecipeId
                } else {
                    meal.recipeId
                } ?: return@mapNotNull null

                MealHistoryRow(
                    householdId = householdId,
                    eatenOn = date.toString(),
                    recipeId = recipeId,
                    sourceWeeklyPlanId = week.id
                )
            }
        }

        if (rows.isNotEmpty()) {
            val existing = supabase.from("meal_history").select {
                filter {
                    eq("household_id", householdId)
                    gte("eaten_on", cutoff.toString())
                }
            }.decodeList<MealHistoryRow>()
                .associateBy { it.eatenOn }

            val changedRows = rows.filter { candidate ->
                existing[candidate.eatenOn]?.recipeId != candidate.recipeId
            }

            if (changedRows.isNotEmpty()) {
                supabase.from("meal_history").upsert(changedRows) {
                    onConflict = "household_id,eaten_on"
                }
            }
        }
    }

    private suspend fun resolveImage(
        kind: String,
        objectId: String,
        imageUri: String?,
        existingPath: String?
    ): Pair<String?, String?> {
        if (imageUri.isNullOrBlank()) {
            if (existingPath != null) deleteImage(existingPath)
            return null to null
        }

        if (!imageUri.startsWith("content://") && !imageUri.startsWith("file://")) {
            return existingPath to imageUri
        }

        val bytes = prepareImage(Uri.parse(imageUri))
        val path = "$householdId/$kind/$objectId-\${UUID.randomUUID()}.jpg"
        supabase.storage["meal-images"].upload(path, bytes) {
            upsert = false
        }

        if (existingPath != null && existingPath != path) {
            deleteImage(existingPath)
        }

        return path to signedImageUrl(path)
    }

    private suspend fun signedImageUrl(path: String): String {
        signedImageUrls[path]?.let { return it }

        val url = supabase.storage["meal-images"].createSignedUrl(path, 24.hours)
        signedImageUrls[path] = url
        return url
    }

    private suspend fun deleteImage(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            supabase.storage["meal-images"].delete(path)
        }
        signedImageUrls.remove(path)
    }

    private fun prepareImage(uri: Uri): ByteArray {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > 1600) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, sample)
        }

        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Kunne ikke læse billedet.")

        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
