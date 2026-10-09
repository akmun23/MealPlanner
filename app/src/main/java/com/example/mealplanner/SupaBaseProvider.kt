package com.example.mealplanner

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object SupabaseProvider {

    val client: SupabaseClient by lazy {
        val url = BuildConfig.SUPABASE_URL.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: "https://placeholder.supabase.co"

        val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY.ifBlank {
            "placeholder_key"
        }

        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth) {
                scheme = "com.mealplan.mealplanner"
                host = "login-callback"
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}