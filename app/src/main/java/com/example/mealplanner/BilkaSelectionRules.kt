package com.example.mealplanner

/**
 * Compare unit prices only when all priced products use the same unit.
 * Search relevance is purely local, free of Prej API calls, and never
 * hides provider results or changes a saved brand/packaging choice.
 */
internal object BilkaSelectionRules {
    private val nonCooking = Regex(
        """(?:^| )\S*(?:likoer|tequila|vodka|whisky|whiskey|spiritus|snaps|froe|pottejord|plante|planter|plantejord|duftlys|shampoo|hundefoder|kattemad|legetoej)(?: |$)"""
    )

    private fun normalized(value: String): String = value.lowercase()
        .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    /**
     * Lexical ranking, not a claim that a product is edible.
     * "Fløde" ranks "fløde" and "piskefløde" over "flødelikør",
     * while all three remain visible for explicit user selection.
     */
    internal fun ingredientNameScore(ingredientName: String, productName: String): Int {
        val wanted = normalized(ingredientName)
        val name = normalized(productName)
        if (wanted.isBlank() || name.isBlank()) return 0
        if (nonCooking.containsMatchIn(name)) return -100
        if (wanted == name) return 100

        val wantedWords = wanted.split(' ').filter { it.length > 1 }
        val words = name.split(' ')
        if (wantedWords.isEmpty()) return 0
        val exact = wantedWords.count(words::contains)
        if (exact == wantedWords.size) return 85

        // Accept Danish compounds ending with the complete food name.
        // A prefix is deliberately not a match: "flødelikør" != "fløde".
        val compound = wantedWords.count { target ->
            target.length >= 4 && words.any { it.endsWith(target) && it != target }
        }
        return when {
            exact + compound == wantedWords.size -> 60
            exact > 0 -> 25
            else -> 0
        }
    }

    fun sortForIngredient(
        products: List<BilkaProduct>,
        rankById: Map<String, Int>,
        byUnitPrice: Boolean,
        chosenProductId: String? = null,
        ingredientName: String? = null
    ): List<BilkaProduct> {
        val bases = products
            .filter { it.priceOre != null && it.unitPriceOre != null }
            .mapNotNull { it.unitPriceBasis }
            .distinct()
        val comparable = byUnitPrice && bases.size == 1
        return products.sortedWith(compareBy<BilkaProduct>(
            { if (it.id == chosenProductId) 0 else 1 },
            {
                if (!byUnitPrice && ingredientName != null)
                    -ingredientNameScore(ingredientName, it.name)
                else 0
            },
            { if (!byUnitPrice) it.priceOre == null else if (comparable) it.unitPriceOre == null else false },
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
