package com.example.mealplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlannerSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun userWeightsSurviveRestartAndAreHouseholdScoped() {
        val home = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val prefs = PlannerSettingsStore(context)
        val custom = PlannerWeights(
            pantryCoverage = 77.0,
            recency7Days = 120.0,
            jitter = 0.0
        )
        prefs.save(home, custom)

        assertEquals(custom, PlannerSettingsStore(context).load(home))
        assertEquals(PlannerWeights(), prefs.load(other))
        prefs.reset(home)
        assertEquals(PlannerWeights(), prefs.load(home))
    }

    @Test
    fun malformedWeightsFallBackToDefaults() {
        val home = UUID.randomUUID().toString()
        context.getSharedPreferences(
            "mealplanner_planner_preferences",
            Context.MODE_PRIVATE
        ).edit().putString("planner-$home", "{invalid").commit()
        assertEquals(PlannerWeights(), PlannerSettingsStore(context).load(home))
    }
}
