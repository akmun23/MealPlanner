package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementUnitConversionTest {
    @Test
    fun massConversionPreservesTheUnderlyingQuantity() {
        assertEquals(
            1.0,
            MeasurementUnit.GRAM.convertAmountTo(1000.0, MeasurementUnit.KILOGRAM)!!,
            0.000001
        )
        assertEquals(
            1250.0,
            MeasurementUnit.KILOGRAM.convertAmountTo(1.25, MeasurementUnit.GRAM)!!,
            0.000001
        )
    }

    @Test
    fun volumeConversionPreservesTheUnderlyingQuantity() {
        assertEquals(
            1250.0,
            MeasurementUnit.LITRE.convertAmountTo(1.25, MeasurementUnit.MILLILITRE)!!,
            0.000001
        )
        assertEquals(
            2.0,
            MeasurementUnit.TEASPOON.convertAmountTo(6.0, MeasurementUnit.TABLESPOON)!!,
            0.000001
        )
    }

    @Test
    fun conversionRejectsIncompatibleAndInvalidUnits() {
        assertNull(MeasurementUnit.PIECE.convertAmountTo(3.0, MeasurementUnit.KILOGRAM))
        assertNull(MeasurementUnit.GRAM.convertAmountTo(Double.NaN, MeasurementUnit.KILOGRAM))
        assertNull(
            MeasurementUnit.GRAM.convertAmountTo(
                Double.POSITIVE_INFINITY,
                MeasurementUnit.KILOGRAM
            )
        )
        assertEquals(
            0.0,
            MeasurementUnit.KILOGRAM.convertAmountTo(0.0, MeasurementUnit.GRAM)!!,
            0.0
        )
    }

    @Test
    fun timeFilterLabelsReflectInclusiveUpperBounds() {
        assertEquals("⚡ Højst 30 min", prepLimitOptionLabel(30))
        assertEquals("⏳ Højst 60 min", prepLimitOptionLabel(60))
    }
}
