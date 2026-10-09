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
