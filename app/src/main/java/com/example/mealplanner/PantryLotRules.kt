package com.example.mealplanner

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic consumption of physical packages. No arithmetic ever adds an
 * old pantry_items total to this list: old totals are represented by a single
 * marked legacy lot.
 */
internal object PantryLotRules {
    fun valid(lot: PantryLot): Boolean =
        lot.originalBase.isFinite() && lot.originalBase > 0.0 &&
            lot.originalBase <= 1e10 &&
            lot.remainingBase.isFinite() && lot.remainingBase >= 0.0 &&
            lot.remainingBase <= lot.originalBase && lot.version >= 1L

    /** Finished containers remain in sync history, but never clutter the UI. */
    fun active(lots: List<PantryLot>, ingredientId: String): List<PantryLot> =
        lots.filter { it.ingredientId == ingredientId && it.remainingBase > 0.0 }

    fun total(lots: List<PantryLot>, ingredientId: String): Double =
        lots.filter { it.ingredientId == ingredientId }.sumOf { it.remainingBase }

    fun consume(
        lots: List<PantryLot>, ingredientId: String, neededBase: Double
    ): List<PantryLot>? {
        if (!neededBase.isFinite() || neededBase <= 0.0 ||
            neededBase > 1e10 || lots.any { !valid(it) }
        ) return null

        val available = total(lots, ingredientId)
        if (!available.isFinite() || available + 0.000001 < neededBase) return null
        var remaining = neededBase
        val result = lots.toMutableList()
        val ordered = lots.withIndex()
            .filter { it.value.ingredientId == ingredientId && it.value.remainingBase > 0.0 }
            .sortedWith(compareBy<IndexedValue<PantryLot>>(
                { !it.value.opened },
                { it.value.bestBefore == null },
                { it.value.bestBefore ?: "9999-12-31" },
                { it.value.createdAt },
                { it.value.id }
            ))
        for ((index, lot) in ordered) {
            val deduct = min(remaining, lot.remainingBase)
            result[index] = lot.copy(
                remainingBase = max(0.0, lot.remainingBase - deduct),
                opened = true,
                version = lot.version + 1
            )
            remaining -= deduct
            if (remaining <= 0.000000001) break
        }
        return if (remaining <= 0.000000001) result else null
    }
}
