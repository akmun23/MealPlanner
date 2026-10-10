package com.example.mealplanner

/**
 * Compare unit prices only when both products use the same comparable unit.
 * Brand and package size remain tied to each concrete product ID.
 */
internal object BilkaSelectionRules {
    fun sortForIngredient(
        products: List<BilkaProduct>,
        rankById: Map<String, Int>,
        byUnitPrice: Boolean
    ): List<BilkaProduct> {
        val bases = products.mapNotNull { it.unitPriceBasis }.distinct()
        val comparable = byUnitPrice && bases.size == 1
        return products.sortedWith(compareBy<BilkaProduct>(
            { if (comparable) it.unitPriceOre == null else false },
            {
                if (comparable) it.unitPriceOre ?: Double.MAX_VALUE
                else (rankById[it.id] ?: Int.MAX_VALUE).toDouble()
            },
            { it.brandLabel },
            { it.name }
        ))
    }

    fun canConfirm(
        selectedProductId: String?,
        suggestedProductIds: Set<String>,
        currentDefaultId: String?
    ): Boolean =
        selectedProductId != null &&
            selectedProductId in suggestedProductIds &&
            selectedProductId != currentDefaultId
}
