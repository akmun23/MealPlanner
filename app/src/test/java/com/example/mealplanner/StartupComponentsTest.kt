package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StartupComponentsTest {

    @Test
    fun navigationIconsInitializeWithoutRuntimeLinkageErrors() {
        assertEquals(4, AppTab.entries.size)
        AppTab.entries.forEach { tab ->
            assertNotNull(tab.icon)
        }
    }

    @Test
    fun supabaseClientCanBeConstructed() {
        assertNotNull(SupabaseProvider.client)
    }
}
