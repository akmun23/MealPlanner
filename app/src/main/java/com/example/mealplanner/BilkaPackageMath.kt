package com.example.mealplanner

import kotlin.math.ceil
import kotlin.math.max

/** The unit a product's package_base_amount is expressed in. */
internal fun BilkaQuantityKind.nativeUnit(): QuantityUnit = when (this) {
    BilkaQuantityKind.MASS -> QuantityUnit.GRAM
    BilkaQuantityKind.VOLUME -> QuantityUnit.MILLILITRE
    BilkaQuantityKind.COUNT -> QuantityUnit.PIECE
    BilkaQuantityKind.PACKAGE -> QuantityUnit.PACK
}

/** Shopping amounts use the ingredient's canonical base unit (g, ml, pieces, packs). */
internal object BilkaPackageMath {
    /**
     * How much of the ingredient's base unit one purchased pack holds.
     *
     * 1. A size the household confirmed explicitly always wins, whatever the
     *    product's unit kind is.
     * 2. A product measured in the ingredient's own kind is used as listed.
     * 3. Otherwise (a 280 g baguette for an ingredient counted in pieces) the
     *    ingredient's own saved conversion bridges the two, e.g. "1 stk = 280 g".
     *    Without such a conversion nothing is guessed and null is returned.
     */
    fun applicablePackBase(
        ingredient: Ingredient,
        product: BilkaProduct,
        correction: Double? = null
    ): Double? {
        correction?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
        val kind = product.quantityKind ?: return null
        val amount = product.packageBaseAmount
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (kind.name == ingredient.quantityType.name) return amount
        return QuantityConversionEngine.toBase(
            ingredient, amount, kind.nativeUnit(), ingredient.unitConversions
        )
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
