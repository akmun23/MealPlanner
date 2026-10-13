package com.example.mealplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.HasOldRecord
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class IngredientRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    @SerialName("quantity_type") val quantityType: String,
    @SerialName("preferred_unit") val preferredUnit: String,
    @SerialName("never_buy") val neverBuy: Boolean = false,
    @SerialName("shopping_category_id") val shoppingCategoryId: String? = null,
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
private data class IngredientConversionVersionRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    val revision: Long = 0L
)

@Serializable
private data class IngredientConversionProfileRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("unit_code") val unitCode: String,
    @SerialName("base_amount_per_unit") val baseAmountPerUnit: Double,
    val approximate: Boolean = false,
    val source: String = "household-confirmed",
    val revision: Long = 1
)

@Serializable
private data class BilkaDefaultImageRow(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String
)

@Serializable
private data class BilkaProductImageRow(
    val id: String,
    @SerialName("image_url") val imageUrl: String? = null
)

@Serializable
private data class IngredientCategoryRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    val position: Int = 0
)

@Serializable
private data class IngredientProteinRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("is_protein") val isProtein: Boolean = false
)

@Serializable
private data class RecipeCategoryRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    val position: Int = 0
)

@Serializable
private data class RecipeCategoryAssignmentRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("category_id") val categoryId: String
)

/** Every persisted RecipeRow field must be requested during remote refresh. */
internal val recipeCloudReadColumns = listOf(
    "id",
    "household_id",
    "name",
    "meal_type",
    "notes",
    "instruction_steps",
    "source_url",
    "image_path",
)

@Serializable
internal data class RecipeRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    @SerialName("meal_type") val mealType: String = "DINNER",
    val notes: String,
    @SerialName("instruction_steps") val instructionSteps: List<RecipeInstructionStep> = emptyList(),
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
private data class RecipeRotationRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("auto_rotation_enabled") val autoRotationEnabled: Boolean = true
)

@Serializable
private data class RecipeTimeRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("prep_minutes") val prepMinutes: Int? = null
)

@Serializable
private data class DayConstraintRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("max_prep_minutes") val maxPrepMinutes: Int
)

@Serializable
private data class RecipeIngredientRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("amount_base") val amountBase: Double,
    @SerialName("display_amount") val displayAmount: Double? = null,
    @SerialName("display_unit_code") val displayUnitCode: String? = null,
    @SerialName("base_per_display_unit") val basePerDisplayUnit: Double? = null,
    @SerialName("conversion_approximate") val conversionApproximate: Boolean
)

@Serializable
private data class RecipePantryPrerequisiteRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("minimum_amount_base") val minimumAmountBase: Double
)

@Serializable
private data class PantryRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("amount_base") val amountBase: Double
)

@Serializable
private data class PantryLotRow(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("original_amount_base") val originalBase: Double,
    @SerialName("remaining_amount_base") val remainingBase: Double,
    val opened: Boolean = false,
    @SerialName("legacy_unknown") val legacyUnknown: Boolean = false,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("purchase_event_key") val purchaseEventKey: String? = null,
    @SerialName("best_before") val bestBefore: String? = null,
    @SerialName("lot_version") val version: Long = 1L,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
private data class SettingsRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("sandwich_recipe_id") val sandwichRecipeId: String? = null
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
internal data class PlannedMealRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    val mode: String,
    @SerialName("recipe_id") val recipeId: String? = null,
    // Required in database: DO NOT give this a Kotlin default.
    // Kotlinx serialization omits default-valued properties by default,
    // which made locked=false disappear from Supabase upserts and caused
    // PostgreSQL's NOT NULL constraint to reject week sync repeatedly.
    val locked: Boolean
)

@Serializable
private data class WeeklyLunchRecipeRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("recipe_id") val recipeId: String,
    val position: Int = 0
)

@Serializable
private data class ShoppingCheckRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    val checked: Boolean
)

@Serializable
private data class ShoppingInclusionRow(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    val included: Boolean
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
    val ingredientCategories: List<IngredientCategory>,
    val recipeCategories: List<RecipeCategoryOption>,
    val recipes: List<Recipe>,
    val pantry: Map<String, Double>,
    val pantryLots: List<PantryLot> = emptyList(),
    val dayModes: Map<DayOfWeek, DayMode>,
    val dayMaxPrepMinutes: Map<DayOfWeek, Int?>,
    val plan: Map<DayOfWeek, String?>,
    val lockedDays: Set<DayOfWeek>,
    val shoppingChecked: Map<String, Boolean>,
    val shoppingIncluded: Map<String, Boolean>,
    val selectedLunchRecipeIds: List<String>,
    val weeklyPlanId: String,
    val weeklySandwichRecipeId: String?,
    val sandwichDay: DayOfWeek?,
    val history: List<MealHistoryEntry>,
    val viewWeekStart: LocalDate = LocalDate.now()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
) {
    companion object {
        val EMPTY = CloudSnapshot(
            ingredients = emptyList(),
            ingredientCategories = emptyList(),
            recipeCategories = emptyList(),
            recipes = emptyList(),
            pantry = emptyMap(),
            dayModes = DayOfWeek.entries.associateWith { DayMode.AUTO },
            dayMaxPrepMinutes = DayOfWeek.entries.associateWith { null },
            plan = DayOfWeek.entries.associateWith { null },
            lockedDays = emptySet(),
            shoppingChecked = emptyMap(),
            shoppingIncluded = emptyMap(),
            selectedLunchRecipeIds = emptyList(),
            weeklyPlanId = "local-week",
            weeklySandwichRecipeId = null,
            sandwichDay = null,
            history = emptyList()
        )
    }
}

