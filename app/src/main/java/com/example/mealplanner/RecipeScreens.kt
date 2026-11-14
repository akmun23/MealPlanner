package com.example.mealplanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class RecipeListUi(
    val recipe: Recipe,
    val categoryName: String,
    val proteinNames: List<String>
) {
    /**
     * Every field the search box matches, joined once per data change so
     * typing is a single `contains` per recipe instead of rebuilding labels.
     * '\n' cannot occur in a single-line query, so fields never match across.
     */
    val searchText: String = listOf(
        recipe.name,
        categoryName,
        recipeCategoryDisplayName(categoryName, proteinNames),
        recipeProteinLabel(proteinNames),
    ).plus(proteinNames).joinToString("\n")
}

// Flat, Bundle-compatible savers preserve unsaved long recipes on rotation.
private val recipeStepSaver = listSaver<SnapshotStateList<RecipeInstructionStep>, String>(
    save = { steps -> steps.flatMap { listOf(it.id, it.text, it.level.toString()) } },
    restore = { stored ->
        mutableStateListOf<RecipeInstructionStep>().apply {
            stored.chunked(3).filter { it.size == 3 }.forEach { parts ->
                add(RecipeInstructionStep(parts[0], parts[1], parts[2].toIntOrNull() ?: 0))
            }
        }
    }
)
private val recipeDraftSaver = listSaver<SnapshotStateList<DraftRequirement>, String>(
    save = { drafts ->
        listOf("recipe-draft-v2") + drafts.flatMap { draft ->
            listOf(
                draft.ingredientId, draft.amount.toString(), draft.unit.name,
                draft.displayUnit?.name ?: "",
                draft.displayFactor?.toString() ?: "",
                draft.displayApproximate.toString()
            )
        }
    },
    restore = { stored ->
        mutableStateListOf<DraftRequirement>().apply {
            // Never guess from list length: an old bundle with exactly two
            // 3-field ingredients has the same length as one new 6-field row.
            val versioned = stored.firstOrNull() == "recipe-draft-v2"
            val stride = if (versioned) 6 else 3
            val payload = if (versioned) stored.drop(1) else stored
            payload.chunked(stride).filter { it.size == stride }.forEach { parts ->
                val amount = parts[1].toDoubleOrNull()
                val unit = MeasurementUnit.entries.find { it.name == parts[2] }
                if (amount != null && unit != null) {
                    add(DraftRequirement(
                        ingredientId = parts[0],
                        amount = amount,
                        unit = unit,
                        displayUnit = if (stride == 6) {
                            QuantityUnit.entries.firstOrNull { it.name == parts[3] }
                        } else null,
                        displayFactor = if (stride == 6) parts[4].toDoubleOrNull() else null,
                        displayApproximate = stride == 6 && parts[5] == "true"
                    ))
                }
            }
        }
    }
)

private val conversionProfileSaver =
    listSaver<SnapshotStateList<IngredientUnitConversion>, String>(
        save = { profiles ->
            profiles.flatMap { listOf(
                it.unit.name, it.baseAmountPerUnit.toString(),
                it.approximate.toString(), it.source, it.revision.toString()
            ) }
        },
        restore = { stored ->
            mutableStateListOf<IngredientUnitConversion>().apply {
                stored.chunked(5).filter { it.size == 5 }.forEach { part ->
                    val unit = QuantityUnit.entries.firstOrNull { it.name == part[0] }
                    val factor = part[1].toDoubleOrNull()
                    if (unit != null && factor != null && factor.isFinite() && factor > 0.0) {
                        add(IngredientUnitConversion(
                            unit, factor, part[2].toBooleanStrictOrNull() ?: false,
                            part[3], part[4].toLongOrNull() ?: 1L
                        ))
                    }
                }
            }
        }
    )

