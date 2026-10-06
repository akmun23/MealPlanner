package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StateSyncTest {

    private data class Row(
        val id: String,
        val value: Int
    )

    @Test
    fun listSyncUpdatesWithoutRebuildingUnchangedRows() {
        val first = Row("a", 1)
        val unchanged = Row("b", 2)
        val target = mutableListOf(first, unchanged)

        syncListByKey(
            target = target,
            incoming = listOf(
                Row("a", 3),
                unchanged,
                Row("c", 4)
            ),
            key = Row::id
        )

        assertEquals(
            listOf(
                Row("a", 3),
                unchanged,
                Row("c", 4)
            ),
            target
        )
        assertSame(unchanged, target[1])
    }

    @Test
    fun listSyncHandlesRemovalAndReordering() {
        val target = mutableListOf(
            Row("a", 1),
            Row("b", 2),
            Row("c", 3)
        )

        syncListByKey(
            target = target,
            incoming = listOf(
                Row("c", 3),
                Row("a", 1)
            ),
            key = Row::id
        )

        assertEquals(
            listOf(
                Row("c", 3),
                Row("a", 1)
            ),
            target
        )
    }

    @Test
    fun mapSyncOnlyKeepsIncomingKeysAndValues() {
        val target = mutableMapOf(
            "a" to 1,
            "b" to 2
        )

        syncMap(
            target = target,
            incoming = mapOf(
                "b" to 3,
                "c" to 4
            )
        )

        assertEquals(
            mapOf(
                "b" to 3,
                "c" to 4
            ),
            target
        )
    }
}
