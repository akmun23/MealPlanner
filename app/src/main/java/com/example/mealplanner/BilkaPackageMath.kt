package com.example.mealplanner

import kotlin.math.ceil
import kotlin.math.max

/** Shopping amounts use the ingredient's canonical base unit (g, ml, pieces, packs). */
internal object BilkaPackageMath {
    fun applicablePackBase(
        ingredient: Ingredient,
        product: BilkaProduct,
        correction: Double? = null
    ): Double? {
        val kind = product.quantityKind ?: return null
        if (kind.name != ingredient.quantityType.name) return null
        val amount = correction ?: product.packageBaseAmount
        return amount?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun missing(requiredBase: Double, pantryBase: Double): Double =
        max(0.0, requiredBase - pantryBase)

    fun packageCount(requiredBase: Double, pantryBase: Double, packBase: Double?): Int? {
        if (packBase == null || !packBase.isFinite() || packBase <= 0.0) return null
        val missing = missing(requiredBase, pantryBase)
        if (!missing.isFinite() || missing < 0.0) return null
        if (missing == 0.0) return 0

        val ratio = missing / packBase
        if (!ratio.isFinite() || ratio < 0.0) return null

        // A binary rounding artefact at an exact boundary must not cause
        // a phantom extra pack (e.g. 0.1 + 0.2 divided by 0.1).
        // Only snap within a few ULPs; real shortfalls still round UP.
        val nearestWhole = kotlin.math.round(ratio)
        val nearWhole = kotlin.math.abs(ratio - nearestWhole) <=
            8.0 * java.lang.Math.ulp(nearestWhole)
        val count = if (nearWhole) nearestWhole else ceil(ratio)
        return count.takeIf { it.isFinite() && it >= 0.0 && it <= Int.MAX_VALUE }?.toInt()
    }

    fun purchasedBase(packBase: Double, packages: Int): Double? {
        if (!packBase.isFinite() || packBase <= 0 || packages < 1) return null
        val amount = packBase * packages
        return amount.takeIf { it.isFinite() && it > 0.0 && it <= 999_999_999.0 }
    }

    fun estimatedCostOre(priceOre: Long?, packages: Int): Long? {
        if (priceOre == null || priceOre < 0 || packages < 1) return null
        return priceOre.takeIf { it <= Long.MAX_VALUE / packages }?.times(packages)
    }
}
