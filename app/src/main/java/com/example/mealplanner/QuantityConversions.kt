package com.example.mealplanner

import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.Serializable

/**
 * Extensible input vocabulary. These are labels, not storage dimensions.
 * GARLIC_CLOVE / GARLIC_HEAD deliberately have NO universal conversion:
 * one garlic head does not contain a fixed number of cloves.
 */
@Serializable
enum class QuantityUnit(val symbol: String, val nativeUnit: MeasurementUnit?) {
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

    fun labelFor(amount: Double): String = when (this) {
        PACK -> if (amount == 1.0) "pakke" else "pakker"
        CAN -> if (amount == 1.0) "dåse" else "dåser"
        GARLIC_CLOVE -> if (amount == 1.0) "fed" else "fed"
        GARLIC_HEAD -> "hvidløg"
        else -> symbol
    }

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
@Serializable
data class IngredientUnitConversion(
    val unit: QuantityUnit,
    val baseAmountPerUnit: Double,
    val approximate: Boolean = false,
    val source: String = "household-confirmed",
    val revision: Long = 1L
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
        // Conflicting household profiles must never be resolved by list order.
        // DB/profile syncing will enforce uniqueness; fail closed meanwhile.
        val matching = rules.filter { it.unit == unit }
        return matching.singleOrNull()
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
    /**
     * Correct a confirmed measuring-unit definition across saved recipes.
     * Unlike a changing retail PACK/CAN size, a corrected TABLESPOON,
     * TEASPOON, CLOVE or HEAD definition changes what the recipe's written
     * measure meant. Only snapshots still matching the prior verified profile
     * can be rewritten. Unknown/legacy rows are never guessed.
     */
    fun correctedIngredientMeasure(
        requirement: RecipeIngredient,
        previous: List<IngredientUnitConversion>,
        updated: List<IngredientUnitConversion>
    ): RecipeIngredient {
        if (!hasValidSnapshot(requirement)) return requirement
        val code = requirement.displayUnitCode ?: return requirement
        val unit = QuantityUnit.entries.firstOrNull { it.name == code }
            ?: return requirement
        if (unit == QuantityUnit.PACK || unit == QuantityUnit.CAN) return requirement
        val oldRule = previous.singleOrNull { it.unit == unit } ?: return requirement
        val newRule = updated.singleOrNull { it.unit == unit } ?: return requirement
        val former = requirement.basePerDisplayUnit ?: return requirement
        if (kotlin.math.abs(former - oldRule.baseAmountPerUnit) >
            kotlin.math.max(0.000001, kotlin.math.abs(former) * 1e-8)
        ) return requirement
        val revisedBase = requirement.displayAmount!! * newRule.baseAmountPerUnit
        if (!revisedBase.isFinite() || revisedBase <= 0.0 || revisedBase > 1e10 ||
            !newRule.baseAmountPerUnit.isFinite() ||
            newRule.baseAmountPerUnit <= 0.0 || newRule.baseAmountPerUnit > 1e9
        ) return requirement
        return requirement.copy(
            amountBase = revisedBase,
            basePerDisplayUnit = newRule.baseAmountPerUnit,
            conversionApproximate = newRule.approximate
        )
    }

    fun freeze(
        ingredientId: String,
        displayAmount: Double,
        displayUnit: MeasurementUnit
    ): RecipeIngredient? {
        if (!displayAmount.isFinite() || displayAmount <= 0.0 || displayAmount > 1e10) return null
        val base = displayUnit.toBase(displayAmount)
        if (!base.isFinite() || base <= 0.0 || base > 1e10) return null
        return RecipeIngredient(
            ingredientId = ingredientId,
            amountBase = base,
            displayAmount = displayAmount,
            displayUnitCode = displayUnit.name,
            basePerDisplayUnit = displayUnit.toBaseFactor
        )
    }

    /** Used in the next editor phase once the household has confirmed a
     *  package/weight or clove/head conversion for this ingredient. */
    fun freeze(
        ingredient: Ingredient,
        displayAmount: Double,
        displayUnit: QuantityUnit,
        rules: List<IngredientUnitConversion>
    ): RecipeIngredient? {
        val factor = QuantityConversionEngine.basePerUnit(
            ingredient, displayUnit, rules
        ) ?: return null
        val base = QuantityConversionEngine.toBase(
            ingredient, displayAmount, displayUnit, rules
        ) ?: return null
        val estimated = if (displayUnit.nativeUnit?.quantityType == ingredient.quantityType) {
            false
        } else rules.singleOrNull { it.unit == displayUnit }?.approximate == true
        return RecipeIngredient(
            ingredientId = ingredient.id,
            amountBase = base,
            displayAmount = displayAmount,
            displayUnitCode = displayUnit.name,
            basePerDisplayUnit = factor,
            conversionApproximate = estimated
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
            val displayUnit = QuantityUnit.entries.firstOrNull {
                it.name == requirement.displayUnitCode
            }
            if (displayUnit != null) {
                val amount = requirement.displayAmount!!
                val estimateLabel = if (requirement.conversionApproximate) {
                    " (omregning anslået)"
                } else ""
                return "${formatDisplayNumber(amount)} ${displayUnit.labelFor(amount)}${estimateLabel}"
            }
        }
        // Older recipes never recorded the chosen display unit.
        // Fallback is explicitly a display of their legacy canonical amount.
        val preferred = ingredient.preferredUnit
        return "${formatDisplayNumber(preferred.fromBase(requirement.amountBase))} " +
            preferred.labelFor(preferred.fromBase(requirement.amountBase))
    }

    /**
     * The legacy editor cannot yet express cross-dimension recipe units.
     * It temporarily presents them in the ingredient's base dimension.
     * Editing a recipe title/notes must not silently rewrite an unchanged
     * "1 pack" snapshot into "200 g". A real quantity change uses the new
     * editor amount, and supported native units remain freely editable.
     */
    fun retainUneditableSnapshot(
        previous: RecipeIngredient?,
        edited: RecipeIngredient,
        ingredient: Ingredient,
        displayExplicitlyEdited: Boolean = false
    ): RecipeIngredient {
        // A deliberate unit selection must win even when the physical amount
        // stays the same (for example 1 frozen pack -> 200 g or 0.2 kg).
        if (displayExplicitlyEdited ||
            previous == null ||
            previous.ingredientId != edited.ingredientId ||
            !hasValidSnapshot(previous)
        ) return edited

        val nativeUnit = MeasurementUnit.entries.firstOrNull {
            it.name == previous.displayUnitCode
        }
        if (nativeUnit != null && nativeUnit.quantityType == ingredient.quantityType) {
            return edited
        }

        if (!edited.amountBase.isFinite() ||
            abs(previous.amountBase - edited.amountBase) >
            max(0.000001, abs(previous.amountBase) * 1e-8)
        ) return edited

        return previous
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