@Composable
fun RecipesScreen(
    state: MealPlannerState,
    onAddRecipe: () -> Unit,
    onOpenRecipe: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var mealFilter by rememberSaveable { mutableStateOf("Alle måltider") }
    var categoryFilter by rememberSaveable { mutableStateOf("Alle kategorier") }
    var canMakeOnly by rememberSaveable { mutableStateOf(false) }

    val mealOptions = remember {
        listOf("Alle måltider") + MealType.entries.map { it.danishName() }
    }
    val categoryOptions by remember {
        derivedStateOf {
            listOf("Alle kategorier") +
                    state.sortedRecipeCategories.map { it.name }
        }
    }

    // Build recipe metadata only when recipes, ingredient protein flags, or
    // categories change. Search typing / pantry checkboxes only filter cached
    // rows instead of rebuilding protein names for every recipe each time.
    val recipeRows by remember {
        derivedStateOf {
            val ingredientsById = state.ingredients.associateBy { it.id }
            val categoryNamesById = state.recipeCategories.associate {
                it.id to it.name
            }

            state.recipes.map { recipe ->
                val proteinNames = recipe.ingredients.asSequence()
                    .mapNotNull { ingredientsById[it.ingredientId] }
                    .filter { it.isProtein }
                    .map { it.name }
                    .distinct()
                    .sortedBy { it.lowercase() }
                    .toList()

                RecipeListUi(
                    recipe = recipe,
                    categoryName =
                        categoryNamesById[recipe.categoryId] ?: "Andet",
                    proteinNames = proteinNames
                )
            }.sortedBy { it.recipe.name.lowercase() }
        }
    }

    // Keep pantry availability calculations independent of text search.
    // Typing a query should only filter cached IDs, not rescan stock per recipe.
    val makeableRecipeIds by remember(state) {
        derivedStateOf {
            if (!canMakeOnly) emptySet()
            else state.recipes.asSequence()
                .filter { state.canMake(it) }
                .map { it.id }
                .toSet()
        }
    }

    val filteredRecipes by remember {
        derivedStateOf {
            val query = searchQuery.trim()
            recipeRows.filter { item ->
                val recipe = item.recipe
                (query.isBlank() ||
                        item.searchText.contains(query, ignoreCase = true)) &&
                        (mealFilter == "Alle måltider" ||
                                recipe.mealType.danishName() == mealFilter) &&
                        (categoryFilter == "Alle kategorier" ||
                                item.categoryName == categoryFilter) &&
                        (!canMakeOnly || recipe.id in makeableRecipeIds)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppLayout.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AppLayout.itemSpacing)
    ) {
        item {
            ScreenTitle("Opskrifter")
        }

        item {
            Button(
                onClick = onAddRecipe,
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.control
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Ny opskrift")
            }
        }

        item {
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Søg i opskrifter"
            )
        }

        item(key = "recipe-filters") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        SimpleSelector(
                            label = mealFilter,
                            options = mealOptions,
                            optionText = { it },
                            onSelected = { mealFilter = it },
                            fillWidth = true
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SimpleSelector(
                            label = categoryFilter,
                            options = categoryOptions,
                            optionText = { it },
                            onSelected = { categoryFilter = it },
                            fillWidth = true
                        )
                    }
                }
                FilterChip(
                    selected = canMakeOnly,
                    onClick = { canMakeOnly = !canMakeOnly },
                    label = { Text("Kan laves") }
                )
            }
        }

        if (filteredRecipes.isEmpty()) {
            item {
                val isFiltered = searchQuery.isNotBlank() ||
                        mealFilter != "Alle måltider" ||
                        categoryFilter != "Alle kategorier" || canMakeOnly

                EmptyCard(
                    title = if (state.recipes.isEmpty()) "Ingen opskrifter endnu" else "Ingen opskrifter fundet",
                    text = if (state.recipes.isEmpty()) "Opret din første opskrift ved at trykke på 'Ny opskrift' ovenfor." else "Prøv en anden søgning eller ryd dine filtre.",
                    icon = Icons.Filled.RestaurantMenu,
                    action = if (isFiltered) {
                        {
                            OutlinedButton(
                                onClick = {
                                    searchQuery = ""
                                    mealFilter = "Alle måltider"
                                    categoryFilter = "Alle kategorier"
                                    canMakeOnly = false
                                },
                                shape = AppShapes.control
                            ) {
                                Text("Ryd filtre")
                            }
                        }
                    } else null
                )
            }
        } else {
            items(
                items = filteredRecipes,
                key = { it.recipe.id },
                contentType = { "recipe-card" }
            ) { item ->
                RecipeListCard(
                    recipe = item.recipe,
                    categoryName = item.categoryName,
                    proteinNames = item.proteinNames,
                    isSandwich =
                        state.weeklySandwichRecipeId ==
                                item.recipe.id,
                    onClick = {
                        onOpenRecipe(item.recipe.id)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun RecipeListCard(
    recipe: Recipe,
    categoryName: String,
    proteinNames: List<String>,
    isSandwich: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Åbn opskrift: ${recipe.name}" }
            .clickable(onClick = onClick),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppThumbnail(
                imageUri = recipe.imageUri,
                fallback = recipe.name.take(1).uppercase()
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val displayCategory =
                    recipeCategoryDisplayName(categoryName, proteinNames)
                Text(
                    recipeCompactMeta(recipe, displayCategory),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Protein gets its own readable line instead of disappearing
                // at the end of a long one-line metadata string.
                if (proteinNames.isNotEmpty() || displayCategory != "Vegetarisk") {
                    Text(
                        if (proteinNames.isEmpty()) "Vegetarisk"
                        else "Protein: ${recipeProteinLabel(proteinNames)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isSandwich) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Ugens sandwich",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatNumberSimple(value: Double): String {
    if (kotlin.math.abs(value - value.toInt()) < 0.00001) {
        return value.toInt().toString()
    }
    return String.format(Locale.forLanguageTag("da-DK"), "%.2f", value).trimEnd('0').trimEnd('.')
}

@Composable
private fun ConsumeRecipeIngredientsDialog(
    recipe: Recipe,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    val ingredientSelection = remember(recipe.id) {
        mutableStateMapOf<String, Pair<Boolean, String>>().apply {
            recipe.pantryPrerequisites.forEach { req ->
                val ingredient = state.ingredients.find { it.id == req.ingredientId }
                if (ingredient != null) {
                    val unit = ingredient.preferredUnit
                    val reqAmountUnit = unit.fromBase(req.minimumAmountBase)
                    put(req.ingredientId, Pair(true, formatNumberSimple(reqAmountUnit)))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Træk fra lager", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Vælg ingredienser og mængder der skal trækkes fra dit lager for ${recipe.name}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                recipe.pantryPrerequisites.forEach { req ->
                    val ingredient = state.ingredients.find { it.id == req.ingredientId }
                    if (ingredient != null) {
                        val selection = ingredientSelection[ingredient.id] ?: Pair(true, "0")
                        val isChecked = selection.first
                        val amountStr = selection.second
                        val unit = ingredient.preferredUnit
                        val availableBase = state.pantry[ingredient.id] ?: 0.0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppShapes.card,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChecked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            ingredientSelection[ingredient.id] =
                                                Pair(checked, amountStr)
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ingredient.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Krav: ${
                                                state.formatBaseAmount(
                                                    ingredient,
                                                    req.minimumAmountBase
                                                )
                                            } · Lager: ${
                                                state.formatBaseAmount(
                                                    ingredient,
                                                    availableBase
                                                )
                                            }",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isChecked) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 36.dp, top = 2.dp)
                                    ) {
                                        Text(
                                            "Træk: ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedTextField(
                                            value = amountStr,
                                            onValueChange = { newStr ->
                                                val clean = newStr.replace(',', '.')
                                                ingredientSelection[ingredient.id] =
                                                    Pair(true, clean)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                            singleLine = true
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            unit.symbol,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val deductions = mutableMapOf<String, Double>()
                    recipe.pantryPrerequisites.forEach { req ->
                        val selection = ingredientSelection[req.ingredientId]
                        if (selection != null && selection.first) {
                            val ingredient = state.ingredients.find { it.id == req.ingredientId }
                            if (ingredient != null) {
                                val amountUnit = selection.second.toDoubleOrNull() ?: 0.0
                                val deductBase = ingredient.preferredUnit.toBase(amountUnit)
                                deductions[req.ingredientId] = deductBase
                            }
                        }
                    }
                    state.consumeRecipeIngredients(recipe.id, deductions)
                    onDismiss()
                }
            ) {
                Text("Træk fra lager")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuller")
            }
        }
    )
}

@Composable
internal fun RecipeDetailScreen(
    state: MealPlannerState,
    recipeId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val recipe = state.recipes.find { it.id == recipeId }
    var showConsumeConfirm by remember(recipeId) {
        mutableStateOf(false)
    }
    var showDeleteConfirm by remember(recipeId) {
        mutableStateOf(false)
    }

    if (recipe == null) {
        // Navigating in the composition phase can invalidate Compose snapshots.
        // Return after composition if a recipe was removed by a cloud refresh.
        LaunchedEffect(recipeId) { onBack() }
        Text("Opskriften findes ikke længere.")
        return
    }

    val ingredientIndex by remember {
        derivedStateOf {
            state.ingredients.associateBy { it.id }
        }
    }
    val ingredientCategoryIndex by remember {
        derivedStateOf {
            state.ingredientCategories.associateBy { it.id }
        }
    }
    val sortedRecipeIngredients by remember(recipe) {
        derivedStateOf {
            sortByIngredientCategory(
                items = recipe.ingredients,
                ingredientId = RecipeIngredient::ingredientId,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
        }
    }
    val sortedPantryPrerequisites by remember(recipe) {
        derivedStateOf {
            sortByIngredientCategory(
                items = recipe.pantryPrerequisites,
                ingredientId = RecipePantryPrerequisite::ingredientId,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
        }
    }

    if (showConsumeConfirm) {
        ConsumeRecipeIngredientsDialog(
            recipe = recipe,
            state = state,
            onDismiss = { showConsumeConfirm = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
            },
            title = {
                Text("Slet opskrift?")
            },
            text = {
                Text(
                    "Opskriften fjernes fra det fælles hjem. Du kan annullere og beholde den."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        state.deleteRecipe(recipe.id)
                        onBack()
                    }
                ) {
                    Text(
                        "Slet",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    SubpageScaffold(
        title = recipe.name,
        onBack = onBack,
        action = {
            TextButton(onClick = onEdit) {
                Text("Rediger")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(AppLayout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppLayout.sectionSpacing)
        ) {
            item {
                LargeImage(
                    imageUri = recipe.imageUri,
                    fallback = recipe.name.take(1).uppercase()
                )
            }

            item {
                val proteinNames = state.recipeProteinNames(recipe)
                val displayCategory = recipeCategoryDisplayName(
                    state.recipeCategoryName(recipe), proteinNames
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        recipeCompactMeta(recipe, displayCategory),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (proteinNames.isNotEmpty() || displayCategory != "Vegetarisk") {
                        Text(
                            if (proteinNames.isEmpty()) "Vegetarisk"
                            else "Protein: ${recipeProteinLabel(proteinNames)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (
                recipe.mealType == MealType.DINNER &&
                state.weeklySandwichRecipeId != recipe.id
            ) {
                item {
                    OutlinedButton(
                        onClick = { state.setWeeklySandwichRecipe(recipe.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.control
                    ) {
                        Text("Vælg som ugens sandwich")
                    }
                }
            } else if (
                recipe.mealType == MealType.DINNER &&
                state.weeklySandwichRecipeId == recipe.id
            ) {
                item {
                    Surface(
                        shape = AppShapes.card,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Ugens sandwich",
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (recipe.pantryPrerequisites.isNotEmpty()) {
                item {
                    SectionCard("Kræver på lager") {
                        sortedPantryPrerequisites.forEach { prerequisite ->
                            val ingredient =
                                ingredientIndex[prerequisite.ingredientId]

                            if (ingredient != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        ingredient.name,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        state.formatBaseAmount(
                                            ingredient,
                                            prerequisite.minimumAmountBase
                                        ),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard("Ingredienser") {
                    sortedRecipeIngredients
                        .groupBy { requirement ->
                            ingredientIndex[
                                requirement.ingredientId
                            ]?.shoppingCategoryId
                        }
                        .forEach { (categoryId, requirements) ->
                            Text(
                                ingredientCategoryIndex[categoryId]
                                    ?.name
                                    ?: "Andet",
                                style =
                                    MaterialTheme.typography.labelSmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    top = 4.dp,
                                    bottom = 2.dp
                                )
                            )

                            requirements.forEach { requirement ->
                                val ingredient =
                                    ingredientIndex[
                                        requirement.ingredientId
                                    ]

                                if (ingredient != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement =
                                            Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            ingredient.name,
                                            modifier =
                                                Modifier.weight(1f)
                                        )
                                        Text(
                                            RecipeQuantityRules.amountAndUnit(
                                                requirement, ingredient
                                            ),
                                            fontWeight =
                                                FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showConsumeConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.control
                ) {
                    Text("Brug fra lager")
                }
            }

            if (recipe.instructionSteps.isNotEmpty()) {
                item(key = "recipe-instructions-header") {
                    Text(
                        "Fremgangsmåde",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(
                    items = RecipeInstructionRules.numbered(recipe.instructionSteps),
                    key = { (_, step) -> "recipe-instruction-${step.id}" },
                    contentType = { "recipe-instruction" }
                ) { (label, step) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            label,
                            modifier = Modifier.width(44.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(step.text, modifier = Modifier.weight(1f))
                    }
                }
            }

            if (!recipe.sourceUrl.isNullOrBlank()) {
                item {
                    val uriHandler = LocalUriHandler.current
                    SectionCard("Opskriftslink") {
                        if (recipe.sourceUrl.startsWith("https://") ||
                            recipe.sourceUrl.startsWith("http://")
                        ) {
                            TextButton(onClick = { uriHandler.openUri(recipe.sourceUrl) }) {
                                Text(recipe.sourceUrl)
                            }
                        } else {
                            Text(recipe.sourceUrl)
                        }
                    }
                }
            }

            if (recipe.notes.isNotBlank()) {
                item {
                    SectionCard("Noter") {
                        Text(recipe.notes)
                    }
                }
            }

            item {
                TextButton(
                    onClick = {
                        showDeleteConfirm = true
                    }
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Slet opskrift",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeEditorScreen(
    state: MealPlannerState,
    recipeId: String?,
    initialMealType: MealType,
    onBack: () -> Unit,
    onSaved: (String?) -> Unit
) {
    val ingredientById by remember(state) {
        derivedStateOf { state.ingredients.associateBy { it.id } }
    }

    val existing = recipeId?.let { id ->
        state.recipes.find { it.id == id }
    }
    if (recipeId != null && existing == null) {
        // A deleted recipe must not silently turn an Edit action into New.
        LaunchedEffect(recipeId) { onBack() }
        Text("Opskriften findes ikke længere.")
        return
    }

    val isKeyboardVisible = WindowInsets.isImeVisible

    var name by rememberSaveable(recipeId) { mutableStateOf(existing?.name ?: "") }
    var mealType by rememberSaveable(recipeId) {
        mutableStateOf(existing?.mealType ?: initialMealType)
    }
    var autoRotationEnabled by rememberSaveable(recipeId) {
        mutableStateOf(existing?.autoRotationEnabled ?: true)
    }
    var prepMinutesText by rememberSaveable(recipeId) {
        mutableStateOf(existing?.prepMinutes?.toString() ?: "")
    }
    val prepMinutes = prepMinutesText
        .trim()
        .takeIf { it.isNotBlank() }
        ?.toIntOrNull()
        ?.takeIf { it in 1..1440 }
    val prepTimeValid =
        prepMinutesText.isBlank() || prepMinutes != null
    var categoryId by rememberSaveable(recipeId) {
        mutableStateOf(
            existing?.categoryId
                ?: state.recipeCategories
                    .firstOrNull { it.name.equals("Andet", ignoreCase = true) }
                    ?.id
                ?: state.recipeCategories.firstOrNull()?.id
        )
    }
    var notes by rememberSaveable(recipeId) { mutableStateOf(existing?.notes ?: "") }
    var sourceUrl by rememberSaveable(recipeId) { mutableStateOf(existing?.sourceUrl.orEmpty()) }
    val instructionSteps = rememberSaveable(recipeId, saver = recipeStepSaver) {
        mutableStateListOf<RecipeInstructionStep>().apply {
            addAll(existing?.instructionSteps.orEmpty())
        }
    }
    // Only visible steps are measured; typing never remeasures an entire steps card.
    val numberedSteps by remember {
        derivedStateOf { RecipeInstructionRules.numbered(instructionSteps.toList()) }
    }
    var newMainStep by rememberSaveable(recipeId) { mutableStateOf("") }
    var newSubStep by rememberSaveable(recipeId) { mutableStateOf("") }
    var imageUri by rememberSaveable(recipeId) { mutableStateOf(existing?.imageUri) }
    var ingredientSearch by rememberSaveable(recipeId) { mutableStateOf("") }
    var prerequisiteSearch by rememberSaveable(recipeId) { mutableStateOf("") }
    val recipeNameAvailable =
        name.isBlank() ||
                state.isRecipeNameAvailable(
                    name,
                    excludingId = existing?.id
                )

    // Track intentional amount/unit changes separately from metadata edits.
    // Otherwise saving a title could rewrite a frozen "1 pakke" as "200 g",
    // while choosing "0,2 kg" explicitly should replace that old display.
    var explicitlyEditedIngredientIds by rememberSaveable(recipeId) {
        mutableStateOf(arrayListOf<String>())
    }

    val drafts = rememberSaveable(recipeId, saver = recipeDraftSaver) {
        mutableStateListOf<DraftRequirement>().apply {
            existing?.ingredients?.forEach { requirement ->
                val ingredient = ingredientById[requirement.ingredientId]

                if (ingredient != null) {
                    val display = RecipeQuantityRules.legacyEditableAmount(
                        requirement, ingredient
                    )
                    val frozenUnit = if (RecipeQuantityRules.hasValidSnapshot(requirement)) {
                        QuantityUnit.entries.firstOrNull {
                            it.name == requirement.displayUnitCode
                        }
                    } else null
                    add(
                        DraftRequirement(
                            ingredientId = ingredient.id,
                            amount = if (frozenUnit != null) {
                                requirement.displayAmount!!
                            } else display.first,
                            unit = display.second,
                            displayUnit = frozenUnit,
                            displayFactor = if (frozenUnit != null) {
                                requirement.basePerDisplayUnit
                            } else null,
                            displayApproximate = requirement.conversionApproximate
                        )
                    )
                }
            }
        }
    }

    val prerequisiteDrafts = rememberSaveable(recipeId, saver = recipeDraftSaver) {
        mutableStateListOf<DraftRequirement>().apply {
            existing?.pantryPrerequisites?.forEach { prerequisite ->
                val ingredient = ingredientById[prerequisite.ingredientId]

                if (ingredient != null) {
                    add(
                        DraftRequirement(
                            ingredientId = ingredient.id,
                            amount = ingredient.preferredUnit.fromBase(
                                prerequisite.minimumAmountBase
                            ),
                            unit = ingredient.preferredUnit
                        )
                    )
                }
            }
        }
    }

    val sortedPrerequisiteDrafts by remember {
        derivedStateOf {
            sortByIngredientCategory(
                items = prerequisiteDrafts
                    .withIndex()
                    .toList(),
                ingredientId = { it.value.ingredientId },
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
        }
    }
    val sortedDrafts by remember {
        derivedStateOf {
            sortByIngredientCategory(
                items = drafts.withIndex().toList(),
                ingredientId = { it.value.ingredientId },
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
        }
    }

    val prerequisiteMatches by remember {
        derivedStateOf {
            sortByIngredientCategory(
                items = state.ingredients
                    .filter { ingredient ->
                        prerequisiteSearch.isNotBlank() &&
                                ingredient.name.contains(
                                    prerequisiteSearch,
                                    ignoreCase = true
                                ) &&
                                prerequisiteDrafts.none {
                                    it.ingredientId == ingredient.id
                                }
                    },
                ingredientId = Ingredient::id,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            ).take(8)
        }
    }

    val matches by remember {
        derivedStateOf {
            sortByIngredientCategory(
                items = state.ingredients
                    .filter { ingredient ->
                        ingredientSearch.isNotBlank() &&
                                ingredient.name.contains(
                                    ingredientSearch,
                                    ignoreCase = true
                                ) &&
                                drafts.none {
                                    it.ingredientId == ingredient.id
                                }
                    },
                ingredientId = Ingredient::id,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            ).take(8)
        }
    }

    // Keep a comparable snapshot of editable fields. Avoid silently losing
    // long recipes when the user navigates back from a partially edited form.
    fun formSnapshot(): List<Any?> = listOf(
        name, mealType, autoRotationEnabled, prepMinutesText, categoryId,
        notes, sourceUrl, imageUri, instructionSteps.toList(), newMainStep, newSubStep,
        drafts.toList(), prerequisiteDrafts.toList()
    )

    val initialForm = remember(recipeId) { formSnapshot() }
    var changesSeen by rememberSaveable(recipeId) { mutableStateOf(false) }
    val hasUnsavedChanges = changesSeen || formSnapshot() != initialForm
    androidx.compose.runtime.LaunchedEffect(hasUnsavedChanges) {
        if (hasUnsavedChanges) changesSeen = true
    }
    var showDiscardDialog by rememberSaveable(recipeId) { mutableStateOf(false) }

    fun requestBack() {
        if (hasUnsavedChanges) showDiscardDialog = true else onBack()
    }

    BackHandler(enabled = hasUnsavedChanges) { requestBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Kassér ændringer?") },
            text = { Text("Du har ændringer i opskriften, som ikke er gemt.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("Kassér ændringer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Fortsæt redigering")
                }
            }
        )
    }

    val canSave = name.isNotBlank() &&
            recipeNameAvailable &&
            prepTimeValid &&
            drafts.isNotEmpty() &&
            drafts.all { it.amount > 0.0 } &&
            prerequisiteDrafts.all { it.amount > 0.0 }
    val saveHint = when {
        name.isBlank() -> "Angiv opskriftens navn."
        !recipeNameAvailable -> "Der findes allerede en opskrift med dette navn."
        !prepTimeValid -> "Tid skal være mellem 1 og 1440 minutter."
        drafts.isEmpty() -> "Tilføj mindst én ingrediens."
        drafts.any { it.amount <= 0.0 } ||
                prerequisiteDrafts.any { it.amount <= 0.0 } ->
            "Ret de ugyldige ingrediensmængder."

        else -> null
    }
    val saveRecipe: () -> Unit = {
        // Text still in the "next step" field is part of the
        // draft too; Save must never silently discard it.
        val fullSteps = RecipeInstructionRules.withPendingText(
            instructionSteps.toList(), newMainStep, newSubStep
        )
        if (existing == null) {
            val savedId = state.addRecipe(
                name = name,
                mealType = mealType,
                autoRotationEnabled = autoRotationEnabled,
                prepMinutes = prepMinutes,
                categoryId = categoryId,
                notes = notes,
                instructionSteps = fullSteps,
                sourceUrl = sourceUrl,
                imageUri = imageUri,
                pantryPrerequisites =
                    if (mealType == MealType.DINNER) {
                        prerequisiteDrafts.toList()
                    } else {
                        emptyList()
                    },
                requirements = drafts.toList()
            )

            if (savedId != null) {
                onSaved(savedId)
            }
        } else {
            val saved = state.updateRecipe(
                id = existing.id,
                name = name,
                mealType = mealType,
                autoRotationEnabled = autoRotationEnabled,
                prepMinutes = prepMinutes,
                categoryId = categoryId,
                notes = notes,
                instructionSteps = fullSteps,
                sourceUrl = sourceUrl,
                imageUri = imageUri,
                pantryPrerequisites =
                    if (mealType == MealType.DINNER) {
                        prerequisiteDrafts.toList()
                    } else {
                        emptyList()
                    },
                requirements = drafts.toList(),
                explicitlyEditedRequirementIds = explicitlyEditedIngredientIds.toSet()
            )
            if (saved) {
                onSaved(existing.id)
            }
        }
    }

    SubpageScaffold(
        title = if (existing == null) "Ny opskrift" else "Rediger opskrift",
        onBack = { requestBack() },
        bottomBar = {
            if (!isKeyboardVisible) Surface(tonalElevation = 3.dp) {
                Column {
                    saveHint?.let { hint ->
                        Text(
                            hint,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    Button(
                        onClick = saveRecipe,
                        enabled = canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = AppShapes.control
                    ) { Text("Gem opskrift") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(AppLayout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppLayout.itemSpacing)
        ) {
            item {
                ImagePickerBox(
                    imageUri = imageUri,
                    onImageSelected = { imageUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 150.dp,
                    label = "Vælg billede"
                )
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !recipeNameAvailable,
                    supportingText = {
                        if (!recipeNameAvailable) {
                            Text("Navnet findes allerede")
                        }
                    }
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Måltid",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        SimpleSelector(
                            label = mealType.danishName(),
                            options = MealType.entries,
                            optionText = { it.danishName() },
                            onSelected = { mealType = it },
                            fillWidth = true
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Kategori",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        if (state.recipeCategories.isEmpty()) {
                            Text(
                                "Andet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SimpleSelector(
                                label = state.recipeCategories
                                    .find { it.id == categoryId }
                                    ?.name
                                    ?: "Andet",
                                options = state.sortedRecipeCategories,
                                optionText = { it.name },
                                onSelected = { categoryId = it.id },
                                fillWidth = true
                            )
                        }
                    }
                }
            }

            if (mealType == MealType.DINNER) {
                item {
                    Text(
                        "Planlægning",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectorChips(
                        items = listOf(true, false),
                        selected = autoRotationEnabled,
                        label = {
                            if (it) "Automatisk" else "Kun manuelt"
                        },
                        onSelected = { autoRotationEnabled = it }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = prepMinutesText,
                    onValueChange = { value ->
                        prepMinutesText = value.filter { it.isDigit() }
                    },
                    label = { Text("Tid (min)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !prepTimeValid
                )
            }

            item(key = "instruction-heading") {
                Text(
                    "Fremgangsmåde · ${instructionSteps.size} trin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(
                items = numberedSteps,
                key = { _, entry -> "instruction-${entry.second.id}" },
                contentType = { _, _ -> "instruction-step" }
            ) { index, entry ->
                val (label, step) = entry
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.card,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                label,
                                modifier = Modifier.width(38.dp).padding(top = 12.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = step.text,
                                onValueChange = { value ->
                                    instructionSteps[index] = step.copy(text = value)
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(if (step.level == 1) "Undertrin" else "Trin") },
                                minLines = 2,
                                maxLines = 5
                            )
                        }
                        Row(
                            modifier = Modifier.padding(start = 38.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    val changed = RecipeInstructionRules.move(
                                        instructionSteps.toList(), index, -1
                                    )
                                    instructionSteps.clear()
                                    instructionSteps.addAll(changed)
                                }
                            ) { Text("Op") }
                            TextButton(
                                enabled = index < numberedSteps.lastIndex,
                                onClick = {
                                    val changed = RecipeInstructionRules.move(
                                        instructionSteps.toList(), index, 1
                                    )
                                    instructionSteps.clear()
                                    instructionSteps.addAll(changed)
                                }
                            ) { Text("Ned") }
                            TextButton(onClick = {
                                val changed = RecipeInstructionRules.remove(
                                    instructionSteps.toList(), index
                                )
                                instructionSteps.clear()
                                instructionSteps.addAll(changed)
                            }) { Text("Fjern") }
                        }
                    }
                }
            }
            item(key = "instruction-add") {
                SectionCard("Tilføj trin") {
                    OutlinedTextField(
                        value = newMainStep,
                        onValueChange = { newMainStep = it.take(2000) },
                        label = { Text("Skriv næste trin") },
                        placeholder = { Text("F.eks. Skær grøntsagerne i tern") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            instructionSteps.add(
                                RecipeInstructionStep(text = newMainStep.trim(), level = 0)
                            )
                            newMainStep = ""
                        },
                        enabled = newMainStep.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tilføj trin") }
                    if (instructionSteps.any { it.level == 0 }) {
                        OutlinedTextField(
                            value = newSubStep,
                            onValueChange = { newSubStep = it.take(2000) },
                            label = { Text("Skriv undertrin (valgfrit)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            enabled = newSubStep.isNotBlank(),
                            onClick = {
                                instructionSteps.add(
                                    RecipeInstructionStep(text = newSubStep.trim(), level = 1)
                                )
                                newSubStep = ""
                            }
                        ) { Text("Tilføj undertrin") }
                    }
                }
            }

            if (mealType == MealType.DINNER) {
                item {
                    Text(
                        "Kræver på lager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(
                    items = sortedPrerequisiteDrafts,
                    key = {
                        "prerequisite-${it.value.ingredientId}"
                    }
                ) { indexed ->
                    val draft = indexed.value
                    val ingredient = ingredientById[draft.ingredientId]

                    if (ingredient != null) {
                        RequirementEditor(
                            ingredient = ingredient,
                            draft = draft,
                            onChange = {
                                prerequisiteDrafts[indexed.index] = it
                            },
                            onRemove = {
                                prerequisiteDrafts.removeAt(indexed.index)
                            }
                        )
                    }
                }

                item {
                    SearchField(
                        value = prerequisiteSearch,
                        onValueChange = { prerequisiteSearch = it },
                        placeholder = "Tilføj lagerkrav"
                    )
                }

                items(
                    items = prerequisiteMatches,
                    key = { "prerequisite-match-${it.id}" }
                ) { ingredient ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (prerequisiteDrafts.none { it.ingredientId == ingredient.id }) {
                                    prerequisiteDrafts += DraftRequirement(
                                        ingredientId = ingredient.id,
                                        amount = 1.0,
                                        unit = ingredient.preferredUnit
                                    )
                                }
                                prerequisiteSearch = ""
                            },
                        shape = AppShapes.card
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ingredient.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Tilføj")
                        }
                    }
                }
            }

            item {
                SectionTitle("Ingredienser")
            }

            items(
                items = sortedDrafts,
                key = { it.value.ingredientId }
            ) { indexed ->
                val draft = indexed.value
                val ingredient = ingredientById[draft.ingredientId]

                if (ingredient != null) {
                    RequirementEditor(
                        ingredient = ingredient,
                        draft = draft,
                        allowCrossDimension = true,
                        onChange = {
                            if (it != draft) {
                                explicitlyEditedIngredientIds = ArrayList(
                                    explicitlyEditedIngredientIds + draft.ingredientId
                                )
                            }
                            drafts[indexed.index] = it
                        },
                        onRemove = {
                            drafts.removeAt(indexed.index)
                        }
                    )
                }
            }

            item {
                SearchField(
                    value = ingredientSearch,
                    onValueChange = { ingredientSearch = it },
                    placeholder = "Tilføj ingrediens"
                )
            }

            items(matches, key = { it.id }) { ingredient ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (drafts.none { it.ingredientId == ingredient.id }) {
                                drafts += DraftRequirement(
                                    ingredientId = ingredient.id,
                                    amount = 1.0,
                                    unit = ingredient.preferredUnit
                                )
                            }
                            ingredientSearch = ""
                        },
                    shape = AppShapes.card
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ingredient.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("Tilføj")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    label = { Text("Link til opskrift") },
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Noter") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun RequirementEditor(
    ingredient: Ingredient,
    draft: DraftRequirement,
    onChange: (DraftRequirement) -> Unit,
    onRemove: () -> Unit,
    allowCrossDimension: Boolean = false
) {
    var amountText by remember(draft.ingredientId, allowCrossDimension) {
        mutableStateOf(formatDisplayNumber(draft.amount))
    }
    val availableUnits = if (allowCrossDimension) {
        (QuantityPlanningRules.selectableUnits(
            ingredient, ingredient.unitConversions
        ) + listOfNotNull(draft.displayUnit)).distinct()
    } else {
        MeasurementUnit.compatibleWith(ingredient.quantityType)
            .map(QuantityUnit::fromLegacy)
    }
    val selectedUnit = draft.displayUnit ?: QuantityUnit.fromLegacy(draft.unit)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ingredient.name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onRemove) {
                    Text("Fjern")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { value ->
                        amountText = value.filter { it.isDigit() || it == ',' || it == '.' }
                        val parsed = parseDanishDecimal(amountText)
                            ?.takeIf { it.isFinite() && it > 0.0 }
                        onChange(draft.copy(amount = parsed ?: 0.0))
                    },
                    label = { Text("Mængde") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                SimpleSelector(
                    label = selectedUnit.labelFor(draft.amount),
                    options = availableUnits,
                    optionText = { it.labelFor(1.0) },
                    minHeight = 56.dp,
                    onSelected = { selected ->
                        val entered = parseDanishDecimal(amountText)
                            ?.takeIf { it.isFinite() && it > 0.0 }
                        val sourceFactor = draft.displayFactor ?: draft.unit.toBaseFactor
                        val targetFactor = QuantityConversionEngine.basePerUnit(
                            ingredient, selected, ingredient.unitConversions
                        ) ?: if (selected == draft.displayUnit) draft.displayFactor else null
                        val physicalBase = entered?.times(sourceFactor)
                        val converted = if (
                            physicalBase != null && physicalBase.isFinite() &&
                            physicalBase > 0.0 && targetFactor != null &&
                            targetFactor.isFinite() && targetFactor > 0.0
                        ) physicalBase / targetFactor else null

                        if (converted != null && converted.isFinite() &&
                            converted > 0.0 && converted <= 1e10
                        ) {
                            amountText = formatDisplayNumber(converted)
                            val profile = ingredient.unitConversions.singleOrNull {
                                it.unit == selected
                            }
                            onChange(
                                draft.copy(
                                    amount = converted,
                                    unit = selected.nativeUnit
                                        ?.takeIf { it.quantityType == ingredient.quantityType }
                                        ?: draft.unit,
                                    displayUnit = selected,
                                    displayFactor = targetFactor,
                                    displayApproximate = draft.displayApproximate ||
                                        profile?.approximate == true
                                )
                            )
                        }
                    }
                )
            }
            if (allowCrossDimension) {
                val factor = draft.displayFactor ?: draft.unit.toBaseFactor
                if (draft.displayUnit != null &&
                    draft.displayUnit.nativeUnit?.quantityType != ingredient.quantityType
                ) {
                    Text(
                        "1 ${selectedUnit.labelFor(1.0)} = ${formatDisplayNumber(factor)} " +
                            ingredient.quantityType.baseMeasurementUnit().symbol +
                            if (draft.displayApproximate) " (anslået)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun IngredientEditorScreen(
    state: MealPlannerState,
    ingredientId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val existing = ingredientId?.let { id ->
        state.ingredients.find { it.id == id }
    }
    if (ingredientId != null && existing == null) {
        // A deleted ingredient must not silently turn an Edit into New.
        LaunchedEffect(ingredientId) { onBack() }
        Text("Ingrediensen findes ikke længere.")
        return
    }

    var name by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.name ?: "")
    }

    var type by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.quantityType ?: QuantityType.MASS)
    }

    var preferredUnit by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.preferredUnit ?: MeasurementUnit.GRAM)
    }

    var isProtein by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.isProtein ?: false)
    }

    var neverBuy by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.neverBuy ?: false)
    }

    val conversionProfiles = rememberSaveable(ingredientId, saver = conversionProfileSaver) {
        mutableStateListOf<IngredientUnitConversion>().apply {
            addAll(existing?.unitConversions.orEmpty())
        }
    }
    var conversionUnit by rememberSaveable(ingredientId) { mutableStateOf(QuantityUnit.PACK) }
    var conversionFactorText by rememberSaveable(ingredientId) { mutableStateOf("") }
    var conversionEstimated by rememberSaveable(ingredientId) { mutableStateOf(false) }
    var showConversionForm by rememberSaveable(ingredientId) { mutableStateOf(false) }
    var editingConversionUnit by rememberSaveable(ingredientId) {
        mutableStateOf<QuantityUnit?>(null)
    }

    var shoppingCategoryId by rememberSaveable(ingredientId) {
        mutableStateOf(
            existing?.shoppingCategoryId
                ?: state.ingredientCategories
                    .firstOrNull { it.name.equals("Andet", ignoreCase = true) }
                    ?.id
                ?: state.ingredientCategories.firstOrNull()?.id
        )
    }

    var imageUri by rememberSaveable(ingredientId) {
        mutableStateOf(existing?.imageUri)
    }

    val ingredientNameAvailable =
        name.isBlank() ||
                state.isIngredientNameAvailable(
                    name,
                    excludingId = existing?.id
                )
    var showDeleteConfirm by rememberSaveable(ingredientId) {
        mutableStateOf(false)
    }

    val typeChangeAllowed = existing == null ||
            state.canChangeIngredientQuantityType(existing.id, type)
    val originalForm = remember(ingredientId) {
        listOf(name, type, preferredUnit, isProtein, neverBuy, shoppingCategoryId, imageUri,
            conversionProfiles.toList())
    }
    var changesSeen by rememberSaveable(ingredientId) { mutableStateOf(false) }
    val hasUnsavedChanges = changesSeen ||
            listOf(
                name,
                type,
                preferredUnit,
                isProtein,
                neverBuy,
                shoppingCategoryId,
                imageUri,
                conversionProfiles.toList()
            ) != originalForm
    androidx.compose.runtime.LaunchedEffect(hasUnsavedChanges) {
        if (hasUnsavedChanges) changesSeen = true
    }
    var showDiscardDialog by rememberSaveable(ingredientId) { mutableStateOf(false) }
    fun requestBack() {
        if (hasUnsavedChanges) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = hasUnsavedChanges) { requestBack() }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Kassér ændringer?") },
            text = { Text("Dine ændringer til ingrediensen er ikke gemt.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("Kassér ændringer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Fortsæt redigering")
                }
            }
        )
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
            },
            title = {
                Text("Slet ingrediens?")
            },
            text = {
                Text(
                    "Ingrediensen fjernes fra det fælles lager. Hvis den bruges i en opskrift, bliver den ikke slettet."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        if (
                            state.deleteIngredient(
                                existing.id
                            )
                        ) {
                            onSaved()
                        }
                    }
                ) {
                    Text(
                        "Slet",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    SubpageScaffold(
        title = if (existing == null) "Ny ingrediens" else "Rediger ingrediens",
        onBack = { requestBack() },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!typeChangeAllowed) {
                        Text(
                            "Typen kræver sikker konvertering af lager og opskrifter.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            val saved = if (existing == null) {
                                state.addIngredient(
                                    name = name,
                                    type = type,
                                    shoppingCategoryId = shoppingCategoryId,
                                    isProtein = isProtein,
                                    neverBuy = neverBuy,
                                    imageUri = imageUri,
                                    preferredUnit = preferredUnit,
                                    unitConversions = conversionProfiles.toList()
                                )
                            } else {
                                state.updateIngredient(
                                    id = existing.id,
                                    name = name,
                                    type = type,
                                    shoppingCategoryId = shoppingCategoryId,
                                    isProtein = isProtein,
                                    neverBuy = neverBuy,
                                    preferredUnit = preferredUnit,
                                    imageUri = imageUri,
                                    unitConversions = conversionProfiles.toList()
                                )
                            }

                            if (saved) {
                                onSaved()
                            }
                        },
                        enabled = name.isNotBlank() && ingredientNameAvailable && typeChangeAllowed,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.control
                    ) {
                        Text("Gem")
                    }

                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(AppLayout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppLayout.sectionSpacing)
        ) {
            item {
                ImagePickerBox(
                    imageUri = imageUri,
                    onImageSelected = { imageUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 150.dp,
                    label = "Vælg billede"
                )
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !ingredientNameAvailable,
                    supportingText = {
                        if (!ingredientNameAvailable) {
                            Text("Navnet findes allerede")
                        }
                    }
                )
            }

            item {
                Text(
                    "Type",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                SelectorChips(
                    items = QuantityType.entries,
                    selected = type,
                    label = { it.danishName() },
                    onSelected = {
                        type = it
                        preferredUnit = MeasurementUnit.defaultFor(it)
                        conversionProfiles.clear()
                    }
                )

            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Protein",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isProtein,
                        onCheckedChange = { isProtein = it }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Købes aldrig",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Vises i opskrifter, men ikke på indkøbslisten",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = neverBuy,
                        onCheckedChange = { neverBuy = it }
                    )
                }
            }

            item {
                Text(
                    "Foretrukken enhed",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                SelectorChips(
                    items = MeasurementUnit.compatibleWith(type),
                    selected = preferredUnit,
                    label = { it.selectorLabel() },
                    onSelected = { preferredUnit = it }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Omregninger",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!showConversionForm) {
                        TextButton(onClick = { showConversionForm = true }) {
                            Text("Tilføj")
                        }
                    }
                }

                run {
                    if (conversionProfiles.isEmpty() && !showConversionForm) {
                        Text(
                            ConversionPhrasing.example(type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    conversionProfiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ConversionPhrasing.describe(type, profile),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = {
                                editingConversionUnit = profile.unit
                                conversionUnit = profile.unit
                                conversionFactorText = formatDisplayNumber(
                                    ConversionPhrasing.shown(
                                        type, profile.unit, profile.baseAmountPerUnit
                                    )
                                )
                                conversionEstimated = profile.approximate
                                showConversionForm = true
                            }) { Text("Ret") }
                            TextButton(onClick = { conversionProfiles.remove(profile) }) {
                                Text("Fjern")
                            }
                        }
                    }
                    if (showConversionForm) {
                        val options = QuantityUnit.entries.filter { unit ->
                            unit.nativeUnit?.quantityType != type &&
                                (conversionProfiles.none { it.unit == unit } ||
                                    editingConversionUnit == unit)
                        }
                        if (options.isNotEmpty()) {
                            val candidate = if (conversionUnit in options) {
                                conversionUnit
                            } else options.first()
                            ConversionAmountRow(
                                type = type,
                                options = options,
                                unit = candidate,
                                unitLocked = editingConversionUnit != null,
                                onUnitChange = { conversionUnit = it },
                                amountText = conversionFactorText,
                                onAmountChange = { conversionFactorText = it }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Anslået", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = conversionEstimated,
                                    onCheckedChange = { conversionEstimated = it }
                                )
                            }
                            val factor = parseDanishDecimal(conversionFactorText)
                                ?.let { ConversionPhrasing.stored(type, candidate, it) }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (factor != null && factor.isFinite() &&
                                            factor > 0.0 && factor <= 1e9
                                        ) {
                                            val replacement = IngredientUnitConversion(
                                                unit = candidate,
                                                baseAmountPerUnit = factor,
                                                approximate = conversionEstimated,
                                                source = "household-confirmed"
                                            )
                                            val editing = editingConversionUnit
                                            if (editing == null) {
                                                conversionProfiles += replacement
                                            } else {
                                                val index = conversionProfiles.indexOfFirst {
                                                    it.unit == editing
                                                }
                                                if (index >= 0) conversionProfiles[index] = replacement
                                            }
                                            conversionFactorText = ""
                                            editingConversionUnit = null
                                            showConversionForm = false
                                        }
                                    },
                                    enabled = factor != null && factor.isFinite() &&
                                        factor > 0.0 && factor <= 1e9
                                ) { Text(if (editingConversionUnit == null) "Tilføj" else "Gem") }
                                TextButton(onClick = {
                                    showConversionForm = false
                                    editingConversionUnit = null
                                    conversionFactorText = ""
                                }) { Text("Annuller") }
                            }
                        } else {
                            Text(
                                "Alle enheder er tilføjet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { showConversionForm = false }) {
                                Text("Luk")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Indkøbskategori",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                if (state.ingredientCategories.isEmpty()) {
                    Text(
                        "Andet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SimpleSelector(
                        label = state.ingredientCategoryName(shoppingCategoryId),
                        options = state.sortedIngredientCategories,
                        optionText = { it.name },
                        onSelected = { shoppingCategoryId = it.id }
                    )
                }
            }

            if (existing != null) {
                item {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = true
                        }
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Slet ingrediens",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