internal enum class CloudSyncDomain {
    INGREDIENTS,
    RECIPES,
    PANTRY,
    WEEK,
    SHOPPING,
    HISTORY
}

internal fun syncDomainForTable(table: String): CloudSyncDomain? =
    when (table) {
        "ingredients",
        "ingredient_protein_flags", "ingredient_unit_conversions",
        "ingredient_conversion_versions",
        "shopping_categories" -> CloudSyncDomain.INGREDIENTS

        "recipe_categories",
        "recipe_category_assignments",
        "recipes",
        "recipe_rotation_settings",
        "recipe_time_settings",
        "recipe_ingredients",
        "recipe_pantry_prerequisites" -> CloudSyncDomain.RECIPES

        "pantry_items", "pantry_lots" -> CloudSyncDomain.PANTRY

        "household_settings",
        "weekly_plans",
        "planned_meals",
        "weekly_day_constraints",
        "weekly_lunch_recipes" -> CloudSyncDomain.WEEK

        "shopping_checks",
        "shopping_inclusions" -> CloudSyncDomain.SHOPPING

        "meal_history" -> CloudSyncDomain.HISTORY
        else -> null
    }

internal data class CloudIngredientsSnapshot(
    val ingredients: List<Ingredient>,
    val ingredientCategories: List<IngredientCategory>
)

internal data class CloudRecipesSnapshot(
    val recipeCategories: List<RecipeCategoryOption>,
    val recipes: List<Recipe>
)

internal data class CloudWeekSnapshot(
    val dayModes: Map<DayOfWeek, DayMode>,
    val dayMaxPrepMinutes: Map<DayOfWeek, Int?>,
    val plan: Map<DayOfWeek, String?>,
    val lockedDays: Set<DayOfWeek>,
    val selectedLunchRecipeIds: List<String>,
    val weeklyPlanId: String,
    val weeklySandwichRecipeId: String?,
    val sandwichDay: DayOfWeek?
)

internal data class CloudShoppingSnapshot(
    val checked: Map<String, Boolean>,
    val included: Map<String, Boolean>
)

internal sealed interface CloudRealtimeEvent {
    val domain: CloudSyncDomain

    data class DomainChanged(
        override val domain: CloudSyncDomain
    ) : CloudRealtimeEvent

    data class PantryChanged(
        val ingredientId: String,
        val amountBase: Double?
    ) : CloudRealtimeEvent {
        override val domain = CloudSyncDomain.PANTRY
    }

    data class ShoppingCheckedChanged(
        val weeklyPlanId: String,
        val ingredientId: String,
        val checked: Boolean?
    ) : CloudRealtimeEvent {
        override val domain = CloudSyncDomain.SHOPPING
    }

    data class ShoppingIncludedChanged(
        val weeklyPlanId: String,
        val ingredientId: String,
        val included: Boolean?
    ) : CloudRealtimeEvent {
        override val domain = CloudSyncDomain.SHOPPING
    }
}

private fun PostgresAction.recordOrNull(): JsonObject? =
    (this as? HasRecord)?.record

private fun PostgresAction.oldRecordOrNull(): JsonObject? =
    (this as? HasOldRecord)?.oldRecord

