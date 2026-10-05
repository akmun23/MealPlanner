package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelHelpersTest {

    @Test
    fun danishDecimalAcceptsCommaAndPeriod() {
        assertEquals(1.5, parseDanishDecimal("1,5") ?: 0.0, 0.0001)
        assertEquals(1.5, parseDanishDecimal("1.5") ?: 0.0, 0.0001)
        assertNull(parseDanishDecimal("1,2,3"))
    }

    @Test
    fun displayNamesStartWithCapitalLetter() {
        assertEquals("Kyllingebryst", "kyllingebryst".normalizedDisplayName())
        assertEquals("Æbler", "  æbler  ".normalizedDisplayName())
        assertEquals("", "   ".normalizedDisplayName())
    }
}
