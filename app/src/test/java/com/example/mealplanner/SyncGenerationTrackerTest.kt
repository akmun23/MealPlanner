package com.example.mealplanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGenerationTrackerTest {

    @Test
    fun newerLocalChangeInvalidatesOlderCloudResponse() {
        val tracker = SyncGenerationTracker()

        val first = tracker.markLocalChange(
            setOf(CloudSyncDomain.PANTRY)
        )
        assertTrue(tracker.isCurrent(first))

        tracker.markLocalChange(
            setOf(CloudSyncDomain.PANTRY)
        )

        assertFalse(tracker.isCurrent(first))
    }

    @Test
    fun unrelatedDomainDoesNotInvalidateResponse() {
        val tracker = SyncGenerationTracker()

        val pantry = tracker.markLocalChange(
            setOf(CloudSyncDomain.PANTRY)
        )
        tracker.markLocalChange(
            setOf(CloudSyncDomain.SHOPPING)
        )

        assertTrue(tracker.isCurrent(pantry))
    }

}