private fun JsonObject.stringValue(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

@Suppress("SameParameterValue")
private fun JsonObject.doubleValue(name: String): Double? =
    get(name)?.jsonPrimitive?.doubleOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    get(name)?.jsonPrimitive?.booleanOrNull

internal fun realtimeEventFor(
    table: String,
    action: PostgresAction,
    expectedHouseholdId: String
): CloudRealtimeEvent? =
    realtimeEventForPayload(
        table = table,
        record = action.recordOrNull(),
        oldRecord = action.oldRecordOrNull(),
        expectedHouseholdId = expectedHouseholdId
    )

internal fun realtimeEventForPayload(
    table: String,
    record: JsonObject?,
    oldRecord: JsonObject?,
    expectedHouseholdId: String
): CloudRealtimeEvent? {
    val source = record ?: oldRecord
    val eventHouseholdId =
        source?.stringValue("household_id")

    if (
        eventHouseholdId != null &&
        eventHouseholdId != expectedHouseholdId
    ) {
        return null
    }

    return when (table) {
        "pantry_items" -> {
            val ingredientId =
                source?.stringValue("ingredient_id") ?: return null

            CloudRealtimeEvent.PantryChanged(
                ingredientId = ingredientId,
                amountBase = record?.doubleValue("amount_base")
            )
        }

        "shopping_checks" -> {
            val planId =
                source?.stringValue("weekly_plan_id") ?: return null
            val ingredientId =
                source.stringValue("ingredient_id") ?: return null

            CloudRealtimeEvent.ShoppingCheckedChanged(
                weeklyPlanId = planId,
                ingredientId = ingredientId,
                checked = record?.booleanValue("checked")
            )
        }

        "shopping_inclusions" -> {
            val planId =
                source?.stringValue("weekly_plan_id") ?: return null
            val ingredientId =
                source.stringValue("ingredient_id") ?: return null

            CloudRealtimeEvent.ShoppingIncludedChanged(
                weeklyPlanId = planId,
                ingredientId = ingredientId,
                included = record?.booleanValue("included")
            )
        }

        else -> syncDomainForTable(table)?.let {
            CloudRealtimeEvent.DomainChanged(it)
        }
    }
}

class CloudMealRepository(
    private val context: Context,
    private val householdId: String
) {
    private val supabase = SupabaseProvider.client
    private val imageUrls = SignedMealImageUrls.get(context.applicationContext)
    private var realtimeChannel: RealtimeChannel? = null
    private var realtimeJobs: List<Job> = emptyList()

    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    suspend fun loadCurrent(
        weekStart: LocalDate = currentWeekStart()
    ): CloudSnapshot = withContext(Dispatchers.IO) {
        coroutineScope {
            val ingredientsDeferred = async {
                loadIngredientsSnapshot()
            }
            val recipesDeferred = async {
                loadRecipesSnapshot()
            }
            val pantryDeferred = async {
                loadPantrySnapshot()
            }
            val pantryLotsDeferred = async {
                loadPantryLotsSnapshot()
            }
            val weekDeferred = async {
                loadWeekSnapshot(weekStart)
            }
            val historyDeferred = async {
                // History maintenance is independent of the other reads;
                // do not delay starting recipes, pantry or week requests.
                // Only explicit meal confirmations belong in meal_history.
                // A planned recipe is not proof that someone actually ate it.
                loadHistorySnapshot()
            }

            val ingredients = ingredientsDeferred.await()
            val recipes = recipesDeferred.await()
            val pantry = pantryDeferred.await()
            val pantryLots = pantryLotsDeferred.await()
            val week = weekDeferred.await()
            val history = historyDeferred.await()
            val shopping = loadShoppingSnapshot(
                week.weeklyPlanId
            )

            CloudSnapshot(
                ingredients = ingredients.ingredients,
                ingredientCategories =
                    ingredients.ingredientCategories,
                recipeCategories =
                    recipes.recipeCategories,
                recipes = recipes.recipes,
                pantry = pantry,
                pantryLots = pantryLots,
                dayModes = week.dayModes,
                dayMaxPrepMinutes =
                    week.dayMaxPrepMinutes,
                plan = week.plan,
                lockedDays = week.lockedDays,
                shoppingChecked = shopping.checked,
                shoppingIncluded = shopping.included,
                selectedLunchRecipeIds =
                    week.selectedLunchRecipeIds,
                weeklyPlanId = week.weeklyPlanId,
                weeklySandwichRecipeId =
                    week.weeklySandwichRecipeId,
                sandwichDay = week.sandwichDay,
                history = history,
                viewWeekStart = weekStart
            )
        }
    }

    internal suspend fun loadIngredientsSnapshot(): CloudIngredientsSnapshot =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val ingredientsDeferred = async {
                    supabase.from("ingredients").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<IngredientRow>()
                }
                val conversionsDeferred = async {
                    supabase.from("ingredient_unit_conversions").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<IngredientConversionProfileRow>()
                }
                val versionsDeferred = async {
                    supabase.from("ingredient_conversion_versions").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<IngredientConversionVersionRow>()
                }
                val categoriesDeferred = async {
                    supabase.from("shopping_categories").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<IngredientCategoryRow>()
                }
                val proteinFlagsDeferred = async {
                    supabase.from("ingredient_protein_flags").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<IngredientProteinRow>()
                }

                // The chosen Bilka product supplies the pantry thumbnail only
                // when no photo was explicitly selected for this ingredient.
                // Never download original-size pictures during snapshot loading.
                val bilkaImagesDeferred = async {
                    try {
                        val defaults = supabase.from("ingredient_bilka_defaults")
                            .select(columns = Columns.list("ingredient_id", "product_id")) {
                                filter { eq("household_id", householdId) }
                            }.decodeList<BilkaDefaultImageRow>()
                        val productIds = defaults.map { it.productId }.distinct()
                        if (productIds.isEmpty()) {
                            emptyMap()
                        } else {
                            val photos = productIds.chunked(100).flatMap { ids ->
                                supabase.from("bilka_products")
                                    .select(columns = Columns.list("id", "image_url")) {
                                        filter { isIn("id", ids) }
                                    }.decodeList<BilkaProductImageRow>()
                            }.associateBy { it.id }
                            defaults.mapNotNull { row ->
                                val url = photos[row.productId]?.imageUrl
                                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                                url?.let { row.ingredientId to it }
                            }.toMap()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // An unreachable optional catalog must not block pantry loading.
                        emptyMap<String, String>()
                    }
                }

                val ingredientRows = ingredientsDeferred.await()
                val categoryRows = categoriesDeferred.await()
                val proteinRows = proteinFlagsDeferred.await()
                val bilkaImages = bilkaImagesDeferred.await()
                val conversionsByIngredient = conversionsDeferred.await()
                    .groupBy { it.ingredientId }
                val revisionsByIngredient = versionsDeferred.await()
                    .associate { it.ingredientId to it.revision }
                val proteinByIngredient =
                    proteinRows.associateBy { it.ingredientId }

                CloudIngredientsSnapshot(
                    ingredients = ingredientRows.mapNotNull { row ->
                        val type = runCatching {
                            QuantityType.valueOf(row.quantityType)
                        }.getOrNull() ?: return@mapNotNull null
                        val unit = runCatching {
                            MeasurementUnit.valueOf(row.preferredUnit)
                        }.getOrNull() ?: return@mapNotNull null

                        Ingredient(
                            id = row.id,
                            name = row.name,
                            quantityType = type,
                            preferredUnit = unit,
                            shoppingCategoryId = row.shoppingCategoryId,
                            isProtein =
                                proteinByIngredient[row.id]?.isProtein == true,
                            neverBuy = row.neverBuy,
                            unitConversionRevision = revisionsByIngredient[row.id] ?: 0L,
                            unitConversions = conversionsByIngredient[row.id].orEmpty()
                                .mapNotNull { conversion ->
                                    val unit = QuantityUnit.entries.firstOrNull {
                                        it.name == conversion.unitCode
                                    } ?: return@mapNotNull null
                                    val factor = conversion.baseAmountPerUnit
                                    if (!factor.isFinite() || factor <= 0.0 || factor > 1e9) {
                                        return@mapNotNull null
                                    }
                                    IngredientUnitConversion(
                                        unit = unit,
                                        baseAmountPerUnit = factor,
                                        approximate = conversion.approximate,
                                        source = conversion.source,
                                        revision = conversion.revision
                                    )
                                },
                            imageUri = PantryImageRules.displayImage(
                                manualImageUri = row.imagePath?.let(MealImageReference::forPath),
                                bilkaImageUrl = bilkaImages[row.id]
                            ),
                            imagePath = row.imagePath
                        )
                    },
                    ingredientCategories = categoryRows
                        .sortedWith(
                            compareBy<IngredientCategoryRow> { it.position }
                                .thenBy { it.name.lowercase() }
                        )
                        .map {
                            IngredientCategory(
                                id = it.id,
                                name = it.name,
                                position = it.position
                            )
                        }
                )
            }
        }

    internal suspend fun loadRecipesSnapshot(): CloudRecipesSnapshot =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val categoriesDeferred = async {
                    supabase.from("recipe_categories").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeCategoryRow>()
                }
                val assignmentsDeferred = async {
                    supabase.from("recipe_category_assignments").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeCategoryAssignmentRow>()
                }
                val recipesDeferred = async {
                    // Explicit projection MUST include every persisted RecipeRow
                    // field. Omitting steps/URL silently applies their Kotlin
                    // defaults during decoding and then wipes the user's edits
                    // when realtime refresh replaces the optimistic recipe.
                    supabase.from("recipes").select(
                        columns = Columns.list(*recipeCloudReadColumns.toTypedArray())
                    ) {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeRow>()
                }
                val rotationDeferred = async {
                    supabase.from("recipe_rotation_settings").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeRotationRow>()
                }
                val timeDeferred = async {
                    supabase.from("recipe_time_settings").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeTimeRow>()
                }
                val ingredientsDeferred = async {
                    supabase.from("recipe_ingredients").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipeIngredientRow>()
                }
                val pantryPrerequisitesDeferred = async {
                    supabase.from("recipe_pantry_prerequisites").select {
                        filter { eq("household_id", householdId) }
                    }.decodeList<RecipePantryPrerequisiteRow>()
                }

                val categoryRows = categoriesDeferred.await()
                val assignmentRows = assignmentsDeferred.await()
                val recipeRows = recipesDeferred.await()
                val rotationRows = rotationDeferred.await()
                val timeRows = timeDeferred.await()
                val ingredientRows = ingredientsDeferred.await()
                val pantryPrerequisiteRows =
                    pantryPrerequisitesDeferred.await()

                val requirements = ingredientRows.groupBy { it.recipeId }
                val pantryPrerequisites =
                    pantryPrerequisiteRows.groupBy { it.recipeId }
                val categoryByRecipe =
                    assignmentRows.associateBy { it.recipeId }
                val rotationByRecipe =
                    rotationRows.associateBy { it.recipeId }
                val timeByRecipe =
                    timeRows.associateBy { it.recipeId }

                CloudRecipesSnapshot(
                    recipeCategories = categoryRows
                        .sortedWith(
                            compareBy<RecipeCategoryRow> { it.position }
                                .thenBy { it.name.lowercase() }
                        )
                        .map {
                            RecipeCategoryOption(
                                id = it.id,
                                name = it.name,
                                position = it.position
                            )
                        },
                    recipes = recipeRows.map { row ->
                        Recipe(
                            id = row.id,
                            name = row.name,
                            mealType = runCatching {
                                MealType.valueOf(row.mealType)
                            }.getOrDefault(MealType.DINNER),
                            autoRotationEnabled =
                                rotationByRecipe[row.id]
                                    ?.autoRotationEnabled
                                    ?: true,
                            prepMinutes =
                                timeByRecipe[row.id]?.prepMinutes,
                            categoryId =
                                categoryByRecipe[row.id]?.categoryId,
                            notes = row.notes,
                            instructionSteps = row.instructionSteps,
                            sourceUrl = row.sourceUrl,
                            imageUri = row.imagePath?.let(MealImageReference::forPath),
                            imagePath = row.imagePath,
                            ingredients =
                                requirements[row.id].orEmpty().map {
                                    RecipeIngredient(
                                        ingredientId = it.ingredientId,
                                        amountBase = it.amountBase,
                                        displayAmount = it.displayAmount,
                                        displayUnitCode = it.displayUnitCode,
                                        basePerDisplayUnit = it.basePerDisplayUnit,
                                        conversionApproximate = it.conversionApproximate
                                    )
                                },
                            pantryPrerequisites =
                                pantryPrerequisites[row.id].orEmpty().map {
                                    RecipePantryPrerequisite(
                                        ingredientId = it.ingredientId,
                                        minimumAmountBase = it.minimumAmountBase
                                    )
                                }
                        )
                    }
                )
            }
        }

    internal suspend fun loadPantrySnapshot(): Map<String, Double> =
        withContext(Dispatchers.IO) {
            supabase.from("pantry_items").select {
                filter { eq("household_id", householdId) }
            }.decodeList<PantryRow>()
                .associate { it.ingredientId to it.amountBase }
        }

    internal suspend fun loadPantryLotsSnapshot(): List<PantryLot> =
        withContext(Dispatchers.IO) {
            supabase.from("pantry_lots").select {
                filter { eq("household_id", householdId) }
            }.decodeList<PantryLotRow>().map { row ->
                PantryLot(
                    id = row.id,
                    ingredientId = row.ingredientId,
                    originalBase = row.originalBase,
                    remainingBase = row.remainingBase,
                    opened = row.opened,
                    legacyUnknown = row.legacyUnknown,
                    productId = row.productId,
                    purchaseEventKey = row.purchaseEventKey,
                    bestBefore = row.bestBefore,
                    version = row.version,
                    createdAt = row.createdAt.orEmpty()
                )
            }
        }

    suspend fun upsertPantryLot(
        lot: PantryLot, expectedVersion: Long, transactionId: String
    ) = withContext(Dispatchers.IO) {
        require(lot.remainingBase.isFinite() && lot.remainingBase >= 0.0 &&
            lot.originalBase.isFinite() && lot.originalBase > 0.0 &&
            lot.remainingBase <= lot.originalBase && lot.originalBase <= 1e10)
        require(expectedVersion >= 0L)
        // The receipt is written atomically with the conditional versioned
        // update. Retrying an already-applied WorkManager job is a no-op.
        supabase.postgrest.rpc(
            function = "upsert_pantry_lot_once",
            parameters = buildJsonObject {
                put("p_household_id", householdId)
                put("p_lot_id", lot.id)
                put("p_ingredient_id", lot.ingredientId)
                put("p_original_amount_base", lot.originalBase)
                put("p_remaining_amount_base", lot.remainingBase)
                put("p_opened", lot.opened)
                put("p_legacy_unknown", lot.legacyUnknown)
                put("p_product_id", lot.productId ?: "")
                put("p_best_before", lot.bestBefore ?: "")
                put("p_expected_version", expectedVersion)
                put("p_event_id", transactionId)
            }
        )
    }

    suspend fun completeMealWithLotsOnce(
        eatenOn: LocalDate,
        recipeId: String,
        deductions: Map<String, Double>,
        transactionId: String
    ) = withContext(Dispatchers.IO) {
        require(deductions.size <= 200 && deductions.values.all {
            it.isFinite() && it > 0.0 && it <= 1e10
        })
        supabase.postgrest.rpc(
            function = "complete_meal_with_lots_once",
            parameters = buildJsonObject {
                put("p_household_id", householdId)
                put("p_eaten_on", eatenOn.toString())
                put("p_recipe_id", recipeId)
                put("p_event_id", transactionId)
                put("p_deductions", buildJsonObject {
                    deductions.forEach { (ingredientId, amount) ->
                        put(ingredientId, amount)
                    }
                })
            }
        )
    }

    suspend fun consumePantryLotsOnce(
        ingredientId: String, amountBase: Double, transactionId: String
    ) = withContext(Dispatchers.IO) {
        require(amountBase.isFinite() && amountBase > 0.0 && amountBase <= 1e10)
        supabase.postgrest.rpc(
            function = "consume_pantry_lots_once",
            parameters = buildJsonObject {
                put("p_household_id", householdId)
                put("p_ingredient_id", ingredientId)
                put("p_amount_base", amountBase)
                put("p_transaction_key", transactionId)
            }
        )
    }

    internal suspend fun loadWeekSnapshot(weekStart: LocalDate = currentWeekStart()): CloudWeekSnapshot =
        withContext(Dispatchers.IO) {
            val settings = supabase.from("household_settings").select {
                filter { eq("household_id", householdId) }
            }.decodeList<SettingsRow>().firstOrNull()

            val weeklyPlan = ensureWeek(
                weekStart = weekStart,
                sandwichRecipeId = settings?.sandwichRecipeId
            )

            coroutineScope {
                val mealsDeferred = async {
                    supabase.from("planned_meals").select {
                        filter {
                            eq("household_id", householdId)
                            eq("weekly_plan_id", weeklyPlan.id)
                        }
                    }.decodeList<PlannedMealRow>()
                }
                val lunchDeferred = async {
                    supabase.from("weekly_lunch_recipes").select {
                        filter {
                            eq("household_id", householdId)
                            eq("weekly_plan_id", weeklyPlan.id)
                        }
                    }.decodeList<WeeklyLunchRecipeRow>()
                }
                val constraintsDeferred = async {
                    supabase.from("weekly_day_constraints").select {
                        filter {
                            eq("household_id", householdId)
                            eq("weekly_plan_id", weeklyPlan.id)
                        }
                    }.decodeList<DayConstraintRow>()
                }

                val mealRows = mealsDeferred.await()
                val lunchRows = lunchDeferred.await()
                val constraintRows = constraintsDeferred.await()

                val modes = orderedWeekDays
                    .associateWith { DayMode.AUTO }
                    .toMutableMap()
                val plan = orderedWeekDays
                    .associateWith { null as String? }
                    .toMutableMap()
                val locked = mutableSetOf<DayOfWeek>()

                mealRows.forEach { row ->
                    val day = DayOfWeek.of(row.dayOfWeek)
                    modes[day] =
                        if (row.mode == DayMode.AWAY.name) {
                            DayMode.AWAY
                        } else {
                            DayMode.AUTO
                        }
                    plan[day] = row.recipeId
                    if (row.locked) locked += day
                }

                CloudWeekSnapshot(
                    dayModes = modes,
                    dayMaxPrepMinutes =
                        orderedWeekDays.associateWith { day ->
                            constraintRows
                                .firstOrNull {
                                    it.dayOfWeek == day.value
                                }
                                ?.maxPrepMinutes
                        },
                    plan = plan,
                    lockedDays = locked,
                    selectedLunchRecipeIds =
                        lunchRows.sortedBy { it.position }
                            .map { it.recipeId },
                    weeklyPlanId = weeklyPlan.id,
                    weeklySandwichRecipeId =
                        weeklyPlan.sandwichRecipeId,
                    sandwichDay =
                        weeklyPlan.sandwichDay?.let(DayOfWeek::of)
                )
            }
        }

    internal suspend fun loadShoppingSnapshot(
        weeklyPlanId: String
    ): CloudShoppingSnapshot =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val checksDeferred = async {
                    supabase.from("shopping_checks").select {
                        filter {
                            eq("household_id", householdId)
                            eq("weekly_plan_id", weeklyPlanId)
                        }
                    }.decodeList<ShoppingCheckRow>()
                }
                val inclusionDeferred = async {
                    supabase.from("shopping_inclusions").select {
                        filter {
                            eq("household_id", householdId)
                            eq("weekly_plan_id", weeklyPlanId)
                        }
                    }.decodeList<ShoppingInclusionRow>()
                }

                CloudShoppingSnapshot(
                    checked = checksDeferred.await()
                        .associate {
                            it.ingredientId to it.checked
                        },
                    included = inclusionDeferred.await()
                        .associate {
                            it.ingredientId to it.included
                        }
                )
            }
        }

    internal suspend fun loadHistorySnapshot(): List<MealHistoryEntry> =
        withContext(Dispatchers.IO) {
            supabase.from("meal_history").select {
                filter {
                    eq("household_id", householdId)
                    gte(
                        "eaten_on",
                        LocalDate.now()
                            .minusDays(90)
                            .toString()
                    )
                }
            }.decodeList<MealHistoryRow>()
                .map {
                    MealHistoryEntry(
                        recipeId = it.recipeId,
                        eatenOn = LocalDate.parse(it.eatenOn)
                    )
                }
        }

    /**
     * Meal history is explicit: users record what they ate, including the
     * ability to correct or remove an entry. Never infer it from the plan.
     */
    suspend fun setMealEaten(
        eatenOn: LocalDate,
        recipeId: String?
    ) = withContext(Dispatchers.IO) {
        if (recipeId == null) {
            supabase.from("meal_history").delete {
                filter {
                    eq("household_id", householdId)
                    eq("eaten_on", eatenOn.toString())
                }
            }
        } else {
            supabase.from("meal_history").upsert(
                MealHistoryRow(
                    householdId = householdId,
                    eatenOn = eatenOn.toString(),
                    recipeId = recipeId
                )
            ) {
                onConflict = "household_id,eaten_on"
            }
        }
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
                neverBuy = ingredient.neverBuy,
                shoppingCategoryId = ingredient.shoppingCategoryId,
                imagePath = finalImage.first
            )
        ) {
            onConflict = "id"
        }

        // Conversion profiles have their own versioned write path. An
        // unrelated ingredient-name/image change must never delete or
        // overwrite a profile edited on another phone.

        supabase.from("ingredient_protein_flags").upsert(
            IngredientProteinRow(
                householdId = householdId,
                ingredientId = ingredient.id,
                isProtein = ingredient.isProtein
            )
        ) {
            onConflict = "household_id,ingredient_id"
        }

        ingredient.copy(
            imagePath = finalImage.first,
            imageUri = finalImage.second
        )
    }

    suspend fun saveIngredientConversionsOnce(
        ingredientId: String,
        expectedRevision: Long,
        conversions: List<IngredientUnitConversion>,
        transactionId: String
    ) = withContext(Dispatchers.IO) {
        require(expectedRevision >= 0L && conversions.size <= 30)
        require(conversions.map { it.unit }.distinct().size == conversions.size)
        require(conversions.all {
            it.baseAmountPerUnit.isFinite() &&
                it.baseAmountPerUnit > 0.0 && it.baseAmountPerUnit <= 1e9 &&
                it.source.isNotBlank() && it.source.length <= 120
        })
        supabase.postgrest.rpc(
            function = "save_ingredient_conversions_once",
            parameters = buildJsonObject {
                put("p_household_id", householdId)
                put("p_ingredient_id", ingredientId)
                put("p_expected_revision", expectedRevision)
                put("p_event_id", transactionId)
                put("p_profiles", buildJsonArray {
                    conversions.forEach { profile ->
                        add(buildJsonObject {
                            put("unit_code", profile.unit.name)
                            put("base_amount_per_unit", profile.baseAmountPerUnit)
                            put("approximate", profile.approximate)
                            put("source", profile.source)
                        })
                    }
                })
            }
        )
    }

    suspend fun upsertIngredientCategory(
        category: IngredientCategory
    ) = withContext(Dispatchers.IO) {
        supabase.from("shopping_categories").upsert(
            IngredientCategoryRow(
                id = category.id,
                householdId = householdId,
                name = category.name.normalizedDisplayName(),
                position = category.position
            )
        ) {
            onConflict = "id"
        }
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
                mealType = recipe.mealType.name,
                notes = recipe.notes,
                instructionSteps = recipe.instructionSteps,
                sourceUrl = recipe.sourceUrl,
                imagePath = finalImage.first
            )
        ) {
            onConflict = "id"
        }

        supabase.from("recipe_rotation_settings").upsert(
            RecipeRotationRow(
                householdId = householdId,
                recipeId = recipe.id,
                autoRotationEnabled = recipe.autoRotationEnabled
            )
        ) {
            onConflict = "household_id,recipe_id"
        }

        supabase.from("recipe_time_settings").upsert(
            RecipeTimeRow(
                householdId = householdId,
                recipeId = recipe.id,
                prepMinutes = recipe.prepMinutes
            )
        ) {
            onConflict = "household_id,recipe_id"
        }

        if (recipe.categoryId == null) {
            supabase.from("recipe_category_assignments").delete {
                filter {
                    eq("household_id", householdId)
                    eq("recipe_id", recipe.id)
                }
            }
        } else {
            supabase.from("recipe_category_assignments").upsert(
                RecipeCategoryAssignmentRow(
                    householdId = householdId,
                    recipeId = recipe.id,
                    categoryId = recipe.categoryId
                )
            ) {
                onConflict = "household_id,recipe_id"
            }
        }

        supabase.from("recipe_ingredients").delete {
            filter {
                eq("household_id", householdId)
                eq("recipe_id", recipe.id)
            }
        }

        if (recipe.ingredients.isNotEmpty()) {
            // Idempotent across Room/WorkManager retries and repeated writes:
            // the primary key is (recipe_id, ingredient_id).
            supabase.from("recipe_ingredients").upsert(
                RecipeRequirementRules.combineIngredients(recipe.ingredients).map {
                    RecipeIngredientRow(
                        householdId = householdId,
                        recipeId = recipe.id,
                        ingredientId = it.ingredientId,
                        amountBase = it.amountBase,
                        displayAmount = it.displayAmount,
                        displayUnitCode = it.displayUnitCode,
                        basePerDisplayUnit = it.basePerDisplayUnit,
                        conversionApproximate = it.conversionApproximate
                    )
                }
            ) {
                onConflict = "recipe_id,ingredient_id"
            }
        }

        supabase.from("recipe_pantry_prerequisites").delete {
            filter {
                eq("household_id", householdId)
                eq("recipe_id", recipe.id)
            }
        }

        if (recipe.pantryPrerequisites.isNotEmpty()) {
            supabase.from("recipe_pantry_prerequisites").upsert(
                RecipeRequirementRules.combinePrerequisites(recipe.pantryPrerequisites).map {
                    RecipePantryPrerequisiteRow(
                        householdId = householdId,
                        recipeId = recipe.id,
                        ingredientId = it.ingredientId,
                        minimumAmountBase = it.minimumAmountBase
                    )
                }
            ) {
                onConflict = "recipe_id,ingredient_id"
            }
        }

        recipe.copy(
            imagePath = finalImage.first,
            imageUri = finalImage.second
        )
    }

    suspend fun upsertRecipeCategory(
        category: RecipeCategoryOption
    ) = withContext(Dispatchers.IO) {
        supabase.from("recipe_categories").upsert(
            RecipeCategoryRow(
                id = category.id,
                householdId = householdId,
                name = category.name.normalizedDisplayName(),
                position = category.position
            )
        ) {
            onConflict = "id"
        }
    }

    suspend fun deleteRecipeCategory(id: String) = withContext(Dispatchers.IO) {
        supabase.from("recipe_categories").delete {
            filter {
                eq("id", id)
                eq("household_id", householdId)
            }
        }
    }

    suspend fun deleteShoppingCategory(id: String) = withContext(Dispatchers.IO) {
        supabase.from("shopping_categories").delete {
            filter {
                eq("id", id)
                eq("household_id", householdId)
            }
        }
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
        supabase.from("household_settings").upsert(
            SettingsRow(
                householdId = householdId,
                sandwichRecipeId = recipeId
            )
        ) {
            onConflict = "household_id"
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

    suspend fun setDayPrepLimit(
        weeklyPlanId: String,
        day: DayOfWeek,
        maxPrepMinutes: Int?
    ) = withContext(Dispatchers.IO) {
        if (maxPrepMinutes == null) {
            supabase.from("weekly_day_constraints").delete {
                filter {
                    eq("household_id", householdId)
                    eq("weekly_plan_id", weeklyPlanId)
                    eq("day_of_week", day.value)
                }
            }
        } else {
            supabase.from("weekly_day_constraints").upsert(
                DayConstraintRow(
                    householdId = householdId,
                    weeklyPlanId = weeklyPlanId,
                    dayOfWeek = day.value,
                    maxPrepMinutes = maxPrepMinutes
                )
            ) {
                onConflict = "weekly_plan_id,day_of_week"
            }
        }
    }

    suspend fun replaceLunchRecipes(
        weeklyPlanId: String,
        recipeIds: List<String>
    ) = withContext(Dispatchers.IO) {
        supabase.from("weekly_lunch_recipes").delete {
            filter {
                eq("household_id", householdId)
                eq("weekly_plan_id", weeklyPlanId)
            }
        }

        if (recipeIds.isNotEmpty()) {
            supabase.from("weekly_lunch_recipes").insert(
                recipeIds.mapIndexed { position, recipeId ->
                    WeeklyLunchRecipeRow(
                        householdId = householdId,
                        weeklyPlanId = weeklyPlanId,
                        recipeId = recipeId,
                        position = position
                    )
                }
            )
        }
    }

    suspend fun setShoppingIncluded(
        weeklyPlanId: String,
        ingredientId: String,
        included: Boolean
    ) = withContext(Dispatchers.IO) {
        supabase.from("shopping_inclusions").upsert(
            ShoppingInclusionRow(
                householdId = householdId,
                weeklyPlanId = weeklyPlanId,
                ingredientId = ingredientId,
                included = included
            )
        ) {
            onConflict = "weekly_plan_id,ingredient_id"
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
        supabase.from("shopping_inclusions").delete {
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

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    internal suspend fun startRealtime(
        scope: CoroutineScope,
        onEvent: suspend (CloudRealtimeEvent) -> Unit
    ) {
        stopRealtime()

        val channel = supabase.realtime.channel("household-$householdId")
        val domainSignals = CloudSyncDomain.entries.associateWith {
            MutableSharedFlow<Unit>(extraBufferCapacity = 32)
        }
        val watchedTables = listOf(
            "ingredients",
            "ingredient_protein_flags",
            "ingredient_unit_conversions",
            "ingredient_conversion_versions",
            "shopping_categories",
            "recipe_categories",
            "recipe_category_assignments",
            "recipes",
            "recipe_rotation_settings",
            "recipe_time_settings",
            "recipe_ingredients",
            "recipe_pantry_prerequisites",
            "pantry_items",
            "pantry_lots",
            "household_settings",
            "weekly_plans",
            "planned_meals",
            "weekly_day_constraints",
            "weekly_lunch_recipes",
            "shopping_checks",
            "shopping_inclusions",
            "meal_history"
        )

        val watcherJobs = watchedTables.mapNotNull { table ->
            val domain = syncDomainForTable(table)
                ?: return@mapNotNull null

            channel.postgresChangeFlow<PostgresAction>(
                schema = "public"
            ) {
                this.table = table
            }.onEach { action ->
                when (
                    val event = realtimeEventFor(
                        table = table,
                        action = action,
                        expectedHouseholdId = householdId
                    )
                ) {
                    is CloudRealtimeEvent.PantryChanged,
                    is CloudRealtimeEvent.ShoppingCheckedChanged,
                    is CloudRealtimeEvent.ShoppingIncludedChanged ->
                        onEvent(event)

                    is CloudRealtimeEvent.DomainChanged ->
                        domainSignals.getValue(domain).tryEmit(Unit)

                    null -> Unit
                }
            }.launchIn(scope)
        }

        val reconciliationJobs = domainSignals.map { (domain, signal) ->
            signal
                .debounce(250.milliseconds)
                .onEach {
                    onEvent(
                        CloudRealtimeEvent.DomainChanged(domain)
                    )
                }
                .launchIn(scope)
        }

        realtimeJobs = watcherJobs + reconciliationJobs
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
        // Never overwrite the actual week_start with today's Monday when
        // saving a future/past plan. The plan ID is the source of truth.
        val existing = supabase.from("weekly_plans").select {
            filter {
                eq("household_id", householdId)
                eq("id", weeklyPlanId)
            }
        }.decodeList<WeeklyPlanRow>().firstOrNull()
            ?: error("Weekly plan no longer exists")

        supabase.from("weekly_plans").upsert(
            existing.copy(
                sandwichDay = sandwichDay?.value,
                sandwichRecipeId = sandwichRecipeId
            )
        ) {
            onConflict = "id"
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

        // Ingredient images are only used as 60dp pantry thumbnails.
        // Resize them at upload, not just at display, to avoid huge originals.
        val bytes = prepareImage(
            imageUri.toUri(),
            maxDimension = if (kind == "ingredients") PantryImageRules.INGREDIENT_UPLOAD_MAX_PIXELS else 1600
        )
        val path = $$"$$householdId/$$kind/$$objectId-${UUID.randomUUID()}.jpg"
        supabase.storage["meal-images"].upload(path, bytes) {
            upsert = false
        }

        if (existingPath != null && existingPath != path) {
            deleteImage(existingPath)
        }

        return path to MealImageReference.forPath(path)
    }

    private suspend fun deleteImage(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            supabase.storage["meal-images"].delete(path)
        }
        imageUrls.invalidate(path)
    }

    private fun prepareImage(uri: Uri, maxDimension: Int): ByteArray {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxDimension) {
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
