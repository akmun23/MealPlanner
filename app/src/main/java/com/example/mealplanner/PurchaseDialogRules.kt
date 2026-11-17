package com.example.mealplanner

/**
 * A saved default can point to a catalogue row that is no longer available or
 * whose provider quantity type disagrees with the ingredient and cannot be
 * bridged. Never initialise checkout with an ID the user cannot select: it
 * leaves Save disabled.
 *
 * Manual checkout is supported server-side and remains transactionally guarded
 * against double-adding a purchase to pantry.
 */
internal object PurchaseDialogRules {
    /**
     * Products measured in another kind (a 280 g baguette for an ingredient
     * counted in pieces) stay selectable when the household confirmed a pack
     * size or the ingredient has a conversion for the product's unit.
     */
    fun compatibleProducts(
        ingredient: Ingredient,
        products: List<BilkaProduct>,
        confirmedPackBase: (BilkaProduct) -> Double? = { null }
    ): List<BilkaProduct> =
        products.filter { product ->
            product.quantityKind == null ||
                product.quantityKind.name == ingredient.quantityType.name ||
                BilkaPackageMath.applicablePackBase(
                    ingredient, product, confirmedPackBase(product)
                ) != null
        }

    fun selectedProductId(
        products: List<BilkaProduct>,
        previousProductId: String?,
        defaultProductId: String?
    ): String? {
        val ids = products.mapTo(HashSet<String>()) { it.id }
        return listOfNotNull(previousProductId, defaultProductId)
            .firstOrNull { it in ids }
            ?: products.firstOrNull()?.id
    }

    fun suggestedPurchasedBase(
        requiredBase: Double,
        pantryBase: Double,
        preferredUnit: MeasurementUnit
    ): Double {
        val missing = requiredBase - pantryBase
        return when {
            missing.isFinite() && missing > 0.0 -> missing
            requiredBase.isFinite() && requiredBase > 0.0 -> requiredBase
            else -> preferredUnit.toBase(1.0)
        }
    }

    fun validPurchasedAmount(amountText: String, unit: MeasurementUnit): Double? {
        val entered = parseDanishDecimal(amountText)
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return unit.toBase(entered)
            .takeIf { it.isFinite() && it > 0.0 && it <= 10_000_000.0 }
    }
}
