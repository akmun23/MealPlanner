package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    val requestedIngredientIds by remember(state) {
        derivedStateOf {
            state.shoppingItems().map { it.ingredient.id }.distinct().sorted()
        }
    }
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
        try {
            val (catalog, weekly) = coroutineScope {
                val catalogJob = async {
                    BilkaPrejClient.catalog(
                        state.householdIdentifier,
                        requestedIngredientIds
                    )
                }
                val weeklyJob = async {
                    BilkaPrejClient.shoppingContext(state.householdIdentifier, weekId)
                }
                catalogJob.await() to weeklyJob.await()
            }
            // Preserve successful responses independently; a failed catalogue
            // must never erase known prior purchase overrides.
            val result = decodeBilkaShoppingLoad(catalog, weekly)
            result.catalog?.let { bilkaCatalog = it }
            result.context?.let { bilkaContext = it }
            bilkaLoadError = result.hasError
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never leave all Add buttons hidden behind a permanent loading
            // state on an unexpected network/JSON exception.
            bilkaLoadError = true
        } finally {
            bilkaLoading = false
        }
    }

    LaunchedEffect(weekId, state.householdIdentifier, requestedIngredientIds) {
        refreshBilkaChoices()
    }

    var addToPantryItem by remember(weekId) {
        mutableStateOf<ShoppingItem?>(null)
    }
    var allowManualAfterError by remember(weekId) { mutableStateOf(false) }
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
        if (!state.isLocalMode && bilkaLoadError && !allowManualAfterError) {
            AlertDialog(
                onDismissRequest = {
                    addToPantryItem = null
                    allowManualAfterError = false
                },
                title = { Text("Bilka kan ikke kontrollere købet") },
                text = {
                    Text(
                        "Købet kan allerede være lagt på Lager fra en anden enhed. " +
                                "Prøv igen, eller brug manuel registrering, hvis du er sikker på, " +
                                "at varen endnu ikke er registreret."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { allowManualAfterError = true }) {
                        Text("Brug manuelt")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        addToPantryItem = null
                        allowManualAfterError = false
                        scope.launch { refreshBilkaChoices() }
                    }) {
                        Text("Prøv igen")
                    }
                }
            )
        } else if (!state.isLocalMode && weekId != null && catalog != null && selection != null &&
            !allowManualAfterError
        ) {
            // Even when no selected catalogue product exists, use the atomic
            // server-backed purchase route in cloud mode. The Bilka dialog
            // supports a manually named product and prevents duplicate credit.
            BilkaPurchaseDialog(
                weekId = weekId,
                item = item,
                state = state,
                catalog = catalog,
                shopping = selection,
                onDismiss = {
                    addToPantryItem = null
                    allowManualAfterError = false
                },
                onSaved = {
                    addToPantryItem = null
                    allowManualAfterError = false
                    scope.launch { refreshBilkaChoices() }
                }
            )
        } else {
            AddPurchasedToPantryDialog(
                item = item,
                state = state,
                onDismiss = {
                    addToPantryItem = null
                    allowManualAfterError = false
                }
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
    var showBought by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }

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

        if (!state.isLocalMode && bilkaLoading) {
            item {
                Text(
                    "Henter Bilka-pakker…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                val percentage = if (included.isEmpty()) 0 else (checkedCount * 100 / included.size)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Indkøbsstatus",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "$checkedCount af ${included.size} købt ($percentage%)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (included.isEmpty()) 0f
                                else checkedCount.toFloat() / included.size.toFloat()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }

        if (shoppingItems.isEmpty()) {
            item {
                EmptyCard(
                    title = "Indkøbslisten er tom",
                    text = "Der er ingen varer at købe lige nu. Tilføj en madplan eller retter for at se ingredienser her.",
                    icon = Icons.Filled.ShoppingCart
                )
            }
        } else {
            if (missing.isEmpty() && included.isNotEmpty()) {
                item {
                    EmptyCard(
                        title = "Alt på indkøbslisten er købt! 🎉",
                        text = "Alle $checkedCount valgte varer er markeret som købt. Du kan udvide 'Købte varer' nedenfor for at se eller lægge dem på lager.",
                        icon = Icons.Filled.CheckCircle
                    )
                }
            }

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
                            key = { "missing-${it.ingredient.id}" },
                            contentType = { "shopping-line" }
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
                        key = { "missing-${it.ingredient.id}" },
                        contentType = { "shopping-line" }
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
                    TextButton(
                        onClick = { showBought = !showBought },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text((if (showBought) "Skjul" else "Vis") + " købte varer (${bought.size})")
                    }
                }

                if (showBought) items(
                    items = bought.sortedBy { it.ingredient.name.lowercase() },
                    key = { it.ingredient.id },
                    contentType = { "shopping-line" }
                ) { item ->
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
                    TextButton(
                        onClick = { showExcluded = !showExcluded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text((if (showExcluded) "Skjul" else "Vis") + " varer, der ikke købes (${excluded.size})")
                    }
                }

                if (showExcluded) items(
                    items = excluded.sortedBy { it.ingredient.name.lowercase() },
                    key = { "excluded-${it.ingredient.id}" },
                    contentType = { "shopping-line" }
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
    val ingredient = item.ingredient
    val missingBase = (item.requiredBase - item.pantryBase).coerceAtLeast(0.0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (checked) 0.dp else 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    enabled = included,
                    modifier = Modifier.semantics {
                        contentDescription = "Marker ${ingredient.name} som købt"
                    },
                    onCheckedChange = { state.setShoppingChecked(ingredient.id, it) }
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ingredient.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (checked) TextDecoration.LineThrough
                        else TextDecoration.None
                    )
                    Text(
                        "Mangler ${state.formatBaseAmount(ingredient, missingBase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Behov ${state.formatBaseAmount(ingredient, item.requiredBase)} · " +
                                "Lager ${state.formatBaseAmount(ingredient, item.pantryBase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    bilkaInfo?.let { info ->
                        Text(
                            info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2
                        )
                    }
                }
                if (onEditPantry != null) {
                    IconButton(
                        onClick = onEditPantry,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Ret lager for ${ingredient.name}",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (checked && onAddToPantry != null) {
                    IconButton(
                        onClick = onAddToPantry,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Tilføj ${ingredient.name} til lager",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        state.setShoppingIncluded(
                            ingredientId = ingredient.id,
                            included = !included
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(if (included) "Køb ikke" else "Tilføj til indkøb")
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

    val suggestedBase = PurchaseDialogRules.suggestedPurchasedBase(
        requiredBase = item.requiredBase,
        pantryBase = item.pantryBase,
        preferredUnit = ingredient.preferredUnit
    )

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
            val validAmount = parseDanishDecimal(amountText)
                ?.takeIf {
                    PurchaseDialogRules.validPurchasedAmount(amountText, unit) != null
                }
            Button(
                onClick = {
                    val amount = validAmount ?: return@Button
                    state.addPantryAmount(
                        ingredientId = ingredient.id,
                        amount = amount,
                        unit = unit
                    )
                    onDismiss()
                },
                enabled = validAmount != null
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
