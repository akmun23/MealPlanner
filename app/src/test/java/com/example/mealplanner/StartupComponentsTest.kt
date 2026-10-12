package com.example.mealplanner

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    fun allSubpageBackArrowIconCanBeLinked() {
        // SubpageScaffold is the shared entry point for Settings, recipe and
        // ingredient editors. Its arrow icon is not used by main tab navigation.
        assertNotNull(Icons.AutoMirrored.Filled.ArrowBack)
    }
}
