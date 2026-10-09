package com.example.mealplanner

import android.content.Context
import androidx.core.content.edit

/** Controls the appearance independently of Supabase and can work offline. */
enum class ThemeMode(val danishLabel: String) {
    SYSTEM("System"),
    LIGHT("Lyst"),
    DARK("Mørkt")
}

internal class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "mealplanner_appearance",
        Context.MODE_PRIVATE
    )

    fun load(): ThemeMode = preferences.getString("theme_mode", null)
        ?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        } ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) {
        preferences.edit { putString("theme_mode", mode.name) }
    }
}
