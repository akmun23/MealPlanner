package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class ShoppingSections(
    val all: List<ShoppingItem>,
    val included: List<ShoppingItem>,
    val missing: List<ShoppingItem>,
    val bought: List<ShoppingItem>,
    val excluded: List<ShoppingItem>,
    val missingByCategoryId: Map<String?, List<ShoppingItem>>,
    val checkedCount: Int
)

@Composable
fun ShoppingScreen(state: MealPlannerState) {
    val scope = rememberCoroutineScope()
    val weekId = state.weeklyPlanId
    var bilkaCatalog by remember(weekId) { mutableStateOf<BilkaProductCatalog?>(null) }
    var bilkaContext by remember(weekId) { mutableStateOf<BilkaShoppingContext?>(null) }
    var bilkaLoading by remember(weekId) { mutableStateOf(!state.isLocalMode) }
    var bilkaLoadError by remember(weekId) { mutableStateOf(false) }

    suspend fun refreshBilkaChoices() {
        if (state.isLocalMode || weekId == null) {
            bilkaLoading = false
            return
        }
        bilkaLoading = true
        bilkaLoadError = false
        val (catalog, weekly) = coroutineScope {
            val catalogJob = async {
                BilkaPrejClient.catalog(
                    state.householdIdentifier,
                    state.shoppingItems().map { it.ingredient.id }.distinct()
                )
            }
            val weeklyJob = async {
                BilkaPrejClient.shoppingContext(state.householdIdentifier, weekId)
            }
            catalogJob.await() to weeklyJob.await()
        }
        if (catalog.isSuccess && weekly.isSuccess) {
            runCatching {
                bilkaCatalog = parseBilkaCatalog(catalog.getOrThrow())
                bilkaContext = decodeBilkaShoppingContext(weekly.getOrThrow())
            }.onFailure {
                bilkaCatalog = null
                bilkaContext = null
                bilkaLoadError = true
            }
        } else {
            bilkaCatalog = null
            bilkaContext = null
            bilkaLoadError = true
        }
        bilkaLoading = false
    }

    LaunchedEffect(weekId, state.householdIdentifier) { refreshBilkaChoices() }

    var addToPantryItem by remember {
        mutableStateOf<ShoppingItem?>(null)
    }
    var editPantryItem by remember {
        mutableStateOf<ShoppingItem?>(null)
    }

    editPantryItem?.let { item ->
        PantryAmountDialog(
            ingredient = item.ingredient,
            state = state,
            onDismiss = { editPantryItem = null }
        )
    }

    addToPantryItem?.let { item ->
        val catalog = bilkaCatalog
        val selection = bilkaContext
        if (!state.isLocalMode && catalog != null && selection != null &&
            (catalog.productsFor(item.ingredient.id).isNotEmpty() ||
                selection.default(item.ingredient.id) != null)
        ) {
            BilkaPurchaseDialog(
                item = item,
                state = state,
                catalog = catalog,
                shopping = selection,
                onDismiss = { addToPantryItem = null },
                onSaved = {
                    addToPantryItem = null
                    scope.launch { refreshBilkaChoices() }
                }
            )
        } else {
            AddPurchasedToPantryDialog(
                item = item,
                state = state,
                onDismiss = { addToPantryItem = null }
            )
        }
    }

    val sections by remember {
        derivedStateOf {
            val all = state.shoppingItems()
            val included = ArrayList<ShoppingItem>()
            val missing = ArrayList<ShoppingItem>()
            val bought = ArrayList<ShoppingItem>()
            val excluded = ArrayList<ShoppingItem>()

            all.forEach { item ->
                val ingredientId = item.ingredient.id
                if (state.isShoppingIncluded(ingredientId)) {
                    included += item
                    if (
                        state.shoppingChecked[ingredientId] ==
                        true
                    ) {
                        bought += item
                    } else {
                        missing += item
                    }
                } else {
                    excluded += item
                }
            }

            ShoppingSections(
                all = all,
                included = included,
                missing = missing,
                bought = bought,
                excluded = excluded,
                missingByCategoryId =
                    missing.groupBy {
                        it.ingredient.shoppingCategoryId
                    },
                checkedCount = bought.size
            )
        }
    }
    val shoppingItems = sections.all
    val included = sections.included
    val missing = sections.missing
    val bought = sections.bought
    val excluded = sections.excluded
    val checkedCount = sections.checkedCount

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Indkøb",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!state.isLocalMode && bilkaLoadError) {
            item {
                Text(
                    "Bilka er midlertidigt utilgængelig. Du kan stadig bruge Lager manuelt.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { scope.launch { refreshBilkaChoices() } }) {
                    Text("Hent Bilka-pakker igen")
                }
            }
        }

        if (shoppingItems.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "$checkedCount af ${included.size} købt",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (included.isEmpty()) {
                                    0f
                                } else {
                                    checkedCount.toFloat() /
                                        included.size.toFloat()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (shoppingItems.isEmpty()) {
            item {
                EmptyCard("Indkøbslisten er tom.")
            }
        } else {
            if (missing.isNotEmpty()) {
                val categories = state.ingredientCategories.sortedBy { it.name.lowercase() }
                val knownIds = categories.map { it.id }.toSet()

                categories.forEach { category ->
                    val categoryItems =
                        sections.missingByCategoryId[category.id]
                            .orEmpty()
                            .sortedBy { it.ingredient.name.lowercase() }

                    if (categoryItems.isNotEmpty()) {
                        item(key = "category-${category.id}") {
                            Text(
                                category.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        items(
                            items = categoryItems,
                            key = { "missing-${it.ingredient.id}" }
                        ) { item ->
                            ShoppingItemCard(
                                item = item,
                                state = state,
                                included = true,
                                checked = false,
                                bilkaInfo = bilkaShoppingLine(item, bilkaContext, bilkaCatalog),
                                onEditPantry = {
                                    editPantryItem = item
                                }
                            )
                        }
                    }
                }

                val uncategorized =
                    sections.missingByCategoryId
                        .filterKeys { it == null || it !in knownIds }
                        .values
                        .flatten()
                        .sortedBy { it.ingredient.name.lowercase() }

                if (uncategorized.isNotEmpty()) {
                    item(key = "category-other") {
                        Text(
                            "Andet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    items(
                        items = uncategorized,
                        key = { "missing-${it.ingredient.id}" }
                    ) { item ->
                        ShoppingItemCard(
                            item = item,
                            state = state,
                            included = true,
                            checked = false,
                            bilkaInfo = bilkaShoppingLine(item, bilkaContext, bilkaCatalog),
                            onEditPantry = {
                                editPantryItem = item
                            }
                        )
                    }
                }
            }

            if (bought.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Købt",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(bought.sortedBy { it.ingredient.name.lowercase() }, key = { it.ingredient.id }) { item ->
                    ShoppingItemCard(
                        item = item,
                        state = state,
                        included = true,
                        checked = true,
                        bilkaInfo = bilkaShoppingLine(item, bilkaContext, bilkaCatalog),
                        onEditPantry = {
                            editPantryItem = item
                        },
                        onAddToPantry = if (bilkaLoading ||
                            bilkaContext?.purchase(item.ingredient.id)?.purchasedAmountBase != null
                        ) null else {
                            { addToPantryItem = item }
                        }
                    )
                }
            }

            if (excluded.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Købes ikke",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(
                    items = excluded.sortedBy { it.ingredient.name.lowercase() },
                    key = { "excluded-${it.ingredient.id}" }
                ) { item ->
                    ShoppingItemCard(
                        item = item,
                        state = state,
                        included = false,
                        checked = false,
                        onEditPantry = {
                            editPantryItem = item
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ShoppingItemCard(
    item: ShoppingItem,
    state: MealPlannerState,
    included: Boolean,
    checked: Boolean,
    bilkaInfo: String? = null,
    onEditPantry: (() -> Unit)? = null,
    onAddToPantry: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Checkbox(
                checked = checked,
                enabled = included,
                onCheckedChange = {
                    state.setShoppingChecked(
                        item.ingredient.id,
                        it
                    )
                }
            )

            Spacer(Modifier.width(4.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.ingredient.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (checked) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    }
                )

                Text(
                    "Behov " + state.formatBaseAmount(item.ingredient, item.requiredBase) +
                        "  ·  Lager " + state.formatBaseAmount(item.ingredient, item.pantryBase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                bilkaInfo?.let { info ->
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = included,
                    onClick = {
                        state.setShoppingIncluded(
                            ingredientId = item.ingredient.id,
                            included = !included
                        )
                    },
                    label = { Text("Køb", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp)
                )

                if (onEditPantry != null) {
                    IconButton(
                        onClick = onEditPantry,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Ret lager",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (checked && onAddToPantry != null) {
                    IconButton(
                        onClick = onAddToPantry,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Til lager",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPurchasedToPantryDialog(
    item: ShoppingItem,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val ingredient = item.ingredient
    var unit by remember(ingredient.id) {
        mutableStateOf(ingredient.preferredUnit)
    }

    val suggestedBase =
        (item.requiredBase - item.pantryBase)
            .takeIf { it > 0.0 }
            ?: item.requiredBase

    var amountText by remember(ingredient.id, item.pantryBase) {
        mutableStateOf(
            formatDisplayNumber(
                unit.fromBase(suggestedBase)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Til lager") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    ingredient.name,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { value ->
                        amountText = value.filter {
                            it.isDigit() || it == ',' || it == '.'
                        }
                    },
                    label = { Text("Købt mængde") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                SimpleSelector(
                    label = unit.selectorLabel(),
                    options = MeasurementUnit.compatibleWith(
                        ingredient.quantityType
                    ),
                    optionText = { it.selectorLabel() },
                    onSelected = { selected ->
                        val current = parseDanishDecimal(amountText)
                        val base = current?.let(unit::toBase)
                        unit = selected
                        if (base != null) {
                            amountText = formatDisplayNumber(
                                selected.fromBase(base)
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = parseDanishDecimal(amountText)
                    if (amount != null && amount > 0.0) {
                        state.addPantryAmount(
                            ingredientId = ingredient.id,
                            amount = amount,
                            unit = unit
                        )
                        onDismiss()
                    }
                }
            ) {
                Text("Tilføj")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuller")
            }
        }
    )
}
