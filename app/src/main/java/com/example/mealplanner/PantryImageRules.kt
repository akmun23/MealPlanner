package com.example.mealplanner

/**
 * A user-supplied ingredient photo always overrides the product photo.
 * Bilka images are a visual fallback, not a mutation of the original photo.
 */
internal object PantryImageRules {
    const val THUMBNAIL_SIZE_DP = 60
    const val INGREDIENT_UPLOAD_MAX_PIXELS = 384

    fun displayImage(manualImageUri: String?, bilkaImageUrl: String?): String? =
        manualImageUri?.takeIf { it.isNotBlank() }
            ?: bilkaImageUrl?.takeIf {
                it.startsWith("https://", ignoreCase = true) ||
                        it.startsWith("http://", ignoreCase = true)
            }
}
