package com.example.mealplanner

/** Optional starting points; all existing advanced weights remain editable. */
internal enum class PlannerPreset(val title: String, val description: String) {
    STANDARD("Balanceret", "Appens standardprioriteter"),
    VARIETY("Mere variation", "Undgå gentagelser på tværs af dage og uger"),
    PANTRY("Brug lageret", "Foretræk råvarer, der allerede er hjemme");

    fun weights(): PlannerWeights = when (this) {
        STANDARD -> PlannerWeights()
        VARIETY -> PlannerWeights().copy(
            duplicateRecipe = 220.0,
            repeatedProtein = 22.0,
            adjacentProtein = 25.0,
            repeatedCategory = 30.0,
            adjacentCategory = 35.0,
            recency7Days = 165.0,
            recency14Days = 95.0,
            recency30Days = 55.0,
            previousPlanReuse = 65.0
        )

        PANTRY -> PlannerWeights().copy(
            pantryCoverage = 90.0,
            ingredientReuse = 70.0,
            repeatedCategory = 7.0,
            jitter = 4.0
        )
    }
}
