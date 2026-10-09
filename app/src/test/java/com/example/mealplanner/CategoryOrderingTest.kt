package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryOrderingTest {
    @Test
    fun movingAnItemNormalizesOrderingWithoutLosingIds() {
        val original = listOf(
            IngredientCategory("a", "A", position = 5),
            IngredientCategory("b", "B", position = 10),
            IngredientCategory("c", "C", position = 30)
        )
        val moved = reorderCategory(
            items = original,
            index = 2,
            delta = -1,
            withPosition = { category, position ->
                category.copy(position = position)
            }
        )!!

        assertEquals(listOf("a", "c", "b"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.position })
        assertEquals(listOf(5, 10, 30), original.map { it.position })
    }

    @Test
    fun invalidMovesDoNotChangeCategories() {
        val items = listOf(RecipeCategoryOption("a", "A", 0))
        for (delta in listOf(-1, 0, 1, 5)) {
            assertNull(
                reorderCategory(items, 0, delta) { category, position ->
                    category.copy(position = position)
                }
            )
        }
    }
}
