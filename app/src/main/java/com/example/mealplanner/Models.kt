package com.example.mealplanner

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

data class IngredientCategory(
    val id: String,
    val name: String,
    val position: Int = 0
)

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

data class Ingredient(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantityType: QuantityType,
    val preferredUnit: MeasurementUnit,
    val shoppingCategoryId: String? = null,
    val isProtein: Boolean = false,
    val imageUri: String? = null,
    val imagePath: String? = null
)

data class RecipeIngredient(
    val ingredientId: String,
    val amountBase: Double
)

enum class MealType {
    DINNER,
    LUNCH
}

fun MealType.danishName(): String = when (this) {
    MealType.DINNER -> "Aftensmad"
    MealType.LUNCH -> "Frokost"
}

enum class RecipeCategory {
    PASTA,
    CURRY,
    STEW,
    OVEN,
    BURGER,
    TACOS,
    SANDWICH_WRAP,
    RICE,
    SOUP,
    SALAD_BOWL,
    PIZZA_PIE,
    OTHER
}

fun RecipeCategory.danishName(): String = when (this) {
    RecipeCategory.PASTA -> "Pasta"
    RecipeCategory.CURRY -> "Karry"
    RecipeCategory.STEW -> "Gryderet"
    RecipeCategory.OVEN -> "Ovnret"
    RecipeCategory.BURGER -> "Burger"
    RecipeCategory.TACOS -> "Tacos"
    RecipeCategory.SANDWICH_WRAP -> "Sandwich/wrap"
    RecipeCategory.RICE -> "Risret"
    RecipeCategory.SOUP -> "Suppe"
    RecipeCategory.SALAD_BOWL -> "Salat/bowl"
    RecipeCategory.PIZZA_PIE -> "Pizza/tærte"
    RecipeCategory.OTHER -> "Andet"
}

fun String.toRecipeCategory(): RecipeCategory {
    val normalized = trim().uppercase()

    return runCatching {
        RecipeCategory.valueOf(normalized)
    }.getOrElse {
        when (trim().lowercase()) {
            "pasta" -> RecipeCategory.PASTA
            "karry" -> RecipeCategory.CURRY
            "gryderet" -> RecipeCategory.STEW
            "ovnret" -> RecipeCategory.OVEN
            "burger" -> RecipeCategory.BURGER
            "tacos" -> RecipeCategory.TACOS
            "sandwich", "sandwich/wrap", "wrap" -> RecipeCategory.SANDWICH_WRAP
            "ris", "risret" -> RecipeCategory.RICE
            "suppe" -> RecipeCategory.SOUP
            "salat", "bowl", "salat/bowl" -> RecipeCategory.SALAD_BOWL
            "pizza", "tærte", "pizza/tærte" -> RecipeCategory.PIZZA_PIE
            else -> RecipeCategory.OTHER
        }
    }
}

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mealType: MealType = MealType.DINNER,
    val autoRotationEnabled: Boolean = true,
    val prepMinutes: Int? = null,
    val proteinTag: String = "",
    val categoryTag: String = RecipeCategory.OTHER.name,
    val categoryId: String? = null,
    val notes: String = "",
    val imageUri: String? = null,
    val imagePath: String? = null,
    val ingredients: List<RecipeIngredient>
)

enum class DayMode {
    AUTO,
    AWAY,
    FIXED
}

data class DraftRequirement(
    val ingredientId: String,
    val amount: Double,
    val unit: MeasurementUnit
)

data class ShoppingItem(
    val ingredient: Ingredient,
    val requiredBase: Double,
    val pantryBase: Double
)

data class MealHistoryEntry(
    val recipeId: String,
    val eatenOn: LocalDate
)

data class PlannerResult(
    val plan: Map<DayOfWeek, String?>,
    val sandwichDay: DayOfWeek?
)

data class PlannerWeights(
    val pantryCoverage: Double = 20.0,
    val ingredientReuse: Double = 12.0,
    val duplicateRecipe: Double = 120.0,
    val repeatedProtein: Double = 3.0,
    val adjacentProtein: Double = 6.0,
    val repeatedCategory: Double = 11.0,
    val adjacentCategory: Double = 14.0,
    val recency7Days: Double = 90.0,
    val recency14Days: Double = 55.0,
    val recency30Days: Double = 25.0,
    val keepSameDay: Double = 4.0,
    val jitter: Double = 8.0
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
    20 -> "Meget hurtig"
    30 -> "Hurtig"
    45 -> "Normal"
    60 -> "God tid"
    else -> "≤ $minutes min"
}

fun prepLimitOptionLabel(minutes: Int?): String = when (minutes) {
    null -> "Ingen tidsgrænse"
    20 -> "Meget hurtig · 20 min"
    30 -> "Hurtig · 30 min"
    45 -> "Normal · 45 min"
    60 -> "God tid · 60 min"
    else -> "Maks. $minutes min"
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
