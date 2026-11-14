package com.example.mealplanner

/**
 * Rough price of the week's shopping list, from the Bilka product the
 * household chose as standard for each ingredient (see [bilkaShoppingLine],
 * which prints the same per-line figure). Lines that already have a recorded
 * purchase use what was actually paid, or - when no amount was entered - the
 * current price of the product that was bought times the packages bought.
 */
internal data class ShoppingPriceEstimate(
    val totalOre: Long,
    /** Lines whose cost is part of [totalOre]. */
    val pricedLines: Int,
    /** Lines that still have to be bought but have no usable price. */
    val unpricedLines: Int
)

internal fun estimateShoppingPrice(
    items: List<ShoppingItem>,
    context: BilkaShoppingContext?,
    catalog: BilkaProductCatalog?
): ShoppingPriceEstimate {
    var total = 0L
    var priced = 0
    var unpriced = 0

    for (item in items) {
        val ingredientId = item.ingredient.id
        val recorded = context?.purchase(ingredientId)
        if (recorded?.purchasedAmountBase != null) {
            val paid = recorded.paidTotalOre?.takeIf { it >= 0L }
            val cost = paid ?: recorded.productId
                ?.let { catalog?.product(it)?.priceOre }
                ?.let { price ->
                    recorded.packageCount?.let { BilkaPackageMath.estimatedCostOre(price, it) }
                }
            if (cost != null) {
                total += cost
                priced++
            } else {
                unpriced++
            }
            continue
        }

        // A manual line without an amount has to be bought, but its size is unknown.
        val needsBuying = (item.manualOnly && item.extraBase <= 0.0) ||
            BilkaPackageMath.missing(item.requiredBase, item.pantryBase) > 0.0
        if (!needsBuying) continue

        val standard = context?.default(ingredientId)
        val product = standard?.let { catalog?.product(it.productId) }
        if (standard == null || product == null) {
            unpriced++
            continue
        }
        val packBase = BilkaPackageMath.applicablePackBase(
            item.ingredient, product, standard.packageBaseAmountOverride
        )
        val packages = BilkaPackageMath.packageCount(
            item.requiredBase, item.pantryBase, packBase
        )
        val cost = packages?.let { BilkaPackageMath.estimatedCostOre(product.priceOre, it) }
        if (cost == null) {
            unpriced++
        } else {
            total += cost
            priced++
        }
    }
    return ShoppingPriceEstimate(total, priced, unpriced)
}
