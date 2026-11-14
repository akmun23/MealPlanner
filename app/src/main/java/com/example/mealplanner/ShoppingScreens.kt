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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
    /** Categories that have items to buy, with items already name-sorted. */
    val missingByCategory: List<Pair<IngredientCategory, List<ShoppingItem>>>,
    /** Missing items without a (known) category, name-sorted. */
    val missingUncategorized: List<ShoppingItem>,
    val checkedCount: Int
)

private fun List<ShoppingItem>.sortedByIngredientName(): List<ShoppingItem> =
    sortedBy { it.ingredient.name.lowercase() }

@Composable
fun ShoppingScreen(state: MealPlannerState) {
    val scope = rememberCoroutineScope()
    val weekId = state.weeklyPlanId
    val requestedIngredientIds by remember(state) {
        derivedStateOf {
            state.shoppingList.map { it.ingredient.id }.distinct().sorted()
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
    var extraItem by remember(weekId) {
        mutableStateOf<ShoppingItem?>(null)
    }
    var sizeFor by remember(weekId) {
        mutableStateOf<Pair<ShoppingItem, BilkaProduct>?>(null)
    }
    var allowManualAfterError by remember(weekId) { mutableStateOf(false) }
    var editPantryItem by remember {
        mutableStateOf<ShoppingItem?>(null)
    }
    var showAddIngredient by rememberSaveable { mutableStateOf(false) }
    var shoppingName by rememberSaveable { mutableStateOf("") }
    var shoppingType by rememberSaveable { mutableStateOf(QuantityType.MASS) }

    if (showAddIngredient) {
        val existing = state.ingredients.firstOrNull {
            it.name.trim().equals(shoppingName.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { showAddIngredient = false },
            title = { Text("Tilføj til fælles indkøb") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = shoppingName,
                        onValueChange = { shoppingName = it.take(120) },
                        label = { Text("Vare, fx skyr") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (existing != null) {
                        Text(
                            "Bruger eksisterende ingrediens: ${existing.name}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Ny ingrediens oprettes og deles med husstanden.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        SimpleSelector(
                            label = "Enhedstype: ${shoppingType.danishName()}",
                            options = QuantityType.entries,
                            optionText = { it.danishName() },
                            onSelected = { shoppingType = it }
                        )
                    }
                    Text(
                        "Varen vises på denne uges indkøbsliste hos begge. " +
                            "I kan sætte flueben, når den er købt.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = shoppingName.isNotBlank() && !state.isWeekChanging,
                    onClick = {
                        val type = existing?.quantityType ?: shoppingType
                        if (state.addShoppingIngredient(shoppingName, type)) {
                            shoppingName = ""
                            showAddIngredient = false
                        }
                    }
                ) { Text("Tilføj til indkøb") }
            },
            dismissButton = {
                TextButton(onClick = { showAddIngredient = false }) {
                    Text("Annuller")
                }
            }
        )
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
        } else if (!state.isLocalMode && weekId != null) {
            // All cloud purchases, including a manually named item and cases
            // where Bilka's catalogue/context lookup failed, must go through
            // the server's atomic per-week purchase transaction. Never use
            // local addPantryAmount as a cloud fallback: retry could double
            // credit the same ingredient after a network timeout.
            BilkaPurchaseDialog(
                weekId = weekId,
                item = item,
                state = state,
                catalog = catalog ?: BilkaProductCatalog(
                    candidates = emptyList(),
                    defaults = emptyList(),
                    products = emptyList()
                ),
                shopping = selection ?: BilkaShoppingContext(
                    defaults = emptyList(),
                    purchases = emptyList()
                ),
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
        } else if (state.isLocalMode) {
            AddPurchasedToPantryDialog(
                item = item,
                state = state,
                onDismiss = {
                    addToPantryItem = null
                    allowManualAfterError = false
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { addToPantryItem = null },
                title = { Text("Ugen er ikke klar") },
                text = { Text("Købet kan ikke registreres sikkert uden en gyldig uge. Opdatér madplanen og prøv igen.") },
                confirmButton = {
                    TextButton(onClick = { addToPantryItem = null }) { Text("OK") }
                }
            )
        }
    }

    val sections by remember {
        derivedStateOf {
            val all = state.shoppingList
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

            // Sort once per data change, not on every LazyColumn content pass.
            val categories = state.sortedIngredientCategories
            val categoryIds = categories.mapTo(HashSet()) { it.id }
            val missingByCategoryId = missing.groupBy {
                it.ingredient.shoppingCategoryId
            }

            ShoppingSections(
                all = all,
                included = included,
                missing = missing,
                bought = bought.sortedByIngredientName(),
                excluded = excluded.sortedByIngredientName(),
                missingByCategory = categories.mapNotNull { category ->
                    missingByCategoryId[category.id]
                        ?.sortedByIngredientName()
                        ?.let { category to it }
                },
                missingUncategorized = missing
                    .filter { it.ingredient.shoppingCategoryId !in categoryIds }
                    .sortedByIngredientName(),
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
    val priceEstimate = remember(included, bilkaContext, bilkaCatalog) {
        estimateShoppingPrice(included, bilkaContext, bilkaCatalog)
    }
    sizeFor?.let { (item, product) ->
        ConfirmPackageSizeDialog(
            item = item,
            product = product,
            state = state,
            onDismiss = { sizeFor = null }
        )
    }
    extraItem?.let { item ->
        ShoppingExtraDialog(
            item = item,
            state = state,
            onDismiss = { extraItem = null }
        )
    }
    var showBought by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppLayout.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AppLayout.itemSpacing)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ScreenTitle("Indkøb", modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { showAddIngredient = true },
                    enabled = state.weeklyPlanId != null && !state.isWeekChanging
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Tilføj vare")
                }
            }
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
                    shape = AppShapes.card,
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
                        if (priceEstimate.pricedLines > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Estimeret pris: ca. " + kroner(priceEstimate.totalOre),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Baseret på ${priceEstimate.pricedLines} varer" +
                                    if (priceEstimate.unpricedLines > 0) {
                                        " · ${priceEstimate.unpricedLines} uden pris"
                                    } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        if (shoppingItems.isEmpty()) {
            item {
                EmptyCard(
                    title = "Indkøbslisten er tom",
                    text = "Indkøbslisten er tom. Tilføj en vare manuelt, eller vælg retter til madplanen.",
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
                sections.missingByCategory.forEach { (category, categoryItems) ->
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
                            bilkaInfo = if (item.manualOnly) null else
                                bilkaShoppingLine(item, bilkaContext, bilkaCatalog),
                            onEditPantry = {
                                editPantryItem = item
                            },
                            onEditExtra = { extraItem = item },
                            onConfirmSize = bilkaProductNeedingSize(item, bilkaContext, bilkaCatalog)
                                ?.let { product -> { sizeFor = item to product } }
                        )
                    }
                }

                val uncategorized = sections.missingUncategorized
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
                            bilkaInfo = if (item.manualOnly) null else
                                bilkaShoppingLine(item, bilkaContext, bilkaCatalog),
                            onEditPantry = {
                                editPantryItem = item
                            },
                            onEditExtra = { extraItem = item },
                            onConfirmSize = bilkaProductNeedingSize(item, bilkaContext, bilkaCatalog)
                                ?.let { product -> { sizeFor = item to product } }
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
                    items = bought,
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
                        onEditExtra = { extraItem = item },
                            onConfirmSize = bilkaProductNeedingSize(item, bilkaContext, bilkaCatalog)
                                ?.let { product -> { sizeFor = item to product } },
                        // More than one legitimate purchase of the same item
                        // per week is now supported by the event-idempotent RPC.
                        onAddToPantry = if (bilkaLoading) null else {
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
                    items = excluded,
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
    onEditExtra: (() -> Unit)? = null,
    onConfirmSize: (() -> Unit)? = null,
    onAddToPantry: (() -> Unit)? = null
) {
    val ingredient = item.ingredient
    val missingBase = (item.requiredBase - item.pantryBase).coerceAtLeast(0.0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
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
                    if (item.manualOnly) {
                        Text(
                            "Tilføjet manuelt · fælles indkøb",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (item.extraBase > 0.0) {
                            Text(
                                "Mangler ${state.formatBaseAmount(ingredient, missingBase)} · " +
                                    "Lager ${state.formatBaseAmount(ingredient, item.pantryBase)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    } else {
                        Text(
                            "Mangler ${state.formatBaseAmount(ingredient, missingBase)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Behov ${state.formatBaseAmount(ingredient, item.requiredBase)}" +
                                (if (item.extraBase > 0.0) {
                                    " (heraf ${state.formatBaseAmount(ingredient, item.extraBase)} ekstra)"
                                } else "") + " · " +
                                "Lager ${state.formatBaseAmount(ingredient, item.pantryBase)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
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
                if (onConfirmSize != null) {
                    TextButton(
                        onClick = onConfirmSize,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Bekræft pakkestørrelse") }
                }
                if (onEditExtra != null && included) {
                    TextButton(
                        onClick = onEditExtra,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (item.extraBase > 0.0) "Ret ekstra" else "Ekstra mængde")
                    }
                }
                TextButton(
                    onClick = {
                        state.setShoppingIncluded(
                            ingredientId = ingredient.id,
                            included = !included
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        when {
                            item.manualOnly -> "Fjern fra indkøb"
                            included -> "Køb ikke"
                            else -> "Tilføj til indkøb"
                        }
                    )
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
            if (item.manualOnly) "" else formatDisplayNumber(
                unit.fromBase(suggestedBase)
            )
        )
    }

    val purchasedBase = PurchaseDialogRules.validPurchasedAmount(amountText, unit)

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
                    singleLine = true,
                    isError = purchasedBase == null
                )
                if (purchasedBase == null) {
                    Text(
                        "Angiv en positiv købt mængde (maks. 10.000.000 grundenheder).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

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
                    val amount = purchasedBase ?: return@Button
                    state.addPantryAmount(
                        ingredientId = ingredient.id,
                        amount = unit.fromBase(amount),
                        unit = unit
                    )
                    onDismiss()
                },
                enabled = purchasedBase != null
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

/**
 * Ask for more than the recipes need ("mælk til kaffe"). The extra is added to
 * the week's need, so it works for ingredients that are already on the list.
 */
@Composable
private fun ShoppingExtraDialog(
    item: ShoppingItem,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val ingredient = item.ingredient
    val units = remember(ingredient.id) {
        MeasurementUnit.compatibleWith(ingredient.quantityType)
    }
    var unit by remember(ingredient.id) {
        mutableStateOf(ingredient.preferredUnit.takeIf { it in units } ?: units.first())
    }
    var amountText by remember(ingredient.id) {
        mutableStateOf(
            if (item.extraBase > 0.0) {
                formatDisplayNumber(unit.fromBase(item.extraBase))
            } else ""
        )
    }
    val base = parseDanishDecimal(amountText)?.let(unit::toBase)
    val valid = base != null && base.isFinite() && base > 0.0 && base <= 1_000_000_000.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ekstra ${ingredient.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Lægges oveni det, opskrifterne kræver – fx mælk til kaffe.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { value ->
                            amountText = value.filter { it.isDigit() || it == ',' || it == '.' }
                        },
                        label = { Text("Mængde") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    if (units.size > 1) {
                        SimpleSelector(
                            label = unit.selectorLabel(),
                            options = units,
                            optionText = { it.selectorLabel() },
                            onSelected = { unit = it }
                        )
                    } else {
                        Text(unit.selectorLabel())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    state.setShoppingExtra(ingredient.id, base ?: return@TextButton)
                    onDismiss()
                }
            ) { Text("Gem") }
        },
        dismissButton = {
            Row {
                if (item.extraBase > 0.0) {
                    TextButton(
                        onClick = {
                            state.setShoppingExtra(ingredient.id, 0.0)
                            onDismiss()
                        }
                    ) { Text("Fjern ekstra") }
                }
                TextButton(onClick = onDismiss) { Text("Annullér") }
            }
        }
    )
}

/**
 * "This product is 280 g - what is that for {ingredient}?" Saves a household
 * conversion on the ingredient (e.g. 1 stk = 280 g) that bridges every product
 * of it measured in that unit, so package counts and prices can be worked out.
 */
@Composable
private fun ConfirmPackageSizeDialog(
    item: ShoppingItem,
    product: BilkaProduct,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val ingredient = item.ingredient
    val type = ingredient.quantityType
    val options = remember(ingredient.id) {
        QuantityUnit.entries.filter { it.nativeUnit?.quantityType != type }
    }
    val productUnit = product.quantityKind?.nativeUnit()
    var unit by remember(product.id) {
        mutableStateOf(productUnit?.takeIf { it in options } ?: options.first())
    }
    // One product is most often one piece/pack: start from its own size.
    var amountText by remember(product.id) {
        mutableStateOf(
            product.packageBaseAmount
                ?.takeIf { productUnit != null && ConversionPhrasing.inverted(type, productUnit) }
                ?.let { formatDisplayNumber(it) }
                ?: ""
        )
    }
    val stored = parseDanishDecimal(amountText)
        ?.let { ConversionPhrasing.stored(type, unit, it) }
    val size = product.packageQuantity?.let {
        formatDisplayNumber(it) + " " + (product.packageUnit ?: "")
    } ?: product.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bekræft pakkestørrelse") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${product.brandLabel} er $size. Hvad svarer det til for " +
                        "${ingredient.name}? Det gælder alle produkter af varen " +
                        "og bruges til at regne pakker og pris ud.",
                    style = MaterialTheme.typography.bodyMedium
                )
                ConversionAmountRow(
                    type = type,
                    options = options,
                    unit = unit,
                    unitLocked = false,
                    onUnitChange = { unit = it },
                    amountText = amountText,
                    onAmountChange = { amountText = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = stored != null,
                onClick = {
                    val rule = IngredientUnitConversion(
                        unit = unit,
                        baseAmountPerUnit = stored ?: return@TextButton,
                        approximate = false,
                        source = "household-confirmed"
                    )
                    val saved = state.updateIngredient(
                        id = ingredient.id,
                        name = ingredient.name,
                        type = ingredient.quantityType,
                        shoppingCategoryId = ingredient.shoppingCategoryId,
                        isProtein = ingredient.isProtein,
                        preferredUnit = ingredient.preferredUnit,
                        imageUri = ingredient.imageUri,
                        neverBuy = ingredient.neverBuy,
                        unitConversions =
                            ingredient.unitConversions.filterNot { it.unit == unit } + rule
                    )
                    if (saved) onDismiss()
                }
            ) { Text("Gem") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annullér") } }
    )
}
