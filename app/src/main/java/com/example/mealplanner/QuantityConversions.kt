package com.example.mealplanner

import kotlin.math.abs
import kotlin.math.max

/**
 * Extensible input vocabulary. These are labels, not storage dimensions.
 * GARLIC_CLOVE / GARLIC_HEAD deliberately have NO universal conversion:
 * one garlic head does not contain a fixed number of cloves.
 */
internal enum class QuantityUnit(val symbol: String, val nativeUnit: MeasurementUnit?) {
    GRAM("g", MeasurementUnit.GRAM),
    KILOGRAM("kg", MeasurementUnit.KILOGRAM),
    MILLILITRE("ml", MeasurementUnit.MILLILITRE),
    LITRE("l", MeasurementUnit.LITRE),
    TEASPOON("tsk.", MeasurementUnit.TEASPOON),
    TABLESPOON("spsk.", MeasurementUnit.TABLESPOON),
    PIECE("stk.", MeasurementUnit.PIECE),
    PACK("pakke", MeasurementUnit.PACK),
    CAN("dåse", MeasurementUnit.CAN),
    GARLIC_CLOVE("fed", null),
    GARLIC_HEAD("hvidløg", null);

    companion object {
        fun fromLegacy(unit: MeasurementUnit): QuantityUnit =
            entries.first { it.name == unit.name }
    }
}

/**
 * A user-confirmed per-ingredient bridge to that ingredient's CURRENT base.
 * The base is still legacy g/ml/pieces/packs until an explicit, separate
 * per-ingredient base migration. Never reinterpret historical stock silently.
 */
internal data class IngredientUnitConversion(
    val unit: QuantityUnit,
    val baseAmountPerUnit: Double,
    val approximate: Boolean = false
)

/**
 * Safe, no-guess conversions. Fixed physical factors are exact only INSIDE
 * their dimension. Cross-dimension factors must be explicitly supplied.
 */
internal object QuantityConversionEngine {
    fun basePerUnit(
        ingredient: Ingredient,
        unit: QuantityUnit,
        rules: List<IngredientUnitConversion> = emptyList()
    ): Double? {
        val native = unit.nativeUnit
        if (native != null && native.quantityType == ingredient.quantityType) {
            return native.toBaseFactor
        }
        return rules.firstOrNull { it.unit == unit }
            ?.baseAmountPerUnit
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 1e9 }
    }

    fun toBase(
        ingredient: Ingredient,
        amount: Double,
        unit: QuantityUnit,
        rules: List<IngredientUnitConversion> = emptyList()
    ): Double? {
        if (!amount.isFinite() || amount <= 0.0) return null
        val factor = basePerUnit(ingredient, unit, rules) ?: return null
        return (amount * factor).takeIf { it.isFinite() && it > 0.0 && it <= 1e10 }
    }

    fun fromBase(
        ingredient: Ingredient,
        amountBase: Double,
        unit: QuantityUnit,
        rules: List<IngredientUnitConversion> = emptyList()
    ): Double? {
        if (!amountBase.isFinite() || amountBase < 0.0) return null
        val factor = basePerUnit(ingredient, unit, rules) ?: return null
        return (amountBase / factor).takeIf { it.isFinite() && it >= 0.0 }
    }
}

/**
 * A recipe is an immutable physical requirement plus a presentation snapshot.
 * Altering product defaults NEVER changes amountBase or the saved display.
 * Nullable fields preserve old snapshots and queued mutations.
 */
internal object RecipeQuantityRules {
    fun freeze(
        ingredientId: String,
        displayAmount: Double,
        displayUnit: MeasurementUnit
    ): RecipeIngredient? {
        if (!displayAmount.isFinite() || displayAmount <= 0.0) return null
        val base = displayUnit.toBase(displayAmount)
        if (!base.isFinite() || base <= 0.0) return null
        return RecipeIngredient(
            ingredientId = ingredientId,
            amountBase = base,
            displayAmount = displayAmount,
            displayUnitCode = displayUnit.name,
            basePerDisplayUnit = displayUnit.toBaseFactor
        )
    }

    fun hasValidSnapshot(requirement: RecipeIngredient): Boolean {
        val amount = requirement.displayAmount ?: return false
        val factor = requirement.basePerDisplayUnit ?: return false
        if (requirement.displayUnitCode.isNullOrBlank() ||
            !amount.isFinite() || amount <= 0.0 ||
            !factor.isFinite() || factor <= 0.0 ||
            !requirement.amountBase.isFinite()
        ) return false
        val computed = amount * factor
        return computed.isFinite() &&
            abs(computed - requirement.amountBase) <=
            max(0.000001, abs(requirement.amountBase) * 1e-8)
    }

    fun amountAndUnit(requirement: RecipeIngredient, ingredient: Ingredient): String {
        if (hasValidSnapshot(requirement)) {
            val label = QuantityUnit.entries.firstOrNull {
                it.name == requirement.displayUnitCode
            }?.symbol ?: requirement.displayUnitCode.orEmpty()
            if (label.isNotBlank()) {
                return "${formatDisplayNumber(requirement.displayAmount!!)} $label"
            }
        }
        // Older recipes never recorded the chosen display unit.
        // Fallback is explicitly a display of their legacy canonical amount.
        val preferred = ingredient.preferredUnit
        return "${formatDisplayNumber(preferred.fromBase(requirement.amountBase))} " +
            preferred.labelFor(preferred.fromBase(requirement.amountBase))
    }

    fun legacyEditableAmount(requirement: RecipeIngredient, ingredient: Ingredient): Pair<Double, MeasurementUnit> {
        if (hasValidSnapshot(requirement)) {
            val unit = MeasurementUnit.entries.firstOrNull {
                it.name == requirement.displayUnitCode
            }
            if (unit != null && unit.quantityType == ingredient.quantityType) {
                return requirement.displayAmount!! to unit
            }
        }
        return ingredient.preferredUnit.fromBase(requirement.amountBase) to ingredient.preferredUnit
    }
}
