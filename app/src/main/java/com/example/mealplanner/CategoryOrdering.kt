package com.example.mealplanner

/**
 * Reorder a visible list by one row and normalize persisted display positions.
 * Returns null for a no-op so callers avoid unnecessary sync writes.
 */
internal fun <T> reorderCategory(
    items: List<T>,
    index: Int,
    delta: Int,
    withPosition: (T, Int) -> T
): List<T>? {
    if (delta != -1 && delta != 1) return null
    val destination = index + delta
    if (index !in items.indices || destination !in items.indices) return null

    val reordered = items.toMutableList()
    val item = reordered.removeAt(index)
    reordered.add(destination, item)
    return reordered.mapIndexed { position, value ->
        withPosition(value, position)
    }
}
