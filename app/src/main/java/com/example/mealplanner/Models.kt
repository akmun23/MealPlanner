package com.example.mealplanner

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import java.util.Locale
import kotlin.math.abs

enum class QuantityType {
    MASS, VOLUME, COUNT, PACKAGE
}

fun QuantityType.danishName(): String = when (this) {
    QuantityType.MASS -> "vægt"
    QuantityType.VOLUME -> "volumen"
    QuantityType.COUNT -> "stk."
    QuantityType.PACKAGE -> "pakke/dåse"
}

/** Canonical physical storage unit, not the preferred unit shown to users. */
fun QuantityType.baseMeasurementUnit(): MeasurementUnit = when (this) {
    QuantityType.MASS -> MeasurementUnit.GRAM
    QuantityType.VOLUME -> MeasurementUnit.MILLILITRE
    QuantityType.COUNT -> MeasurementUnit.PIECE
    QuantityType.PACKAGE -> MeasurementUnit.PACK
}

enum class MeasurementUnit(
    val symbol: String,
    val quantityType: QuantityType,
    val toBaseFactor: Double
) {
    GRAM("g", QuantityType.MASS, 1.0),
    KILOGRAM("kg", QuantityType.MASS, 1000.0),
    MILLILITRE("ml", QuantityType.VOLUME, 1.0),
    TEASPOON("tsk.", QuantityType.VOLUME, 5.0),
    TABLESPOON("spsk.", QuantityType.VOLUME, 15.0),
    LITRE("l", QuantityType.VOLUME, 1000.0),
    PIECE("stk.", QuantityType.COUNT, 1.0),
    PACK("pakke", QuantityType.PACKAGE, 1.0),
    CAN("dåse", QuantityType.PACKAGE, 1.0);

    fun toBase(amount: Double): Double = amount * toBaseFactor
    fun fromBase(amountBase: Double): Double = amountBase / toBaseFactor

    /** Convert the displayed amount while preserving its underlying quantity. */
    fun convertAmountTo(amount: Double, target: MeasurementUnit): Double? {
        if (!amount.isFinite() || quantityType != target.quantityType) return null
        return target.fromBase(toBase(amount))
    }

    fun labelFor(amount: Double): String = when (this) {
        PACK -> if (abs(amount - 1.0) < 0.00001) "pakke" else "pakker"
        CAN -> if (abs(amount - 1.0) < 0.00001) "dåse" else "dåser"
        else -> symbol
    }

    fun selectorLabel(): String = when (this) {
        PACK -> "pakke"
        CAN -> "dåse"
        PIECE -> "stk."
        else -> symbol
    }

    companion object {
        fun compatibleWith(type: QuantityType): List<MeasurementUnit> =
            entries.filter { it.quantityType == type }

        fun defaultFor(type: QuantityType): MeasurementUnit =
            when (type) {
                QuantityType.MASS -> GRAM
                QuantityType.VOLUME -> LITRE
                QuantityType.COUNT -> PIECE
                QuantityType.PACKAGE -> PACK
            }
    }
}

@Serializable
data class IngredientCategory(
    val id: String,
    val name: String,
    val position: Int = 0
)

@Serializable
data class RecipeCategoryOption(
    val id: String,
    val name: String,
    val position: Int = 0
)

fun String.normalizedDisplayName(): String {
    val clean = trim()
    if (clean.isBlank()) return clean
    val locale = Locale.forLanguageTag("da-DK")
    return clean.substring(0, 1).uppercase(locale) + clean.substring(1)
}

fun parseDanishDecimal(value: String): Double? =
    value.trim()
        .replace(',', '.')
        .toDoubleOrNull()

@Serializable
data class Ingredient(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantityType: QuantityType,
    val preferredUnit: MeasurementUnit,
    val shoppingCategoryId: String? = null,
    val isProtein: Boolean = false,
    val neverBuy: Boolean = false,
    val imageUri: String? = null,
    val imagePath: String? = null,
    // Household-approved conversions are synced separately from ingredients,
    // cached with the ingredient and keyed by ingredient UUID on the server.
    val unitConversions: List<IngredientUnitConversion> = emptyList(),
    val unitConversionRevision: Long = 0L
)

/**
 * Existing quantities are stored in base units without a quantity-kind tag.
 * Reinterpreting those values as mass/volume/count would silently corrupt data.
 */
internal fun canChangeIngredientQuantityType(
    ingredient: Ingredient,
    newType: QuantityType,
    pantryBase: Double,
    recipes: List<Recipe>
): Boolean = ingredient.quantityType == newType || (
        pantryBase == 0.0 &&
                recipes.none { recipe ->
                    recipe.ingredients.any { it.ingredientId == ingredient.id } ||
                            recipe.pantryPrerequisites.any { it.ingredientId == ingredient.id }
                }
        )

@Serializable
data class RecipeIngredient(
    val ingredientId: String,
    /** Physical requirement in this ingredient's stored base dimension. */
    val amountBase: Double,
    /** User-authored recipe display, frozen at save time. Null for legacy rows. */
    val displayAmount: Double? = null,
    val displayUnitCode: String? = null,
    /** Snapshot, not a live lookup of the current product packaging. */
    val basePerDisplayUnit: Double? = null,
    /** True if the saved conversion was a household estimate. */
    val conversionApproximate: Boolean = false
)

