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
        var rebasedId: String? = null

        val drain = OfflineSyncDrain(
            store = store,
            onSuperseded = { id ->
                rebasedId = id
                store.rebaseLatest(
                    id = id,
                    baseSnapshotPayload = "remote-after-first"
                )
            },
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
        assertEquals(first.id, rebasedId)
        assertNull(store.pending)
    }

    @Test
    fun detectedSharedConflictIsPausedWithoutApplyingLocalWrite() =
        runBlocking {
            val store = FakePendingMutationStore(
                pending = pendingPantry(
                    revision = "r1",
                    amount = 3.0
                )
            )
            var applied = false

            val drain = OfflineSyncDrain(
                store = store,
                hasConflict = { _, _ -> true },
                applyMutation = {
                    applied = true
                }
            )

            assertEquals(
                OfflineSyncDrainResult.EMPTY,
                drain.drain("household")
            )
            assertTrue(store.pending?.conflict == true)
            assertEquals(false, applied)
        }

    @Test
    fun entireBatchIsPreflightedBeforeAnyMutationIsApplied() =
        runBlocking {
            val first = pendingPantry(
                revision = "pantry",
                amount = 2.0
            ).copy(batchId = "batch")
            val clear = QueuedMutation.ClearShopping("week")
            val second = PendingMutationEntity(
                id = "household|${clear.key()}",
                revision = "clear",
                batchId = "batch",
                householdId = "household",
                mutationKey = clear.key(),
                priority = clear.priority(),
                domainMask = clear.domainMask(),
                payload = QueuedMutationCodec.encode(clear),
                updatedAtEpochMs = 2L
            )
            val store = FakeBatchPendingMutationStore(
                mutableListOf(first, second)
            )
            val applied = mutableListOf<QueuedMutation>()

            val drain = OfflineSyncDrain(
                store = store,
                hasConflict = { pending, _ ->
                    pending.id == second.id
                },
                applyMutation = applied::add
            )

            assertEquals(
                OfflineSyncDrainResult.EMPTY,
                drain.drain("household")
            )
            assertTrue(applied.isEmpty())
            assertTrue(
                store.rows.all { it.conflict }
            )
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

    private class FakeBatchPendingMutationStore(
        val rows: MutableList<PendingMutationEntity>
    ) : PendingMutationStore {

        override suspend fun next(
            householdId: String
        ): PendingMutationEntity? =
            rows
                .filter {
                    it.householdId == householdId &&
                        !it.conflict
                }
                .sortedWith(
                    compareBy<PendingMutationEntity> {
                        it.priority
                    }.thenBy {
                        it.updatedAtEpochMs
                    }.thenBy {
                        it.id
                    }
                )
                .firstOrNull()

        override suspend fun batch(
            batchId: String
        ): List<PendingMutationEntity> =
            rows
                .filter {
                    it.batchId == batchId &&
                        !it.conflict
                }
                .sortedWith(
                    compareBy<PendingMutationEntity> {
                        it.priority
                    }.thenBy {
                        it.updatedAtEpochMs
                    }.thenBy {
                        it.id
                    }
                )

        override suspend fun deleteIfCurrent(
            id: String,
            revision: String
        ): Int {
            val index = rows.indexOfFirst {
                it.id == id &&
                    it.revision == revision
            }
            if (index < 0) return 0
            rows.removeAt(index)
            return 1
        }

        override suspend fun markFailure(
            id: String,
            revision: String,
            message: String?
        ): Int = 1

        override suspend fun markConflict(
            batchId: String,
            message: String?
        ): Int {
            var changed = 0
            rows.indices.forEach { index ->
                if (rows[index].batchId == batchId) {
                    rows[index] = rows[index].copy(
                        conflict = true,
                        lastError = message
                    )
                    changed += 1
                }
            }
            return changed
        }

        override suspend fun rebaseLatest(
            id: String,
            baseSnapshotPayload: String
        ): Int = 0
    }

    private class FakePendingMutationStore(
        var pending: PendingMutationEntity?
    ) : PendingMutationStore {
        var failureCount = 0

        override suspend fun next(
            householdId: String
        ): PendingMutationEntity? =
            pending?.takeIf {
                it.householdId == householdId &&
                    !it.conflict
            }

        override suspend fun batch(
            batchId: String
        ): List<PendingMutationEntity> =
            pending
                ?.takeIf {
                    it.batchId == batchId &&
                        !it.conflict
                }
                ?.let(::listOf)
                .orEmpty()

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

        override suspend fun markConflict(
            batchId: String,
            message: String?
        ): Int {
            val current = pending
            if (current?.batchId == batchId) {
                pending = current.copy(
                    conflict = true,
                    lastError = message
                )
                return 1
            }
            return 0
        }

        override suspend fun rebaseLatest(
            id: String,
            baseSnapshotPayload: String
        ): Int {
            val current = pending
            if (current?.id == id) {
                pending = current.copy(
                    baseSnapshotPayload = baseSnapshotPayload,
                    conflict = false,
                    attemptCount = 0,
                    lastError = null
                )
                return 1
            }
            return 0
        }
    }
}
