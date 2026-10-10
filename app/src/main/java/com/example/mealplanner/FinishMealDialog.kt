package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Make consumption explicit: users review/adjust quantities before an eaten
 * meal and pantry changes are saved together. Ingredients with no stock are
 * not automatically deducted.
 */
@Composable
internal fun FinishMealDialog(
    date: LocalDate,
    recipe: Recipe,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val amounts = remember(recipe.id, date) {
        mutableStateMapOf<String, Pair<Boolean, String>>()
    }
    val requirements = recipe.ingredients
        .groupBy { it.ingredientId }
        .mapNotNull { (id, rows) ->
            val ingredient = state.ingredients.find { it.id == id } ?: return@mapNotNull null
            ingredient to rows.sumOf { it.amountBase }
        }

    // Pre-fill no more than the stock available; never subtract anything
    // unless the user explicitly confirms the entire dialog.
    requirements.forEach { (ingredient, needed) ->
        if (ingredient.id !in amounts) {
            val available = (state.pantry[ingredient.id] ?: 0.0).coerceAtLeast(0.0)
            val suggested = minOf(needed, available)
            amounts[ingredient.id] = (suggested > 0.0) to
                formatDisplayNumber(ingredient.preferredUnit.fromBase(suggested))
        }
    }

    val values = linkedMapOf<String, Double>()
    var valid = true
    for ((ingredient, _) in requirements) {
        val (checked, entered) = amounts[ingredient.id] ?: (false to "")
        if (!checked) continue
        val number = parseDanishDecimal(entered)
        val base = number?.let { ingredient.preferredUnit.toBase(it) }
        val available = state.pantry[ingredient.id] ?: 0.0
        if (base == null || !base.isFinite() || base <= 0.0 || base > available) {
            valid = false
        } else {
            values[ingredient.id] = base
        }
    }

    val dateText = date.format(
        DateTimeFormatter.ofPattern("EEEE d. MMMM", Locale.forLanguageTag("da-DK"))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Færdig med dagens ret?", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(dateText.replaceFirstChar { it.uppercase() })
                Text(recipe.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "Markér det, du faktisk har brugt fra lageret, og ret eventuelt " +
                        "mængderne. Kun markerede varer trækkes fra. " +
                        "Du kan også markere retten som spist uden lagerforbrug.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (requirements.isEmpty()) {
                    Text("Der er ingen registrerede ingredienser til denne opskrift.")
                }
                requirements.forEach { (ingredient, needed) ->
                    val (checked, entered) = amounts[ingredient.id] ?: (false to "")
                    val stock = state.pantry[ingredient.id] ?: 0.0
                    val amountBase = parseDanishDecimal(entered)
                        ?.let { ingredient.preferredUnit.toBase(it) }
                    val invalid = checked && (
                        amountBase == null || !amountBase.isFinite() ||
                            amountBase <= 0.0 || amountBase > stock
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { new ->
                                    amounts[ingredient.id] = new to entered
                                },
                                enabled = stock > 0.0
                            )
                            Column {
                                Text(ingredient.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "Opskrift: ${state.formatBaseAmount(ingredient, needed)} · " +
                                        "Lager: ${state.formatBaseAmount(ingredient, stock)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (checked) {
                            Row(
                                modifier = Modifier.padding(start = 48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = entered,
                                    onValueChange = {
                                        amounts[ingredient.id] = true to it
                                    },
                                    label = { Text("Brugt fra lager") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    isError = invalid
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(ingredient.preferredUnit.symbol)
                            }
                            if (invalid) {
                                Text(
                                    "Skriv en gyldig mængde, som ikke overstiger lageret.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuller") }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    if (state.completePlannedDinner(date, recipe.id, values)) {
                        onDismiss()
                    }
                }
            ) {
                Text("Markér som spist og gem")
            }
        }
    )
}
