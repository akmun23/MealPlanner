package com.example.mealplanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class RecipeListUi(
    val recipe: Recipe,
    val categoryName: String,
    val proteinNames: List<String>
)

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
        drafts.flatMap { listOf(it.ingredientId, it.amount.toString(), it.unit.name) }
    },
    restore = { stored ->
        mutableStateListOf<DraftRequirement>().apply {
            stored.chunked(3).filter { it.size == 3 }.forEach { parts ->
                val amount = parts[1].toDoubleOrNull()
                val unit = MeasurementUnit.entries.find { it.name == parts[2] }
                if (amount != null && unit != null) add(DraftRequirement(parts[0], amount, unit))
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

    val mealOptions = listOf("Alle måltider") +
        MealType.entries.map { it.danishName() }
    val categoryOptions = listOf("Alle kategorier") +
        state.recipeCategories.sortedBy { it.name.lowercase() }.map { it.name }

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

    val filteredRecipes by remember {
        derivedStateOf {
            val query = searchQuery.trim()
            recipeRows.filter { item ->
                val recipe = item.recipe
                (query.isBlank() ||
                    recipe.name.contains(query, ignoreCase = true) ||
                    item.categoryName.contains(query, ignoreCase = true) ||
                    recipeCategoryDisplayName(
                        item.categoryName, item.proteinNames
                    ).contains(query, ignoreCase = true) ||
                    recipeProteinLabel(item.proteinNames)
                        .contains(query, ignoreCase = true) ||
                    item.proteinNames.any {
                        it.contains(query, ignoreCase = true)
                    }) &&
                    (mealFilter == "Alle måltider" ||
                        recipe.mealType.danishName() == mealFilter) &&
                    (categoryFilter == "Alle kategorier" ||
                        item.categoryName == categoryFilter) &&
                    (!canMakeOnly || state.canMake(recipe))
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Opskrifter",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Button(
                onClick = onAddRecipe,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SimpleSelector(
                        label = mealFilter,
                        options = mealOptions,
                        optionText = { it },
                        onSelected = { mealFilter = it }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    SimpleSelector(
                        label = categoryFilter,
                        options = categoryOptions,
                        optionText = { it },
                        onSelected = { categoryFilter = it }
                    )
                }
            }
        }

        item {
            FilterChip(
                selected = canMakeOnly,
                onClick = { canMakeOnly = !canMakeOnly },
                label = { Text("Kan laves") }
            )
        }

        if (filteredRecipes.isEmpty()) {
            item {
                EmptyCard("Ingen opskrifter fundet.")
                if (searchQuery.isNotBlank() ||
                    mealFilter != "Alle måltider" ||
                    categoryFilter != "Alle kategorier" || canMakeOnly
                ) {
                    TextButton(onClick = {
                        searchQuery = ""
                        mealFilter = "Alle måltider"
                        categoryFilter = "Alle kategorier"
                        canMakeOnly = false
                    }) { Text("Ryd filtre") }
                }
            }
        } else {
            items(
                items = filteredRecipes,
                key = { it.recipe.id }
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
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
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

            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            shape = RoundedCornerShape(12.dp),
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
                                            ingredientSelection[ingredient.id] = Pair(checked, amountStr)
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ingredient.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Krav: ${state.formatBaseAmount(ingredient, req.minimumAmountBase)} · Lager: ${state.formatBaseAmount(ingredient, availableBase)}",
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
                                        Text("Træk: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        OutlinedTextField(
                                            value = amountStr,
                                            onValueChange = { newStr ->
                                                val clean = newStr.replace(',', '.')
                                                ingredientSelection[ingredient.id] = Pair(true, clean)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                            singleLine = true
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(unit.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
        onBack()
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        shape = RoundedCornerShape(14.dp)
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
                        shape = RoundedCornerShape(14.dp),
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
                                            state.formatBaseAmount(
                                                ingredient,
                                                requirement.amountBase
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
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Brug fra lager")
                }
            }

            if (recipe.instructionSteps.isNotEmpty()) {
                item {
                    SectionCard("Fremgangsmåde") {
                        RecipeInstructionRules.numbered(recipe.instructionSteps).forEach { (label, step) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.width(48.dp),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(step.text, modifier = Modifier.weight(1f))
                            }
                        }
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

@Composable
internal fun RecipeEditorScreen(
    state: MealPlannerState,
    recipeId: String?,
    initialMealType: MealType,
    onBack: () -> Unit,
    onSaved: (String?) -> Unit
) {
    val existing = recipeId?.let { id ->
        state.recipes.find { it.id == id }
    }

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

    val drafts = rememberSaveable(recipeId, saver = recipeDraftSaver) {
        mutableStateListOf<DraftRequirement>().apply {
            existing?.ingredients?.forEach { requirement ->
                val ingredient = state.ingredients.find {
                    it.id == requirement.ingredientId
                }

                if (ingredient != null) {
                    add(
                        DraftRequirement(
                            ingredientId = ingredient.id,
                            amount = ingredient.preferredUnit.fromBase(
                                requirement.amountBase
                            ),
                            unit = ingredient.preferredUnit
                        )
                    )
                }
            }
        }
    }

    val prerequisiteDrafts = rememberSaveable(recipeId, saver = recipeDraftSaver) {
        mutableStateListOf<DraftRequirement>().apply {
            existing?.pantryPrerequisites?.forEach { prerequisite ->
                val ingredient = state.ingredients.find {
                    it.id == prerequisite.ingredientId
                }

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
        notes, sourceUrl, imageUri, instructionSteps.toList(),
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
                        if (existing == null) {
                            val savedId = state.addRecipe(
                                name = name,
                                mealType = mealType,
                                autoRotationEnabled = autoRotationEnabled,
                                prepMinutes = prepMinutes,
                                categoryId = categoryId,
                                notes = notes,
                                instructionSteps = instructionSteps.toList(),
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
                                instructionSteps = instructionSteps.toList(),
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
                            if (saved) {
                                onSaved(existing.id)
                            }
                        }
    }

    SubpageScaffold(
        title = if (existing == null) "Ny opskrift" else "Rediger opskrift",
        onBack = { requestBack() },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
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
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Gem opskrift") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            onSelected = { mealType = it }
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
                                options = state.recipeCategories.sortedBy { it.name.lowercase() },
                                optionText = { it.name },
                                onSelected = { categoryId = it.id }
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

            item {
                SectionCard("Fremgangsmåde · ${instructionSteps.size} trin") {
                    RecipeInstructionRules.numbered(instructionSteps.toList())
                        .forEachIndexed { index, entry ->
                            val (label, step) = entry
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.width(42.dp).padding(top = 12.dp),
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = step.text,
                                    onValueChange = { value ->
                                        instructionSteps[index] = step.copy(text = value)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = {
                                        Text(if (step.level == 1) "Undertrin" else "Trin")
                                    },
                                    minLines = 2
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    val changed = RecipeInstructionRules.move(
                                        instructionSteps.toList(), index, -1
                                    )
                                    instructionSteps.clear()
                                    instructionSteps.addAll(changed)
                                }) { Text("Op") }
                                TextButton(onClick = {
                                    val changed = RecipeInstructionRules.move(
                                        instructionSteps.toList(), index, 1
                                    )
                                    instructionSteps.clear()
                                    instructionSteps.addAll(changed)
                                }) { Text("Ned") }
                                TextButton(onClick = {
                                    val changed = RecipeInstructionRules.remove(
                                        instructionSteps.toList(), index
                                    )
                                    instructionSteps.clear()
                                    instructionSteps.addAll(changed)
                                }) { Text("Fjern") }
                            }
                        }
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
                    val ingredient = state.ingredients.find {
                        it.id == draft.ingredientId
                    }

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
                        shape = RoundedCornerShape(14.dp)
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
                Text(
                    "Ingredienser",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                items = sortedDrafts,
                key = { it.value.ingredientId }
            ) { indexed ->
                val draft = indexed.value
                val ingredient = state.ingredients.find {
                    it.id == draft.ingredientId
                }

                if (ingredient != null) {
                    RequirementEditor(
                        ingredient = ingredient,
                        draft = draft,
                        onChange = {
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
                    shape = RoundedCornerShape(14.dp)
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
    onRemove: () -> Unit
) {
    var amountText by remember(draft.ingredientId) {
        mutableStateOf(formatDisplayNumber(draft.amount))
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
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
                        amountText = value
                            .filter { it.isDigit() || it == ',' || it == '.' }

                        val parsed = parseDanishDecimal(amountText)
                            ?.takeIf { it.isFinite() && it > 0.0 }
                        // Never silently persist the last valid amount if
                        // the visible field is currently empty or invalid.
                        onChange(draft.copy(amount = parsed ?: 0.0))
                    },
                    label = { Text("Mængde") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                SimpleSelector(
                    label = draft.unit.selectorLabel(),
                    options = MeasurementUnit.compatibleWith(
                        ingredient.quantityType
                    ),
                    optionText = { it.selectorLabel() },
                    onSelected = { selected ->
                        val entered = parseDanishDecimal(amountText)
                            ?.takeIf { it.isFinite() && it > 0.0 }
                        val converted = entered?.let {
                            draft.unit.convertAmountTo(it, selected)
                        }
                        if (converted != null) {
                            amountText = formatDisplayNumber(converted)
                        }
                        onChange(draft.copy(
                            unit = selected,
                            amount = converted ?: 0.0
                        ))
                    }
                )
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
        listOf(name, type, preferredUnit, isProtein, neverBuy, shoppingCategoryId, imageUri)
    }
    var changesSeen by rememberSaveable(ingredientId) { mutableStateOf(false) }
    val hasUnsavedChanges = changesSeen ||
        listOf(name, type, preferredUnit, isProtein, neverBuy, shoppingCategoryId, imageUri) != originalForm
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
                            "Typen kan ikke ændres, mens ingrediensen er på lager eller bruges i opskrifter.",
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
                                preferredUnit = preferredUnit
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
                                imageUri = imageUri
                            )
                        }

                        if (saved) {
                            onSaved()
                        }
                    },
                    enabled = name.isNotBlank() && ingredientNameAvailable && typeChangeAllowed,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    }
                )
                if (!typeChangeAllowed) {
                    Text(
                        "Fjern beholdningen og opskriftsreferencerne før typen ændres.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
                        options = state.ingredientCategories.sortedBy { it.name.lowercase() },
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
