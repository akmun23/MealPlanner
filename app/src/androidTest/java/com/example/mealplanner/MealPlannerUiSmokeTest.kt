package com.example.mealplanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealPlannerUiSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchCanBeEnteredAndCleared() {
        var query by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Find opskrift"
                )
            }
        }
        composeRule.onNodeWithText("Find opskrift").performTextInput("rug")
        composeRule.onNodeWithText("rug").assertExists()
        composeRule.onNodeWithContentDescription("Ryd søgning").performClick()
        composeRule.onNodeWithText("rug").assertDoesNotExist()
    }

    @Test
    fun measurementChipsAllowSwitchingUnits() {
        var selected by mutableStateOf(MeasurementUnit.GRAM)
        composeRule.setContent {
            MaterialTheme {
                SelectorChips(
                    items = MeasurementUnit.compatibleWith(QuantityType.MASS),
                    selected = selected,
                    label = { it.selectorLabel() },
                    onSelected = { selected = it }
                )
            }
        }
        composeRule.onNodeWithText("kg").performClick()
        composeRule.onNodeWithText("kg").assertIsSelected()
    }

    @Test
    fun commonSubpageScaffoldRendersTitleBodyAndWorkingBackNavigation() {
        var backClicks = 0
        composeRule.setContent {
            MaterialTheme {
                SubpageScaffold(
                    title = "Undermenutest",
                    onBack = { backClicks++ }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        Text("Undermenuens indhold")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Undermenutest").assertExists()
        composeRule.onNodeWithText("Undermenuens indhold").assertExists()
        composeRule.onNodeWithContentDescription("Tilbage").performClick()
        composeRule.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun clickingSettingsButtonOpensSettingsScreen() {
        composeRule.setContent {
            MaterialTheme {
                MealPlannerApp(
                    household = HouseholdDto("test-id", "Test Husstand", "ABC123"),
                    onRenameHousehold = { true },
                    onLogout = {},
                    themeMode = ThemeMode.SYSTEM,
                    onThemeModeChange = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription("Indstillinger").performClick()
        composeRule.onNodeWithText("Indstillinger").assertExists()
    }
}
