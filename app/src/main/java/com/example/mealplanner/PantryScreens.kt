package com.example.mealplanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PantryScreen(
    state: MealPlannerState,
    listState: LazyListState,
    onAddIngredient: () -> Unit,
    onEditIngredient: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var stockFilterKey by rememberSaveable {
        mutableStateOf(PantryStockFilter.ALL.name)
    }
    var sortOrderKey by rememberSaveable {
        mutableStateOf(PantrySortOrder.NAME_ASC.name)
    }
    val stockFilter = PantryStockFilter.entries
        .firstOrNull { it.name == stockFilterKey }
        ?: PantryStockFilter.ALL
    val sortOrder = PantrySortOrder.entries
        .firstOrNull { it.name == sortOrderKey }
        ?: PantrySortOrder.NAME_ASC
    var editing by remember { mutableStateOf<Ingredient?>(null) }

    editing?.let { ingredient ->
        PantryAmountDialog(
            ingredient = ingredient,
            state = state,
            onDismiss = { editing = null }
        )
    }

    val filtered by remember {
        derivedStateOf {
            visiblePantryIngredients(
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList(),
                pantry = state.pantry,
                searchQuery = searchQuery,
                selectedCategoryId = selectedCategoryId,
                stockFilter = stockFilter,
                sortOrder = sortOrder
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Lager",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Button(
                onClick = onAddIngredient,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Ny ingrediens")
            }
        }

        item {
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Søg navn eller kategori"
            )
        }

        if (state.ingredientCategories.isNotEmpty()) {
            item {
                val selectedCategory = state.ingredientCategories
                    .find { it.id == selectedCategoryId }

                SimpleSelector(
                    label = selectedCategory?.name ?: "Alle kategorier",
                    options = listOf<IngredientCategory?>(null) +
                        state.ingredientCategories.sortedBy { it.name.lowercase() },
                    optionText = { it?.name ?: "Alle kategorier" },
                    onSelected = { selectedCategoryId = it?.id }
                )
            }
        }

        item(key = "pantry-view-controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SimpleSelector(
                        label = stockFilter.label,
                        options = PantryStockFilter.entries.toList(),
                        optionText = { it.label },
                        onSelected = { stockFilterKey = it.name }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SimpleSelector(
                        label = sortOrder.label,
                        options = PantrySortOrder.entries.toList(),
                        optionText = { it.label },
                        onSelected = { sortOrderKey = it.name }
                    )
                }
            }
            Text(
                "Viser ${filtered.size} af ${state.ingredients.size} ingredienser",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(filtered, key = { it.id }) { ingredient ->
            val amount = state.pantry[ingredient.id] ?: 0.0

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = ingredient },
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppThumbnail(
                        imageUri = ingredient.imageUri,
                        fallback = ingredient.name.take(1).uppercase()
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ingredient.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (amount > 0.0) {
                                state.formatBaseAmount(
                                    ingredient,
                                    amount
                                )
                            } else {
                                "Ikke på lager"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (amount > 0.0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    IconButton(
                        onClick = { onEditIngredient(ingredient.id) }
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rediger ingrediens"
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PantryAmountDialog(
    ingredient: Ingredient,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val initialUnit = ingredient.preferredUnit
    val currentBase = state.pantry[ingredient.id] ?: 0.0

    var unit by remember(ingredient.id) {
        mutableStateOf(initialUnit)
    }

    var amountText by remember(ingredient.id) {
        mutableStateOf(
            if (currentBase > 0.0) {
                formatDisplayNumber(initialUnit.fromBase(currentBase))
            } else {
                ""
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ingredient.name) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter { char ->
                            char.isDigit() || char == ',' || char == '.'
                        }
                    },
                    label = { Text("Mængde") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                SimpleSelector(
                    label = unit.selectorLabel(),
                    options = MeasurementUnit.compatibleWith(
                        ingredient.quantityType
                    ),
                    optionText = { it.selectorLabel() },
                    onSelected = { unit = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = parseDanishDecimal(amountText) ?: 0.0

                    state.setPantry(
                        ingredientId = ingredient.id,
                        amount = amount,
                        unit = unit
                    )
                    onDismiss()
                }
            ) {
                Text("Gem")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuller")
            }
        }
    )
}
