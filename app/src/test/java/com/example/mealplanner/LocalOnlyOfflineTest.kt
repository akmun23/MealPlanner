package com.example.mealplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalOnlyOfflineTest {
    @Test
    fun localHouseholdSnapshotPersistsInRoomWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val householdId = "local-fixture-${UUID.randomUUID()}"
        val ingredient = Ingredient(
            id = "rice",
            name = "Ris",
            quantityType = QuantityType.MASS,
            preferredUnit = MeasurementUnit.GRAM
        )
        val snapshot = CloudSnapshot.EMPTY.copy(
            weeklyPlanId = "local-week",
            ingredients = listOf(ingredient),
            pantry = mapOf(ingredient.id to 400.0),
            plan = orderedWeekDays.associateWith { day ->
                if (day == DayOfWeek.MONDAY) "test-meal" else null
            }
        )
        val cache = RoomSnapshotCache(context, householdId)

        cache.save(snapshot)
        val restored = cache.load()

        assertNotNull(restored)
        assertEquals(snapshot.ingredients, restored!!.ingredients)
        assertEquals(snapshot.pantry, restored.pantry)
        assertEquals("test-meal", restored.plan[DayOfWeek.MONDAY])
        assertTrue(OfflineSyncQueue(context, householdId).hasPendingMutations().not())
    }
}
