package com.example.mealplanner

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSyncDrainTest {

    @Test
    fun schedulerAppendsOrReplacesInsteadOfIgnoringNewWork() {
        assertSame(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OFFLINE_SYNC_WORK_POLICY
        )
    }

    @Test
    fun transientFailuresStayRetryablePastPreviousCutoff() = runBlocking {
        val store = FakePendingMutationStore(
            pending = pendingPantry(
                revision = "r1",
                amount = 1.0
            )
        )
        val drain = OfflineSyncDrain(
            store = store,
            applyMutation = {
                error("temporary network failure")
            }
        )

        repeat(8) {
            assertEquals(
                OfflineSyncDrainResult.RETRY,
                drain.drain("household")
            )
            assertTrue(store.pending != null)
        }

        assertEquals(8, store.failureCount)
    }

    @Test
    fun olderInFlightRevisionCannotDeleteNewerEdit() = runBlocking {
        val first = pendingPantry(
            revision = "r1",
            amount = 1.0
        )
        val second = pendingPantry(
            revision = "r2",
            amount = 2.0
        )
        val store = FakePendingMutationStore(first)
        val applied = mutableListOf<Double>()

        val drain = OfflineSyncDrain(
            store = store,
            applyMutation = { mutation ->
                val pantry = mutation as QueuedMutation.SetPantry
                applied += pantry.amountBase

                if (pantry.amountBase == 1.0) {
                    store.pending = second
                }
            }
        )

        assertEquals(
            OfflineSyncDrainResult.EMPTY,
            drain.drain("household")
        )
        assertEquals(listOf(1.0, 2.0), applied)
        assertNull(store.pending)
    }

    private fun pendingPantry(
        revision: String,
        amount: Double
    ): PendingMutationEntity {
        val mutation = QueuedMutation.SetPantry(
            ingredientId = "ingredient",
            amountBase = amount
        )

        return PendingMutationEntity(
            id = "household|pantry:ingredient",
            revision = revision,
            householdId = "household",
            mutationKey = mutation.key(),
            priority = mutation.priority(),
            domainMask = mutation.domainMask(),
            payload = QueuedMutationCodec.encode(mutation),
            updatedAtEpochMs = 1L
        )
    }

    private class FakePendingMutationStore(
        var pending: PendingMutationEntity?
    ) : PendingMutationStore {
        var failureCount = 0

        override suspend fun next(
            householdId: String
        ): PendingMutationEntity? =
            pending?.takeIf { it.householdId == householdId }

        override suspend fun deleteIfCurrent(
            id: String,
            revision: String
        ): Int {
            val current = pending
            if (
                current?.id == id &&
                current.revision == revision
            ) {
                pending = null
                return 1
            }
            return 0
        }

        override suspend fun markFailure(
            id: String,
            revision: String,
            message: String?
        ): Int {
            val current = pending
            if (
                current?.id == id &&
                current.revision == revision
            ) {
                failureCount += 1
                pending = current.copy(
                    attemptCount = current.attemptCount + 1,
                    lastError = message
                )
                return 1
            }
            return 0
        }
    }
}
