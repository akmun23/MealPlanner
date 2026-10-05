package com.example.mealplanner

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

enum class QuantityType {
    MASS, VOLUME, COUNT, PACKAGE
}

fun QuantityType.danishName(): String = when (this) {
    QuantityType.MASS -> "vægt"
    QuantityType.VOLUME -> "væske"
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
    LITRE("l", QuantityType.VOLUME, 1000.0),
    PIECE("stk.", QuantityType.COUNT, 1.0),
    CAN("dåse", QuantityType.PACKAGE, 1.0);

    fun toBase(amount: Double): Double = amount * toBaseFactor
    fun fromBase(amountBase: Double): Double = amountBase / toBaseFactor

    fun labelFor(amount: Double): String = when (this) {
        CAN -> if (abs(amount - 1.0) < 0.00001) "dåse" else "dåser"
        else -> symbol
    }

    fun selectorLabel(): String = when (this) {
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
                QuantityType.PACKAGE -> CAN
            }
    }
}

data class Ingredient(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantityType: QuantityType,
    val preferredUnit: MeasurementUnit,
    val imageUri: String? = null,
    val imagePath: String? = null
)

data class RecipeIngredient(
    val ingredientId: String,
    val amountBase: Double
)

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val proteinTag: String = "",
    val categoryTag: String = "",
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

data class PlannerWeights(
    val pantryCoverage: Double = 20.0,
    val ingredientReuse: Double = 12.0,
    val duplicateRecipe: Double = 120.0,
    val repeatedProtein: Double = 15.0,
    val adjacentProtein: Double = 28.0,
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
