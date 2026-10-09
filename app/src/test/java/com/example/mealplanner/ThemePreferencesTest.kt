package com.example.mealplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemePreferencesTest {
    @Test
    fun themePersistsAcrossStoreInstancesAndDefaultsToSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ThemePreferences(context)
        store.save(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, store.load())
        store.save(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePreferences(context).load())
        store.save(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemePreferences(context).load())
        store.save(ThemeMode.SYSTEM)
    }

    @Test
    fun unknownPreferenceFallsBackToSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mealplanner_appearance", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", "missing-value").commit()
        assertEquals(ThemeMode.SYSTEM, ThemePreferences(context).load())
    }
}
