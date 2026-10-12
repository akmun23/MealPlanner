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
        if (missing == 0.0) return 0
        val count = ceil(missing / packBase)
        return count.takeIf { it.isFinite() && it <= Int.MAX_VALUE }?.toInt()
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
