package com.example.mealplanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.DayOfWeek
import java.util.Locale

private val AppColors = lightColorScheme(
    primary = Color(0xFF2F6B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBDD),
    onPrimaryContainer = Color(0xFF123322),
    secondary = Color(0xFF6A5F32),
    secondaryContainer = Color(0xFFF1E7B9),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EEE9),
    onSurfaceVariant = Color(0xFF4E5750),
    outline = Color(0xFFBBC4BC)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MealPlannerApp()
                }
            }
        }
    }
}

enum class AppTab(val label: String, val short: String) {
    WEEK("Madplan", "U"),
    RECIPES("Opskrifter", "O"),
    PANTRY("Lager", "L"),
    SHOPPING("Indkøb", "I")
}

@Composable
fun MealPlannerApp() {
    val context = LocalContext.current
    val state = remember { MealPlannerState(context.applicationContext) }
    var tab by remember { mutableStateOf(AppTab.WEEK) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(shadowElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Madplan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "En enkel uge, mindre planlægning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = {
                            Text(
                                item.short,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (item == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                AppTab.WEEK -> WeekScreen(state)
                AppTab.RECIPES -> RecipesScreen(state)
                AppTab.PANTRY -> PantryScreen(state)
                AppTab.SHOPPING -> ShoppingScreen(state)
            }
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        content = content
    )
}

@Composable
fun WeekScreen(state: MealPlannerState) {
    val sandwichDay = state.sandwichDay()
    val homeCount = orderedWeekDays.count { state.dayModes[it] != DayMode.AWAY }

    ScreenColumn {
        Text("Ugens madplan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Vælg hvilke dage I spiser hjemme. Sandwich placeres på fredag, hvis I er hjemme – ellers flyttes den automatisk.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Hjemme", "$homeCount dage", Modifier.weight(1f))
            SummaryCard(
                "Sandwich",
                sandwichDay?.danishName() ?: "Ikke placeret",
                Modifier.weight(1f),
                highlighted = true
            )
        }

        Spacer(Modifier.height(18.dp))

        if (state.weeklySandwichRecipeId == null) {
            NoticeCard("Vælg en opskrift som ugens faste sandwich under Opskrifter.")
            Spacer(Modifier.height(12.dp))
        }

        orderedWeekDays.forEach { day ->
            DayCard(day, state)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { state.generateWeek() },
            enabled = state.recipes.isNotEmpty() && state.weeklySandwichRecipeId != null && homeCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Lav ny madplan", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier, highlighted: Boolean = false) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun DayCard(day: DayOfWeek, state: MealPlannerState) {
    val away = state.dayModes[day] == DayMode.AWAY
    val recipe = state.recipeFor(day)
    val isSandwich = recipe?.id == state.weeklySandwichRecipeId

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                away -> MaterialTheme.colorScheme.surfaceVariant
                isSandwich -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (away) 0.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!away && !recipe?.imageUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = recipe.imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(day.danishName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            away -> "Spiser ikke hjemme"
                            recipe != null -> recipe.name
                            else -> "Ingen ret valgt endnu"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (away) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Hjemme", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = !away,
                        onCheckedChange = { state.toggleEatingAtHome(day) }
                    )
                }
            }

            if (!away && recipe != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (isSandwich) StatusPill("Ugens sandwich", true)
                    if (recipe.proteinTag.isNotBlank()) StatusPill(recipe.proteinTag, false)
                    if (recipe.categoryTag.isNotBlank() && !isSandwich) StatusPill(recipe.categoryTag, false)
                }

                Spacer(Modifier.height(10.dp))
                if (isSandwich) {
                    OutlinedButton(
                        onClick = { state.moveSandwich() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Flyt sandwich til en anden dag")
                    }
                } else {
                    OutlinedButton(
                        onClick = { state.reroll(day) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Rul denne ret igen")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, accent: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ImagePickerBox(
    imageUri: String?,
    onImageSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    label: String = "Vælg billede"
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onImageSelected(uri.toString())
        }
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { launcher.launch("image/*") },
        contentAlignment = Alignment.Center
    ) {
        if (!imageUri.isNullOrEmpty()) {
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            TextButton(
                onClick = { onImageSelected(null) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Text("Fjern", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📷 $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tryk for at vælge et foto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RecipesScreen(state: MealPlannerState) {
    var recipeName by remember { mutableStateOf("") }
    var proteinTag by remember { mutableStateOf("") }
    var categoryTag by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var recipeImageUri by remember { mutableStateOf<String?>(null) }

    var selectedIngredientId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(MeasurementUnit.GRAM) }
    val draftRequirements = remember { mutableStateListOf<DraftRequirement>() }

    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }

    if (editingRecipe != null) {
        EditRecipeDialog(
            recipe = editingRecipe!!,
            state = state,
            onDismiss = { editingRecipe = null }
        )
    }

    ScreenColumn {
        Text("Opskrifter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Byg jeres samling af retter. Ingredienser oprettes og styres under fanen Lager.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        SectionCard("Ny Opskrift") {
            ImagePickerBox(
                imageUri = recipeImageUri,
                onImageSelected = { recipeImageUri = it },
                modifier = Modifier.fillMaxWidth(),
                height = 130.dp,
                label = "Billede af retten"
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = recipeName,
                onValueChange = { recipeName = it },
                label = { Text("Navn på ret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proteinTag,
                    onValueChange = { proteinTag = it },
                    label = { Text("Protein (fx kylling)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = categoryTag,
                    onValueChange = { categoryTag = it },
                    label = { Text("Kategori (fx pasta)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Noter / tilberedning") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            if (state.ingredients.isEmpty()) {
                Text("Opret mindst én ingrediens under fanen Lager først.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val selectedIngredient = state.ingredients.find { it.id == selectedIngredientId } ?: state.ingredients.first()
                if (selectedIngredientId == null) {
                    selectedIngredientId = selectedIngredient.id
                    selectedUnit = selectedIngredient.preferredUnit
                }

                Text("Tilføj ingrediens til opskrift", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SimpleSelector(
                    label = selectedIngredient.name,
                    options = state.ingredients.toList(),
                    optionText = { it.name },
                    onSelected = { ingredient ->
                        selectedIngredientId = ingredient.id
                        selectedUnit = ingredient.preferredUnit
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.replace(',', '.') },
                        label = { Text("Mængde") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    SimpleSelector(
                        label = selectedUnit.selectorLabel(),
                        options = MeasurementUnit.compatibleWith(selectedIngredient.quantityType),
                        optionText = { it.selectorLabel() },
                        onSelected = { selectedUnit = it }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val amount = amountText.toDoubleOrNull()
                        if (amount != null && amount > 0.0) {
                            draftRequirements += DraftRequirement(selectedIngredient.id, amount, selectedUnit)
                            amountText = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Tilføj til opskriften") }

                if (draftRequirements.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    draftRequirements.forEachIndexed { index, draft ->
                        val ingredient = state.ingredients.find { it.id == draft.ingredientId }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "• ${ingredient?.name ?: "Ukendt"}: ${state.formatEnteredAmount(draft.amount, draft.unit)}",
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { draftRequirements.removeAt(index) }) { Text("Fjern") }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        state.addRecipe(recipeName, proteinTag, categoryTag, notes, recipeImageUri, draftRequirements.toList())
                        recipeName = ""
                        proteinTag = ""
                        categoryTag = ""
                        notes = ""
                        recipeImageUri = null
                        draftRequirements.clear()
                    },
                    enabled = recipeName.isNotBlank() && draftRequirements.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Gem Opskrift", fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Jeres Opskrifter (${state.recipes.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        if (state.recipes.isEmpty()) {
            Text("Ingen opskrifter endnu. Opret den første ovenfor!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.recipes.forEach { recipe ->
                RecipeCard(
                    recipe = recipe,
                    state = state,
                    onEdit = { editingRecipe = recipe }
                )
                Spacer(Modifier.height(14.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun RecipeCard(
    recipe: Recipe,
    state: MealPlannerState,
    onEdit: () -> Unit
) {
    val isSandwich = state.weeklySandwichRecipeId == recipe.id
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSandwich) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            if (!recipe.imageUri.isNullOrEmpty()) {
                AsyncImage(
                    model = recipe.imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentScale = ContentScale.Crop
                )
            }

            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        recipe.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSandwich) StatusPill("Ugens sandwich", true)
                }

                if (recipe.proteinTag.isNotBlank() || recipe.categoryTag.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(recipe.proteinTag, recipe.categoryTag).filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (recipe.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        recipe.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Ingredienser:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                recipe.ingredients.forEach { req ->
                    val ingredient = state.ingredients.find { it.id == req.ingredientId }
                    if (ingredient != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            if (!ingredient.imageUri.isNullOrEmpty()) {
                                AsyncImage(
                                    model = ingredient.imageUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("• ${ingredient.name}: ${state.formatBaseAmount(ingredient, req.amountBase)}")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isSandwich) {
                        OutlinedButton(
                            onClick = { state.setWeeklySandwichRecipe(recipe.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Faste sandwich") }
                    }
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Redigér") }
                    TextButton(onClick = { state.deleteRecipe(recipe.id) }) {
                        Text("Slet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun EditRecipeDialog(
    recipe: Recipe,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    var recipeName by remember { mutableStateOf(recipe.name) }
    var proteinTag by remember { mutableStateOf(recipe.proteinTag) }
    var categoryTag by remember { mutableStateOf(recipe.categoryTag) }
    var notes by remember { mutableStateOf(recipe.notes) }
    var imageUri by remember { mutableStateOf(recipe.imageUri) }

    var selectedIngredientId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(MeasurementUnit.GRAM) }

    val draftRequirements = remember {
        mutableStateListOf<DraftRequirement>().apply {
            recipe.ingredients.forEach { req ->
                val ing = state.ingredients.find { it.id == req.ingredientId }
                if (ing != null) {
                    val unit = ing.preferredUnit
                    val amount = unit.fromBase(req.amountBase)
                    add(DraftRequirement(req.ingredientId, amount, unit))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Redigér Opskrift") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImagePickerBox(
                    imageUri = imageUri,
                    onImageSelected = { imageUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 120.dp,
                    label = "Opskriftsbillede"
                )

                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    label = { Text("Navn på ret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = proteinTag,
                    onValueChange = { proteinTag = it },
                    label = { Text("Protein (fx kylling)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = categoryTag,
                    onValueChange = { categoryTag = it },
                    label = { Text("Kategori (fx pasta)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Noter / tilberedning") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text("Ingredienser i opskriften", fontWeight = FontWeight.Bold)

                if (state.ingredients.isNotEmpty()) {
                    val selectedIngredient = state.ingredients.find { it.id == selectedIngredientId } ?: state.ingredients.first()
                    if (selectedIngredientId == null) {
                        selectedIngredientId = selectedIngredient.id
                        selectedUnit = selectedIngredient.preferredUnit
                    }

                    SimpleSelector(
                        label = selectedIngredient.name,
                        options = state.ingredients.toList(),
                        optionText = { it.name },
                        onSelected = { ingredient ->
                            selectedIngredientId = ingredient.id
                            selectedUnit = ingredient.preferredUnit
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it.replace(',', '.') },
                            label = { Text("Mængde") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        SimpleSelector(
                            label = selectedUnit.selectorLabel(),
                            options = MeasurementUnit.compatibleWith(selectedIngredient.quantityType),
                            optionText = { it.selectorLabel() },
                            onSelected = { selectedUnit = it }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val amount = amountText.toDoubleOrNull()
                            if (amount != null && amount > 0.0) {
                                draftRequirements += DraftRequirement(selectedIngredient.id, amount, selectedUnit)
                                amountText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tilføj ingrediens") }
                }

                draftRequirements.forEachIndexed { index, draft ->
                    val ingredient = state.ingredients.find { it.id == draft.ingredientId }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "• ${ingredient?.name ?: "Ukendt"}: ${state.formatEnteredAmount(draft.amount, draft.unit)}",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { draftRequirements.removeAt(index) }) {
                            Text("Fjern")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    state.updateRecipe(
                        id = recipe.id,
                        name = recipeName,
                        protein = proteinTag,
                        category = categoryTag,
                        notes = notes,
                        imageUri = imageUri,
                        requirements = draftRequirements.toList()
                    )
                    onDismiss()
                },
                enabled = recipeName.isNotBlank() && draftRequirements.isNotEmpty()
            ) {
                Text("Gem ændringer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuller") }
        }
    )
}

@Composable
fun AddIngredientDialog(
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(QuantityType.MASS) }
    var imageUri by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opret Ny Ingrediens") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImagePickerBox(
                    imageUri = imageUri,
                    onImageSelected = { imageUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 110.dp,
                    label = "Billede af råvare"
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ingrediensnavn (fx Kyllingebryst)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                SimpleSelector(
                    label = "Måles som: ${type.danishName()}",
                    options = QuantityType.entries,
                    optionText = { it.danishName() },
                    onSelected = { type = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    state.addIngredient(name, type, imageUri)
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) {
                Text("Gem ingrediens")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuller") }
        }
    )
}

@Composable
fun EditIngredientDialog(
    ingredient: Ingredient,
    state: MealPlannerState,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(ingredient.name) }
    var type by remember { mutableStateOf(ingredient.quantityType) }
    var preferredUnit by remember { mutableStateOf(ingredient.preferredUnit) }
    var imageUri by remember { mutableStateOf(ingredient.imageUri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Redigér Ingrediens") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImagePickerBox(
                    imageUri = imageUri,
                    onImageSelected = { imageUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 110.dp,
                    label = "Foto af råvare"
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ingrediensnavn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                SimpleSelector(
                    label = "Måles som: ${type.danishName()}",
                    options = QuantityType.entries,
                    optionText = { it.danishName() },
                    onSelected = {
                        type = it
                        preferredUnit = MeasurementUnit.defaultFor(it)
                    }
                )

                SimpleSelector(
                    label = "Forestrukket enhed: ${preferredUnit.symbol}",
                    options = MeasurementUnit.compatibleWith(type),
                    optionText = { it.symbol },
                    onSelected = { preferredUnit = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    state.updateIngredient(
                        id = ingredient.id,
                        name = name,
                        type = type,
                        preferredUnit = preferredUnit,
                        imageUri = imageUri
                    )
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) {
                Text("Gem ændringer")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        state.deleteIngredient(ingredient.id)
                        onDismiss()
                    }
                ) {
                    Text("Slet", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Annuller") }
            }
        }
    )
}

@Composable
fun PantryScreen(state: MealPlannerState) {
    var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
    var showAddIngredientDialog by remember { mutableStateOf(false) }

    if (editingIngredient != null) {
        EditIngredientDialog(
            ingredient = editingIngredient!!,
            state = state,
            onDismiss = { editingIngredient = null }
        )
    }

    if (showAddIngredientDialog) {
        AddIngredientDialog(
            state = state,
            onDismiss = { showAddIngredientDialog = false }
        )
    }

    ScreenColumn {
        Text("Lager & Råvarer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Tryk på en ingrediens for at redigere den. Tilpas mængden på lageret direkte nedenfor.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { showAddIngredientDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("+ Opret Ny Ingrediens", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))

        if (state.ingredients.isEmpty()) {
            NoticeCard("Ingen ingredienser oprettet endnu. Tryk på knappen ovenfor for at oprette den første!")
        } else {
            Text("Ingredienser (${state.ingredients.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            state.ingredients
                .sortedBy { it.name.lowercase() }
                .forEach { ingredient ->
                    PantryIngredientItemCard(
                        ingredient = ingredient,
                        state = state,
                        onEdit = { editingIngredient = ingredient }
                    )
                    Spacer(Modifier.height(10.dp))
                }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PantryIngredientItemCard(
    ingredient: Ingredient,
    state: MealPlannerState,
    onEdit: () -> Unit
) {
    val currentBaseAmount = state.pantry[ingredient.id] ?: 0.0
    val currentUnit = ingredient.preferredUnit
    val currentVal = currentUnit.fromBase(currentBaseAmount)

    var amountText by remember(currentVal) {
        mutableStateOf(if (currentBaseAmount > 0.0) formatDisplayNumber(currentVal) else "")
    }
    var selectedUnit by remember(currentUnit) { mutableStateOf(currentUnit) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit() }
            ) {
                if (!ingredient.imageUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = ingredient.imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🥗", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ingredient.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("✏️", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "${ingredient.quantityType.danishName()} · Tryk for at redigere",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // INLINE PANTRY AMOUNT CONTROLLER
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "På lager:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { newText ->
                            amountText = newText.replace(',', '.')
                            val parsed = amountText.toDoubleOrNull()
                            if (parsed != null && parsed >= 0.0) {
                                state.setPantry(ingredient.id, parsed, selectedUnit)
                            } else if (newText.isEmpty()) {
                                state.setPantry(ingredient.id, 0.0, selectedUnit)
                            }
                        },
                        placeholder = { Text("0") },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        singleLine = true
                    )

                    Spacer(Modifier.width(8.dp))

                    SimpleSelector(
                        label = selectedUnit.symbol,
                        options = MeasurementUnit.compatibleWith(ingredient.quantityType),
                        optionText = { it.symbol },
                        onSelected = { unit ->
                            selectedUnit = unit
                            val parsed = amountText.toDoubleOrNull() ?: 0.0
                            state.setPantry(ingredient.id, parsed, unit)
                        }
                    )
                }
            }
        }
    }
}

private fun formatDisplayNumber(value: Double): String {
    if (kotlin.math.abs(value - value.toInt()) < 0.00001) {
        return value.toInt().toString()
    }
    return String.format(Locale("da", "DK"), "%.2f", value).trimEnd('0').trimEnd('.')
}

@Composable
fun ShoppingScreen(state: MealPlannerState) {
    val items = state.shoppingItems()
    val checkedCount = items.count { state.shoppingChecked[it.ingredient.id] == true }

    ScreenColumn {
        Text("Indkøb", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Hele behovet vises. Lageret står kun som information, så intet bliver glemt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (items.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text("$checkedCount af ${items.size} varer klaret", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (items.isEmpty()) 0f else checkedCount.toFloat() / items.size.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (items.isEmpty()) {
            NoticeCard("Lav først en madplan, så bliver indkøbslisten bygget automatisk.")
        } else {
            items.sortedBy { state.shoppingChecked[it.ingredient.id] == true }.forEach { item ->
                val checked = state.shoppingChecked[item.ingredient.id] ?: false
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (checked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { state.setShoppingChecked(item.ingredient.id, it) }
                        )
                        if (!item.ingredient.imageUri.isNullOrEmpty()) {
                            AsyncImage(
                                model = item.ingredient.imageUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.ingredient.name,
                                fontWeight = FontWeight.Bold,
                                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
                            )
                            Text("Behov: ${state.formatBaseAmount(item.ingredient, item.requiredBase)}")
                            if (item.pantryBase > 0.0) {
                                Text(
                                    "Har hjemme: ${state.formatBaseAmount(item.ingredient, item.pantryBase)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun <T> SimpleSelector(
    label: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp)) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}