/** An ordered recipe instruction: level 0 is a numbered main step, level 1 a lettered substep. */
@Serializable
data class RecipeInstructionStep(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val level: Int = 0
)

@Serializable
data class RecipePantryPrerequisite(
    val ingredientId: String,
    val minimumAmountBase: Double
)

enum class MealType {
    DINNER,
    LUNCH
}

fun MealType.danishName(): String = when (this) {
    MealType.DINNER -> "Aftensmad"
    MealType.LUNCH -> "Frokost"
}

@Serializable
data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mealType: MealType = MealType.DINNER,
    val autoRotationEnabled: Boolean = true,
    val prepMinutes: Int? = null,
    val categoryId: String? = null,
    val notes: String = "",
    val imageUri: String? = null,
    val imagePath: String? = null,
    val ingredients: List<RecipeIngredient>,
    val pantryPrerequisites: List<RecipePantryPrerequisite> = emptyList(),
    val instructionSteps: List<RecipeInstructionStep> = emptyList(),
    val sourceUrl: String? = null
)

enum class DayMode {
    AUTO,
    AWAY
}

data class DraftRequirement(
    val ingredientId: String,
    val amount: Double,
    // Physical native unit used for legacy pantry requirements.
    val unit: MeasurementUnit,
    // A frozen recipe display can also use a confirmed cross-dimension unit.
    // Keeping this separate avoids reinterpreting old PACKAGE pantry numbers.
    val displayUnit: QuantityUnit? = null,
    val displayFactor: Double? = null,
    val displayApproximate: Boolean = false
)

/** One real-world package or a legacy amount with unknown packaging.
 * Physical amounts remain in the ingredient's existing base dimension. */
@Serializable
data class PantryLot(
    val id: String = UUID.randomUUID().toString(),
    val ingredientId: String,
    val originalBase: Double,
    val remainingBase: Double,
    val opened: Boolean = false,
    val legacyUnknown: Boolean = false,
    val productId: String? = null,
    val purchaseEventKey: String? = null,
    val bestBefore: String? = null,
    val version: Long = 1L,
    val createdAt: String = ""
)

data class ShoppingItem(
    val ingredient: Ingredient,
    val requiredBase: Double,
    val pantryBase: Double,
    /** Added to the shared list independently of recipes for this week. */
    val manualOnly: Boolean = false,
    /**
     * Extra amount the household asked for on top of the recipes (milk for
     * coffee). Already part of [requiredBase]; kept separately so the list can
     * show it and the dialog can edit it.
     */
    val extraBase: Double = 0.0
)

data class MealHistoryEntry(
    val recipeId: String,
    val eatenOn: LocalDate
)

data class PlannerResult(
    val plan: Map<DayOfWeek, String?>,
    val sandwichDay: DayOfWeek?
)

@Serializable
data class PlannerWeights(
    val pantryCoverage: Double = 20.0,
    val ingredientReuse: Double = 6.0,
    val duplicateRecipe: Double = 120.0,
    val repeatedProtein: Double = 3.0,
    val adjacentProtein: Double = 6.0,
    val repeatedCategory: Double = 11.0,
    val adjacentCategory: Double = 14.0,
    val recency7Days: Double = 90.0,
    val recency14Days: Double = 55.0,
    val recency30Days: Double = 25.0,
    /** Per time a dish was eaten in the last 60 days (at most 4 counted). */
    val frequency60Days: Double = 15.0,
    val keepSameDay: Double = 4.0,
    val previousPlanReuse: Double = 35.0,
    val jitter: Double = 10.0
)

val orderedWeekDays = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY
)

val sandwichDayPreference = listOf(
    DayOfWeek.FRIDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.SUNDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.MONDAY
)

fun prepLimitLabel(minutes: Int?): String = when (minutes) {
    null -> "Tid"
    30 -> "⚡ Hurtig"
    60 -> "⏳ God tid"
    else -> if (minutes <= 30) "⚡ Hurtig" else "⏳ God tid"
}

fun prepLimitOptionLabel(minutes: Int?): String = when (minutes) {
    null -> "Alle retter"
    30 -> "⚡ Højst 30 min"
    60 -> "⏳ Højst 60 min"
    else -> if (minutes <= 30) "⚡ Hurtig ret" else "⏳ God tid"
}

fun DayOfWeek.danishName(): String = when (this) {
    DayOfWeek.MONDAY -> "Mandag"
    DayOfWeek.TUESDAY -> "Tirsdag"
    DayOfWeek.WEDNESDAY -> "Onsdag"
    DayOfWeek.THURSDAY -> "Torsdag"
    DayOfWeek.FRIDAY -> "Fredag"
    DayOfWeek.SATURDAY -> "Lørdag"
    DayOfWeek.SUNDAY -> "Søndag"
}

fun DayOfWeek.shortDanishName(): String = when (this) {
    DayOfWeek.MONDAY -> "MAN"
    DayOfWeek.TUESDAY -> "TIR"
    DayOfWeek.WEDNESDAY -> "ONS"
    DayOfWeek.THURSDAY -> "TOR"
    DayOfWeek.FRIDAY -> "FRE"
    DayOfWeek.SATURDAY -> "LØR"
    DayOfWeek.SUNDAY -> "SØN"
}
