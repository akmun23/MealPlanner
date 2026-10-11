package com.example.mealplanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilkaKeyInputRulesTest {
    @Test
    fun acceptsSupportedPersonalPrejKeyFormats() {
        assertTrue(BilkaKeyInputRules.isValid("prej_dev_" + "a".repeat(12)))
        assertTrue(BilkaKeyInputRules.isValid("prej_biz_" + "A2_-".repeat(3)))
        assertTrue(BilkaKeyInputRules.isValid("prej_pro_" + "X".repeat(256)))
    }

    @Test
    fun preventsSubmittingIncompleteOrMalformedKeys() {
        assertFalse(BilkaKeyInputRules.isValid(""))
        assertFalse(BilkaKeyInputRules.isValid("prej_dev_short"))
        assertFalse(BilkaKeyInputRules.isValid("prej_dev_" + "a".repeat(257)))
        assertFalse(BilkaKeyInputRules.isValid("prej_invalid_" + "a".repeat(20)))
        assertFalse(BilkaKeyInputRules.isValid("prej_dev_" + "a".repeat(12) + " "))
        assertFalse(BilkaKeyInputRules.isValid("prej_dev_" + "a".repeat(12) + "\n"))
    }
}
