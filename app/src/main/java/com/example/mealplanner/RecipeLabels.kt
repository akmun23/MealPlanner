package com.example.mealplanner

/**
 * A recipe without an ingredient marked as protein is displayed as
 * "Vegetarisk". Stored recipe categories are never rewritten.
 */
internal fun recipeProteinLabel(proteinNames: List<String>): String =
    if (proteinNames.isEmpty()) "Vegetarisk"
    else proteinNames.joinToString(" + ")

internal fun recipeCategoryDisplayName(
    categoryName: String,
    proteinNames: List<String>
): String = if (
    proteinNames.isEmpty() &&
    categoryName.equals("Andet", ignoreCase = true)
) {
    "Vegetarisk"
} else {
    categoryName
}

/**
 * Keep recipe cards concise: dinner is the default meal type, while lunch
 * remains explicit. Protein is rendered separately so it cannot be truncated
 * behind category or timing metadata.
 */
internal fun recipeCompactMeta(recipe: Recipe, categoryName: String): String =
    buildList {
        if (recipe.mealType == MealType.LUNCH) add("Frokost")
        add(categoryName)
        if (recipe.mealType == MealType.DINNER && !recipe.autoRotationEnabled) {
            add("Kun manuelt")
        }
        recipe.prepMinutes?.let { add("$it min") }
    }.joinToString(" · ")
