package com.example.mealplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun localOnlyEditsPersistAcrossRestartsWithoutCloudMutations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val householdId = "local-household-1"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = OfflineSyncQueue(context, householdId)
        val originalPending = queue.hasPendingMutations()
        val name = "Local test " + UUID.randomUUID().toString()
        val first = MealPlannerState(context, householdId, scope)

        try {
            withTimeout(10_000L) {
                while (first.isLoading) delay(25)
            }
            assertTrue(first.weeklyPlanId?.startsWith("local-week-") == true)
            assertTrue(
                first.addIngredient(
                    name = name,
                    type = QuantityType.COUNT,
                    shoppingCategoryId = null,
                    isProtein = false
                )
            )
            val ingredient = first.ingredients.single { it.name == name }
            first.setPantry(ingredient.id, 3.0, MeasurementUnit.PIECE)
            first.setRecipeForDay(DayOfWeek.MONDAY, "offline-dinner")
        } finally {
            first.close()
        }

        val cached = RoomSnapshotCache(context, householdId).load()
        assertNotNull(cached)
        val id = cached!!.ingredients.single { it.name == name }.id
        assertEquals(3.0, cached.pantry[id] ?: 0.0, 0.0)
        assertEquals("offline-dinner", cached.plan[DayOfWeek.MONDAY])
        assertEquals(originalPending, queue.hasPendingMutations())

        val second = MealPlannerState(context, householdId, scope)
        try {
            withTimeout(10_000L) {
                while (second.isLoading) delay(25)
            }
            assertTrue(second.ingredients.any { it.name == name })
            assertFalse(second.isLoading)
        } finally {
            second.close()
            scope.cancel()
        }
    }
}
