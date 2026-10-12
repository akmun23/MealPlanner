package com.example.mealplanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    // Derived state only tracks observable Compose reads inside its lambda.
    // The selected enum values above are plain Kotlin values, so key the
    // calculation to the persisted selector keys to avoid stale sorting.
    val filtered by remember(sortOrderKey, stockFilterKey) {
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
                    onSelected = { selectedCategoryId = it?.id },
                    fillWidth = true
                )
            }
        }

        item(key = "pantry-view-controls") {
            // Separate rows leave enough width for long Danish sort labels
            // even with a larger font setting on a small phone.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SimpleSelector(
                    label = "Status: ${stockFilter.label}",
                    options = PantryStockFilter.entries.toList(),
                    optionText = { it.label },
                    onSelected = { stockFilterKey = it.name },
                    fillWidth = true
                )
                SimpleSelector(
                    label = "Sorter: ${sortOrder.label}",
                    options = PantrySortOrder.entries.toList(),
                    optionText = { it.label },
                    onSelected = { sortOrderKey = it.name },
                    fillWidth = true
                )
            }
            Text(
                "Viser ${filtered.size} af ${state.ingredients.size} ingredienser",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (filtered.isEmpty()) {
            item {
                val isFiltered = searchQuery.isNotBlank() || selectedCategoryId != null ||
                        stockFilter != PantryStockFilter.ALL ||
                        sortOrder != PantrySortOrder.NAME_ASC

                EmptyCard(
                    title = if (state.ingredients.isEmpty()) "Ingen ingredienser endnu" else "Ingen ingredienser fundet",
                    text = if (state.ingredients.isEmpty()) "Tilføj en ny ingrediens for at holde styr på dit lager." else "Ingen ingredienser matcher dine nuværende filtre.",
                    icon = Icons.Filled.Inventory2,
                    action = if (isFiltered) {
                        {
                            Button(
                                onClick = {
                                    searchQuery = ""
                                    selectedCategoryId = null
                                    stockFilterKey = PantryStockFilter.ALL.name
                                    sortOrderKey = PantrySortOrder.NAME_ASC.name
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Ryd filtre")
                            }
                        }
                    } else null
                )
            }
        }

        items(filtered, key = { it.id }, contentType = { "pantry-row" }) { ingredient ->
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
    val lots = state.pantryLots.filter { it.ingredientId == ingredient.id }
    val hasIdentifiedPackages = lots.any { !it.legacyUnknown }
    val physicalUnit = MeasurementUnit.defaultFor(ingredient.quantityType)
    var newPackSizeText by remember(ingredient.id) { mutableStateOf("") }
    var newPackageCountText by remember(ingredient.id) { mutableStateOf("1") }
    var newPackOpened by remember(ingredient.id) { mutableStateOf(false) }

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
                modifier = Modifier.heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
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

                if (amountText.isBlank() ||
                    parseDanishDecimal(amountText)?.let { !it.isFinite() || it < 0.0 } != false
                ) {
                    Text(
                        if (currentBase > 0.0) {
                            "Angiv en gyldig mængde, eller brug Tøm lager."
                        } else {
                            "Angiv en gyldig mængde."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (currentBase > 0.0 && !hasIdentifiedPackages) {
                    TextButton(onClick = {
                        state.setPantry(ingredient.id, 0.0, unit)
                        onDismiss()
                    }) {
                        Text("Tøm lager")
                    }
                }

                SimpleSelector(
                    label = unit.selectorLabel(),
                    options = MeasurementUnit.compatibleWith(
                        ingredient.quantityType
                    ),
                    optionText = { it.selectorLabel() },
                    onSelected = { selected ->
                        val entered = parseDanishDecimal(amountText)
                        if (entered != null && entered.isFinite()) {
                            amountText = formatDisplayNumber(
                                unit.convertAmountTo(entered, selected) ?: entered
                            )
                        }
                        unit = selected
                    }
                )
                if (lots.isNotEmpty()) {
                    Text("Pakker på lager", style = MaterialTheme.typography.titleMedium)
                    lots.sortedWith(compareBy<PantryLot>(
                        { !it.opened }, { it.bestBefore ?: "9999-12-31" }, { it.id }
                    )).forEach { lot ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (lot.legacyUnknown) "Gammel beholdning · ukendt emballage"
                                else if (lot.opened) "Åbnet pakke"
                                else "Uåbnet pakke",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${formatDisplayNumber(lot.remainingBase)} af " +
                                    "${formatDisplayNumber(lot.originalBase)} " +
                                    physicalUnit.symbol + " tilbage",
                                style = MaterialTheme.typography.bodySmall
                            )
                            var remainderText by remember(lot.id) {
                                mutableStateOf(formatDisplayNumber(lot.remainingBase))
                            }
                            OutlinedTextField(
                                value = remainderText,
                                onValueChange = { value ->
                                    remainderText = value.filter { it.isDigit() || it == ',' || it == '.' }
                                },
                                label = { Text("Rest i ${physicalUnit.symbol}") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Åbnet", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = lot.opened,
                                    onCheckedChange = { state.updatePantryLot(
                                        lot.id, lot.remainingBase, it
                                    ) }
                                )
                            }
                            val remainder = parseDanishDecimal(remainderText)
                            TextButton(
                                onClick = {
                                    if (remainder != null) {
                                        state.updatePantryLot(lot.id, remainder, lot.opened)
                                    }
                                },
                                enabled = remainder != null && remainder.isFinite() &&
                                    remainder >= 0.0 && remainder <= lot.originalBase
                            ) { Text("Gem restmængde") }
                        }
                    }
                }

                if (ingredient.quantityType != QuantityType.PACKAGE) {
                    Text("Tilføj fysiske pakker", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = newPackSizeText,
                        onValueChange = { value ->
                            newPackSizeText = value.filter { it.isDigit() || it == ',' || it == '.' }
                        },
                        label = { Text("Mængde pr. pakke (${physicalUnit.symbol})") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newPackageCountText,
                        onValueChange = { value ->
                            newPackageCountText = value.filter(Char::isDigit).take(3)
                        },
                        label = { Text("Antal pakker") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Allerede åbnet", modifier = Modifier.weight(1f))
                        Switch(checked = newPackOpened, onCheckedChange = {
                            newPackOpened = it
                        })
                    }
                    val packageBase = parseDanishDecimal(newPackSizeText)
                    val count = newPackageCountText.toIntOrNull()
                    Button(
                        onClick = {
                            if (packageBase != null && count != null &&
                                state.addPantryPackages(
                                    ingredient.id, count, packageBase, physicalUnit, newPackOpened
                                )
                            ) {
                                newPackSizeText = ""
                                newPackageCountText = "1"
                            }
                        },
                        enabled = packageBase != null && packageBase.isFinite() &&
                            packageBase > 0.0 && count != null && count in 1..100
                    ) { Text("Tilføj pakker") }
                }
            }
        },
        confirmButton = {
            val amount = parseDanishDecimal(amountText)
                ?.takeIf { it.isFinite() && it >= 0.0 }
            Button(
                onClick = {
                    val validAmount = amount ?: return@Button
                    state.setPantry(
                        ingredientId = ingredient.id,
                        amount = validAmount,
                        unit = unit
                    )
                    onDismiss()
                },
                enabled = amount != null && !hasIdentifiedPackages
            ) {
                Text("Gem samlet beholdning")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuller")
            }
        }
    )
}
