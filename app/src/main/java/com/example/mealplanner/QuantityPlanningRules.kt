package com.example.mealplanner

import kotlin.math.ceil
import kotlin.math.max

/**
 * A purchase suggestion in the ingredient's existing physical base (g/ml/pieces).
 * It is NOT a purchase receipt and must not credit pantry or bypass Bilka's
 * server-side quantity checks.
 */
internal data class PackagePurchasePlan(
    val missingBase: Double,
    val basePerPackage: Double,
    val packageCount: Int,
    val purchasedBase: Double,
    val surplusBase: Double,
    val conversionApproximate: Boolean
)

/**
 * Pure, no-guess rules for phase 2. The client will bind these to persisted,
 * household-approved conversion profiles in a separate change.
 */
internal object QuantityPlanningRules {
    /** Only offer recipe units for which this ingredient has a known bridge. */
    fun selectableUnits(
        ingredient: Ingredient,
        rules: List<IngredientUnitConversion>
    ): List<QuantityUnit> = QuantityUnit.entries.filter { unit ->
        QuantityConversionEngine.basePerUnit(ingredient, unit, rules) != null
    }

    /**
     * Explicit UNIT change: re-express an existing physical amount without
     * altering amountBase. This is different from changing the entered number,
     * which must create a fresh frozen requirement via RecipeQuantityRules.
     */
    fun changeDisplayUnit(
        requirement: RecipeIngredient,
        ingredient: Ingredient,
        targetUnit: QuantityUnit,
        rules: List<IngredientUnitConversion>
    ): RecipeIngredient? {
        if (requirement.ingredientId != ingredient.id ||
            !requirement.amountBase.isFinite() ||
            requirement.amountBase <= 0.0 ||
            requirement.amountBase > 1e10
        ) return null

        val factor = QuantityConversionEngine.basePerUnit(ingredient, targetUnit, rules)
            ?: return null
        val display = QuantityConversionEngine.fromBase(
            ingredient, requirement.amountBase, targetUnit, rules
        )?.takeIf { it > 0.0 && it <= 1e10 } ?: return null

        // Existing physical uncertainty does not disappear just because a
        // display changes from tablespoons or packs back to grams.
        val bridgeApproximate = if (
            targetUnit.nativeUnit?.quantityType == ingredient.quantityType
        ) false else rules.singleOrNull { it.unit == targetUnit }?.approximate == true

        val changed = requirement.copy(
            displayAmount = display,
            displayUnitCode = targetUnit.name,
            basePerDisplayUnit = factor,
            conversionApproximate = requirement.conversionApproximate || bridgeApproximate
        )
        return changed.takeIf(RecipeQuantityRules::hasValidSnapshot)
    }

    /**
     * Plan complete retail packs using a separately approved current pack
     * content. Never derive physical pack size from an old PACKAGE base number
     * and never treat an unknown product package as one physical base unit.
     */
    fun planPackages(
        ingredient: Ingredient,
        requiredBase: Double,
        pantryBase: Double,
        packageUnit: QuantityUnit,
        rules: List<IngredientUnitConversion>
    ): PackagePurchasePlan? {
        if (ingredient.quantityType == QuantityType.PACKAGE ||
            packageUnit != QuantityUnit.PACK && packageUnit != QuantityUnit.CAN ||
            !requiredBase.isFinite() || requiredBase < 0.0 || requiredBase > 1e10 ||
            !pantryBase.isFinite() || pantryBase < 0.0 || pantryBase > 1e10
        ) return null

        val rule = rules.singleOrNull { it.unit == packageUnit } ?: return null
        val packBase = QuantityConversionEngine.basePerUnit(
            ingredient, packageUnit, rules
        ) ?: return null
        val missing = max(0.0, requiredBase - pantryBase)
        val ratio = missing / packBase
        if (!ratio.isFinite() || ratio < 0.0) return null
        // Avoid a phantom extra package when binary floating point represents
        // an exact boundary as e.g. 3.0000000000000004 packages.
        // Use a few ULPs rather than a fixed tolerance that could hide a
        // genuine small shortfall for a small physical package.
        val nearest = kotlin.math.round(ratio)
        val nearWholePackage = kotlin.math.abs(ratio - nearest) <=
            8.0 * java.lang.Math.ulp(nearest)
        val rawCount = if (nearWholePackage) nearest else ceil(ratio)
        if (!rawCount.isFinite() || rawCount < 0.0 || rawCount > Int.MAX_VALUE) return null

        val count = rawCount.toInt()
        val purchased = packBase * count
        if (!purchased.isFinite() || purchased > 1e10) return null
        return PackagePurchasePlan(
            missingBase = missing,
            basePerPackage = packBase,
            packageCount = count,
            purchasedBase = purchased,
            surplusBase = max(0.0, purchased - missing),
            conversionApproximate = rule.approximate
        )
    }
}
