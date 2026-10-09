package com.example.mealplanner

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DarkThemeContrastTest {
    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double {
            val channel = value.toDouble()
            return if (channel <= 0.04045) {
                channel / 12.92
            } else {
                ((channel + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun darkTextAndEmphasisPairsMeetWcagAaForNormalText() {
        val scheme = DarkAppColors
        val pairs = mapOf(
            "Main content" to (scheme.onSurface to scheme.surface),
            "Background content" to (scheme.onBackground to scheme.background),
            "Secondary labels" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "Primary buttons" to (scheme.onPrimary to scheme.primary),
            "Primary cards" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "Secondary buttons" to (scheme.onSecondary to scheme.secondary),
            "Secondary cards" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "Tertiary cards" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "Error buttons" to (scheme.onError to scheme.error)
        )
        pairs.forEach { (name, colors) ->
            assertTrue(
                "$name contrast was ${contrast(colors.first, colors.second)} (minimum 4.5)",
                contrast(colors.first, colors.second) >= 4.5
            )
        }
    }

    @Test
    fun darkControlsHaveVisibleBorders() {
        val scheme = DarkAppColors
        assertTrue(contrast(scheme.outline, scheme.surface) >= 3.0)
        assertTrue(contrast(scheme.outlineVariant, scheme.surface) >= 3.0)
    }
}
