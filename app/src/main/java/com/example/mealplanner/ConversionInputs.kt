package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope

/**
 * The one place a household types "what is this in the ingredient's unit",
 * used by the ingredient editor and by "Bekræft pakkestørrelse". Counted
 * ingredients read "1 stk = [280] g"; the others "1 pakke = [200] g".
 */
@Composable
internal fun ConversionAmountRow(
    type: QuantityType,
    options: List<QuantityUnit>,
    unit: QuantityUnit,
    unitLocked: Boolean,
    onUnitChange: (QuantityUnit) -> Unit,
    amountText: String,
    onAmountChange: (String) -> Unit
) {
    val inverted = ConversionPhrasing.inverted(type, unit)
    val base = type.baseMeasurementUnit()

    @Composable
    fun RowScope.AmountField(label: String) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { text ->
                onAmountChange(text.filter { it.isDigit() || it == ',' || it == '.' })
            },
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
    }

    @Composable
    fun UnitPicker(oneLabel: Boolean) {
        val label = if (oneLabel) unit.labelFor(1.0) else unit.symbol
        if (unitLocked) {
            Text(label, fontWeight = FontWeight.SemiBold)
        } else {
            SimpleSelector(
                label = label,
                options = options,
                optionText = { it.labelFor(1.0) },
                minHeight = 56.dp,
                onSelected = onUnitChange
            )
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
    ) {
        if (inverted) {
            Text("1 ${base.labelFor(1.0)} =", fontWeight = FontWeight.SemiBold)
            AmountField("Antal")
            UnitPicker(oneLabel = false)
        } else {
            Text("1", fontWeight = FontWeight.SemiBold)
            UnitPicker(oneLabel = true)
            Text("=", fontWeight = FontWeight.SemiBold)
            AmountField("Indhold (${base.symbol})")
        }
    }
}
