package com.example.mealplanner

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.jan.supabase.auth.handleDeeplinks
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
        SupabaseProvider.client.handleDeeplinks(intent)

        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CloudGate()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SupabaseProvider.client.handleDeeplinks(intent)
    }
}

enum class AppTab(val label: String, val icon: ImageVector) {
    WEEK("Madplan", Icons.Filled.DateRange),
    RECIPES("Opskrifter", Icons.Filled.Restaurant),
    PANTRY("Lager", Icons.Filled.Inventory2),
    SHOPPING("Indkøb", Icons.Filled.ShoppingCart)
}

private sealed interface AppPage {
    data object Settings : AppPage
    data object IngredientCategories : AppPage
    data object RecipeCategories : AppPage
    data class IngredientEditor(val ingredientId: String?) : AppPage
    data class RecipeDetail(val recipeId: String) : AppPage
    data class RecipeEditor(
        val recipeId: String?,
        val initialMealType: MealType = MealType.DINNER
    ) : AppPage
}

@Composable
fun MealPlannerApp(
    household: HouseholdDto,
    onRenameHousehold: suspend (String) -> Boolean,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(household.id) {
        MealPlannerState(
            context = context.applicationContext,
            householdId = household.id,
            scope = scope
        )
    }

    LaunchedEffect(state) {
        try {
            awaitCancellation()
        } finally {
            state.close()
        }
    }

    var tab by remember { mutableStateOf(AppTab.WEEK) }
    var page by remember { mutableStateOf<AppPage?>(null) }
    var showSyncConflictDialog by remember {
        mutableStateOf(false)
    }
    val pantryListState = rememberLazyListState()
    val messageHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.lastError) {
        val message = state.lastError ?: return@LaunchedEffect
        state.clearError()
        messageHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Long
        )
    }

    fun navigateBack() {
        page = when (val current = page) {
            AppPage.Settings -> null
            AppPage.IngredientCategories -> AppPage.Settings
            AppPage.RecipeCategories -> AppPage.Settings
            is AppPage.IngredientEditor -> null
            is AppPage.RecipeDetail -> null
            is AppPage.RecipeEditor ->
                current.recipeId?.let { AppPage.RecipeDetail(it) }
            null -> null
        }
    }

    BackHandler(enabled = page != null) {
        navigateBack()
    }

    if (
        showSyncConflictDialog &&
        state.syncConflictCount > 0
    ) {
        AlertDialog(
            onDismissRequest = {
                showSyncConflictDialog = false
            },
            title = {
                Text("Synkroniseringskonflikt")
            },
            text = {
                Text(
                    if (state.syncConflictCount == 1) {
                        "En ændring findes også i en nyere fælles version. Intet overskrives automatisk. Du kan lukke og vælge senere."
                    } else {
                        "${state.syncConflictCount} ændringer findes også i nyere fælles versioner. Intet overskrives automatisk. Du kan lukke og vælge senere."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncConflictDialog = false
                        state.useSharedConflictingChanges()
                    }
                ) {
                    Text("Behold fælles")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSyncConflictDialog = false
                        state.keepMyConflictingChanges()
                    }
                ) {
                    Text("Behold mine")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = page) {
        AppPage.Settings -> SettingsScreen(
            household = household,
            state = state,
            onBack = { navigateBack() },
            onRenameHousehold = onRenameHousehold,
            onIngredientCategories = { page = AppPage.IngredientCategories },
            onRecipeCategories = { page = AppPage.RecipeCategories },
            onLogout = onLogout
        )

        AppPage.IngredientCategories -> IngredientCategoriesScreen(
            state = state,
            onBack = { navigateBack() }
        )

        AppPage.RecipeCategories -> RecipeCategoriesScreen(
            state = state,
            onBack = { navigateBack() }
        )

        is AppPage.IngredientEditor -> IngredientEditorScreen(
            state = state,
            ingredientId = current.ingredientId,
            onBack = { navigateBack() },
            onSaved = { page = null }
        )

        is AppPage.RecipeDetail -> RecipeDetailScreen(
            state = state,
            recipeId = current.recipeId,
            onBack = { navigateBack() },
            onEdit = { page = AppPage.RecipeEditor(current.recipeId) }
        )

        is AppPage.RecipeEditor -> RecipeEditorScreen(
            state = state,
            recipeId = current.recipeId,
            initialMealType = current.initialMealType,
            onBack = { navigateBack() },
            onSaved = { savedId ->
                page = savedId?.let { AppPage.RecipeDetail(it) }
            }
        )

        null -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    AppHeader(
                        onSettings = { page = AppPage.Settings }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == tab,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = item.label
                                    )
                                },
                                label = {
                                    Text(
                                        item.label,
                                        fontWeight = if (item == tab) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                    when (tab) {
                        AppTab.WEEK -> WeekScreen(
                            state = state,
                            onOpenRecipe = {
                                page = AppPage.RecipeDetail(it)
                            },
                            onNewLunchRecipe = {
                                page = AppPage.RecipeEditor(
                                    recipeId = null,
                                    initialMealType = MealType.LUNCH
                                )
                            }
                        )
                        AppTab.RECIPES -> RecipesScreen(
                            state = state,
                            onAddRecipe = { page = AppPage.RecipeEditor(null) },
                            onOpenRecipe = { page = AppPage.RecipeDetail(it) }
                        )
                        AppTab.PANTRY -> PantryScreen(
                            state = state,
                            listState = pantryListState,
                            onAddIngredient = {
                                page = AppPage.IngredientEditor(null)
                            },
                            onEditIngredient = {
                                page = AppPage.IngredientEditor(it)
                            }
                        )
                        AppTab.SHOPPING -> ShoppingScreen(state)
                    }
                    }
                }
            }
        }
        }

        SnackbarHost(
            hostState = messageHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    horizontal = 16.dp,
                    vertical = 126.dp
                )
        )

        SyncStatusBadge(
            indicator = state.syncIndicator,
            onRetry = state::retryPendingSync,
            onConflict = {
                showSyncConflictDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 86.dp)
        )
    }
}

@Composable
private fun SyncStatusBadge(
    indicator: SyncIndicator,
    onRetry: () -> Unit,
    onConflict: () -> Unit,
    modifier: Modifier = Modifier
) {
    val text = when (indicator) {
        SyncIndicator.IDLE -> null
        SyncIndicator.SAVING -> "Gemmer…"
        SyncIndicator.SAVED -> "Gemt"
        SyncIndicator.PENDING -> "Gemt lokalt · afventer synk."
        SyncIndicator.DELAYED -> "Synk. forsinket · Prøv igen"
        SyncIndicator.CONFLICT -> "Synk.-konflikt · Gennemse"
        SyncIndicator.ERROR -> "Kunne ikke gemme lokalt"
        SyncIndicator.OFFLINE -> "Offline"
    } ?: return

    val action = when (indicator) {
        SyncIndicator.DELAYED -> onRetry
        SyncIndicator.CONFLICT -> onConflict
        else -> null
    }

    Surface(
        modifier = modifier.then(
            action?.let { onClick ->
                Modifier.clickable(onClick = onClick)
            } ?: Modifier
        ),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 7.dp
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AppHeader(onSettings: () -> Unit) {
    Surface(shadowElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MealPlanner",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Indstillinger"
                )
            }
        }
    }
}

@Composable
private fun ScreenColumn(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        content = content
    )
}

@Composable
fun WeekScreen(
    state: MealPlannerState,
    onOpenRecipe: (String) -> Unit,
    onNewLunchRecipe: () -> Unit
) {
    val sandwichDay = state.sandwichDay()
    val homeCount = orderedWeekDays.count { state.dayModes[it] != DayMode.AWAY }
    val selectedLunch = state.lunchRecipes()
    val lunchOptions = state.recipes.filter { it.mealType == MealType.LUNCH }
    var showLunchPicker by remember { mutableStateOf(false) }
    var showSandwichPicker by remember { mutableStateOf(false) }

    if (showSandwichPicker) {
        val sandwichOptions = state.recipes.filter {
            it.mealType == MealType.DINNER
        }

        AlertDialog(
            onDismissRequest = { showSandwichPicker = false },
            title = { Text("Sandwich") },
            text = {
                if (sandwichOptions.isEmpty()) {
                    Text("Ingen aftensmadsopskrifter")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        items(
                            items = sandwichOptions,
                            key = { it.id }
                        ) { recipe ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        state.setWeeklySandwichRecipe(recipe.id)
                                        showSandwichPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    recipe.name,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSandwichPicker = false }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    if (showLunchPicker) {
        AlertDialog(
            onDismissRequest = { showLunchPicker = false },
            title = { Text("Frokost") },
            text = {
                if (lunchOptions.isEmpty()) {
                    Text("Ingen frokostopskrifter")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        items(
                            items = lunchOptions,
                            key = { it.id }
                        ) { recipe ->
                            val selected = recipe.id in state.selectedLunchRecipeIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selected) {
                                            state.removeLunchRecipe(recipe.id)
                                        } else {
                                            state.addLunchRecipe(recipe.id)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        if (selected) {
                                            state.removeLunchRecipe(recipe.id)
                                        } else {
                                            state.addLunchRecipe(recipe.id)
                                        }
                                    }
                                )
                                Text(
                                    recipe.name,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLunchPicker = false }) {
                    Text("Færdig")
                }
            }
        )
    }

    ScreenColumn {
        Text(
            "Madplan",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Hjemme", "$homeCount dage", Modifier.weight(1f))
            SummaryCard(
                "Sandwich",
                sandwichDay?.shortDanishName() ?: "–",
                Modifier.weight(1f),
                highlighted = true
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (state.weeklySandwichRecipeId != null) {
                TextButton(
                    onClick = { state.removeSandwichThisWeek() }
                ) {
                    Text("Ingen sandwich denne uge")
                }
            } else {
                TextButton(
                    onClick = { showSandwichPicker = true }
                ) {
                    Text("Tilføj sandwich")
                }
            }
        }

        SectionCard("Frokost") {
            selectedLunch.forEach { recipe ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        recipe.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { state.removeLunchRecipe(recipe.id) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = "Fjern frokost"
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showLunchPicker = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Vælg")
                }

                TextButton(
                    onClick = onNewLunchRecipe,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ny opskrift")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        orderedWeekDays.forEach { day ->
            DayCard(
                day = day,
                state = state,
                onOpenRecipe = onOpenRecipe
            )
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = { state.generateWeek() },
            enabled = state.recipes.any { it.mealType == MealType.DINNER } &&
                homeCount > 0 &&
                !state.isPlanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (state.isPlanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text("Ny madplan", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier,
    highlighted: Boolean = false
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun DayCard(
    day: DayOfWeek,
    state: MealPlannerState,
    onOpenRecipe: (String) -> Unit
) {
    val away = state.dayModes[day] == DayMode.AWAY
    val recipe = state.recipeFor(day)
    val isSandwich = recipe?.id == state.weeklySandwichRecipeId
    val isLocked = day in state.lockedDays
    val maxPrepMinutes = state.dayMaxPrepMinutes[day]

    var showRecipePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

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
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (away) {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ) {
                    Text(
                        day.shortDanishName(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (away) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        day.danishName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            away -> "Ikke hjemme"
                            recipe != null -> recipe.name
                            else -> "Ingen ret"
                        },
                        modifier = if (!away && recipe != null) {
                            Modifier.clickable {
                                onOpenRecipe(recipe.id)
                            }
                        } else {
                            Modifier
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!away && recipe != null) {
                    IconButton(
                        onClick = { state.toggleLockDay(day) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = if (isLocked) "Lås op" else "Lås ret",
                            tint = if (isLocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Switch(
                    checked = !away,
                    onCheckedChange = { state.toggleEatingAtHome(day) }
                )
            }

            if (!away) {
                if (!recipe?.imageUri.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(
                        model = recipe.imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                if (recipe != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSandwich) StatusPill("Ugens sandwich", true)
                        StatusPill(
                            state.recipeCategoryName(recipe),
                            false
                        )
                        recipe.prepMinutes?.let {
                            StatusPill("${it} min", false)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Box {
                    FilterChip(
                        selected = maxPrepMinutes != null,
                        onClick = { showTimePicker = true },
                        label = {
                            Text(prepLimitLabel(maxPrepMinutes))
                        }
                    )

                    DropdownMenu(
                        expanded = showTimePicker,
                        onDismissRequest = { showTimePicker = false }
                    ) {
                        listOf<Int?>(null, 20, 30, 45, 60).forEach { minutes ->
                            DropdownMenuItem(
                                text = {
                                    Text(prepLimitOptionLabel(minutes))
                                },
                                onClick = {
                                    state.setDayMaxPrepMinutes(day, minutes)
                                    showTimePicker = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (isSandwich) {
                    OutlinedButton(
                        onClick = { state.moveSandwich() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Flyt sandwich")
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showRecipePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Skift ret")
                            }

                            DropdownMenu(
                                expanded = showRecipePicker,
                                onDismissRequest = { showRecipePicker = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ingen ret") },
                                    onClick = {
                                        state.setRecipeForDay(day, null)
                                        showRecipePicker = false
                                    }
                                )

                                state.recipes
                                    .filter {
                                        it.mealType == MealType.DINNER &&
                                            it.id != state.weeklySandwichRecipeId
                                    }
                                    .forEach { candidate ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    buildString {
                                                        append(candidate.name)
                                                        candidate.prepMinutes?.let {
                                                            append(" · ${it} min")
                                                        }
                                                    }
                                                )
                                            },
                                            onClick = {
                                                state.setRecipeForDay(day, candidate.id)
                                                showRecipePicker = false
                                            }
                                        )
                                    }
                            }
                        }

                        if (!isLocked && recipe != null) {
                            IconButton(
                                onClick = { state.reroll(day) }
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Ny ret"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    highlighted: Boolean
) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
fun RecipesScreen(
    state: MealPlannerState,
    onAddRecipe: () -> Unit,
    onOpenRecipe: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var mealFilter by remember { mutableStateOf("Alle måltider") }
    var categoryFilter by remember { mutableStateOf("Alle kategorier") }
    var canMakeOnly by remember { mutableStateOf(false) }

    val mealOptions = listOf("Alle måltider") +
        MealType.entries.map { it.danishName() }
    val categoryOptions = listOf("Alle kategorier") +
        state.recipeCategories.map { it.name }

    val filteredRecipes by remember {
        derivedStateOf {
            state.recipes.filter { recipe ->
                val categoryName = state.recipeCategoryName(recipe)
                val proteinNames = state.recipeProteinNames(recipe)

                val matchesSearch =
                    searchQuery.isBlank() ||
                        recipe.name.contains(searchQuery, ignoreCase = true) ||
                        categoryName.contains(searchQuery, ignoreCase = true) ||
                        proteinNames.any {
                            it.contains(searchQuery, ignoreCase = true)
                        }

                val matchesMeal =
                    mealFilter == "Alle måltider" ||
                        recipe.mealType.danishName() == mealFilter

                val matchesCategory =
                    categoryFilter == "Alle kategorier" ||
                        categoryName == categoryFilter

                val matchesPantry =
                    !canMakeOnly || state.canMake(recipe)

                matchesSearch &&
                    matchesMeal &&
                    matchesCategory &&
                    matchesPantry
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
            }
        } else {
            items(
                items = filteredRecipes,
                key = { it.id }
            ) { recipe ->
                RecipeListCard(
                    recipe = recipe,
                    categoryName = state.recipeCategoryName(recipe),
                    proteinNames = state.recipeProteinNames(recipe),
                    isSandwich = state.weeklySandwichRecipeId == recipe.id,
                    onClick = { onOpenRecipe(recipe.id) }
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val meta = buildList {
                    add(recipe.mealType.danishName())
                    add(categoryName)
                    if (
                        recipe.mealType == MealType.DINNER &&
                        !recipe.autoRotationEnabled
                    ) {
                        add("Kun manuelt")
                    }
                    recipe.prepMinutes?.let { add("${it} min") }
                    if (proteinNames.isNotEmpty()) {
                        add(proteinNames.joinToString(" + "))
                    }
                }

                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isSandwich) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Ugens sandwich",
                        style = MaterialTheme.typography.labelSmall,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetailScreen(
    state: MealPlannerState,
    recipeId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val recipe = state.recipes.find { it.id == recipeId }
    var showConsumeConfirm by remember(recipeId) {
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
        AlertDialog(
            onDismissRequest = { showConsumeConfirm = false },
            title = { Text("Brug ingredienser") },
            text = {
                Text("Træk opskriftens mængder fra lageret?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.consumeRecipeIngredients(recipe.id)
                        showConsumeConfirm = false
                    }
                ) {
                    Text("Brug")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConsumeConfirm = false }
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
                Text(
                    buildList {
                        add(recipe.mealType.danishName())
                        add(state.recipeCategoryName(recipe))
                        if (
                            recipe.mealType == MealType.DINNER &&
                            !recipe.autoRotationEnabled
                        ) {
                            add("Kun manuelt")
                        }
                        recipe.prepMinutes?.let { add("${it} min") }
                        val proteinNames = state.recipeProteinNames(recipe)
                        if (proteinNames.isNotEmpty()) {
                            add(proteinNames.joinToString(" + "))
                        }
                    }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    sortedRecipeIngredients.forEach { requirement ->
                        val ingredient =
                            ingredientIndex[requirement.ingredientId]

                        if (ingredient != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    ingredient.name,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    state.formatBaseAmount(
                                        ingredient,
                                        requirement.amountBase
                                    ),
                                    fontWeight = FontWeight.SemiBold
                                )
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
                        state.deleteRecipe(recipe.id)
                        onBack()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeEditorScreen(
    state: MealPlannerState,
    recipeId: String?,
    initialMealType: MealType,
    onBack: () -> Unit,
    onSaved: (String?) -> Unit
) {
    val existing = recipeId?.let { id ->
        state.recipes.find { it.id == id }
    }

    var name by remember(recipeId) { mutableStateOf(existing?.name ?: "") }
    var mealType by remember(recipeId) {
        mutableStateOf(existing?.mealType ?: initialMealType)
    }
    var autoRotationEnabled by remember(recipeId) {
        mutableStateOf(existing?.autoRotationEnabled ?: true)
    }
    var prepMinutesText by remember(recipeId) {
        mutableStateOf(existing?.prepMinutes?.toString() ?: "")
    }
    val prepMinutes = prepMinutesText
        .trim()
        .takeIf { it.isNotBlank() }
        ?.toIntOrNull()
        ?.takeIf { it in 1..1440 }
    val prepTimeValid =
        prepMinutesText.isBlank() || prepMinutes != null
    var categoryId by remember(recipeId) {
        mutableStateOf(
            existing?.categoryId
                ?: state.recipeCategories
                    .firstOrNull { it.name.equals("Andet", ignoreCase = true) }
                    ?.id
                ?: state.recipeCategories.firstOrNull()?.id
        )
    }
    var notes by remember(recipeId) { mutableStateOf(existing?.notes ?: "") }
    var imageUri by remember(recipeId) { mutableStateOf(existing?.imageUri) }
    var ingredientSearch by remember { mutableStateOf("") }
    var prerequisiteSearch by remember { mutableStateOf("") }
    val recipeNameAvailable =
        name.isBlank() ||
            state.isRecipeNameAvailable(
                name,
                excludingId = existing?.id
            )

    val drafts = remember(recipeId) {
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

    val prerequisiteDrafts = remember(recipeId) {
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
                    }
                    .take(8),
                ingredientId = Ingredient::id,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
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
                    }
                    .take(8),
                ingredientId = Ingredient::id,
                ingredients = state.ingredients.toList(),
                categories = state.ingredientCategories.toList()
            )
        }
    }

    SubpageScaffold(
        title = if (existing == null) "Ny opskrift" else "Rediger opskrift",
        onBack = onBack
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
                                options = state.recipeCategories.toList(),
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
                                prerequisiteDrafts += DraftRequirement(
                                    ingredientId = ingredient.id,
                                    amount = 1.0,
                                    unit = ingredient.preferredUnit
                                )
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
                            drafts += DraftRequirement(
                                ingredientId = ingredient.id,
                                amount = 1.0,
                                unit = ingredient.preferredUnit
                            )
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
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Noter") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            item {
                Button(
                    onClick = {
                        if (existing == null) {
                            val savedId = state.addRecipe(
                                name = name,
                                mealType = mealType,
                                autoRotationEnabled = autoRotationEnabled,
                                prepMinutes = prepMinutes,
                                categoryId = categoryId,
                                notes = notes,
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
                    },
                    enabled = name.isNotBlank() &&
                        recipeNameAvailable &&
                        prepTimeValid &&
                        drafts.isNotEmpty() &&
                        drafts.all { it.amount > 0.0 } &&
                        prerequisiteDrafts.all { it.amount > 0.0 },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Gem")
                }
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

                        parseDanishDecimal(amountText)
                            ?.takeIf { it > 0.0 }
                            ?.let { amount ->
                                onChange(draft.copy(amount = amount))
                            }
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
                    onSelected = { unit ->
                        onChange(draft.copy(unit = unit))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientLibraryScreen(
    state: MealPlannerState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }

    val filtered = state.ingredients
        .filter {
            search.isBlank() ||
                it.name.contains(search, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }

    SubpageScaffold(
        title = "Ingredienser",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Button(
                    onClick = onAdd,
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
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "Søg i ingredienser"
                )
            }

            items(filtered, key = { it.id }) { ingredient ->
                IngredientListCard(
                    ingredient = ingredient,
                    onClick = { onOpen(ingredient.id) }
                )
            }
        }
    }
}

@Composable
private fun IngredientListCard(
    ingredient: Ingredient,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
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
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildList {
                        add(ingredient.quantityType.danishName())
                        add(ingredient.preferredUnit.selectorLabel())
                        if (ingredient.isProtein) add("Protein")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("›")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientEditorScreen(
    state: MealPlannerState,
    ingredientId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val existing = ingredientId?.let { id ->
        state.ingredients.find { it.id == id }
    }

    var name by remember(ingredientId) {
        mutableStateOf(existing?.name ?: "")
    }

    var type by remember(ingredientId) {
        mutableStateOf(existing?.quantityType ?: QuantityType.MASS)
    }

    var preferredUnit by remember(ingredientId) {
        mutableStateOf(existing?.preferredUnit ?: MeasurementUnit.GRAM)
    }

    var isProtein by remember(ingredientId) {
        mutableStateOf(existing?.isProtein ?: false)
    }

    var shoppingCategoryId by remember(ingredientId) {
        mutableStateOf(
            existing?.shoppingCategoryId
                ?: state.ingredientCategories
                    .firstOrNull { it.name.equals("Andet", ignoreCase = true) }
                    ?.id
                ?: state.ingredientCategories.firstOrNull()?.id
        )
    }

    var imageUri by remember(ingredientId) {
        mutableStateOf(existing?.imageUri)
    }

    val ingredientNameAvailable =
        name.isBlank() ||
            state.isIngredientNameAvailable(
                name,
                excludingId = existing?.id
            )

    SubpageScaffold(
        title = if (existing == null) "Ny ingrediens" else "Rediger ingrediens",
        onBack = onBack
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
                        options = state.ingredientCategories.toList(),
                        optionText = { it.name },
                        onSelected = { shoppingCategoryId = it.id }
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val saved = if (existing == null) {
                            state.addIngredient(
                                name = name,
                                type = type,
                                shoppingCategoryId = shoppingCategoryId,
                                isProtein = isProtein,
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
                                preferredUnit = preferredUnit,
                                imageUri = imageUri
                            )
                        }

                        if (saved) {
                            onSaved()
                        }
                    },
                    enabled = name.isNotBlank() && ingredientNameAvailable,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Gem")
                }
            }

            if (existing != null) {
                item {
                    TextButton(
                        onClick = {
                            state.deleteIngredient(existing.id)
                            onSaved()
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

@Composable
fun PantryScreen(
    state: MealPlannerState,
    listState: LazyListState,
    onAddIngredient: () -> Unit,
    onEditIngredient: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryId by remember {
        mutableStateOf<String?>(null)
    }
    var editing by remember { mutableStateOf<Ingredient?>(null) }

    editing?.let { ingredient ->
        PantryAmountDialog(
            ingredient = ingredient,
            state = state,
            onDismiss = { editing = null }
        )
    }

    val filtered = filterPantryIngredients(
        ingredients = state.ingredients.toList(),
        categories = state.ingredientCategories.toList(),
        searchQuery = searchQuery,
        selectedCategoryId = selectedCategoryId
    )

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
                        state.ingredientCategories.toList(),
                    optionText = { it?.name ?: "Alle kategorier" },
                    onSelected = { selectedCategoryId = it?.id }
                )
            }
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
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (amount > 0.0) {
                                state.formatBaseAmount(
                                    ingredient,
                                    amount
                                )
                            } else {
                                "Ikke registreret"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun PantryAmountDialog(
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

@Composable
fun ShoppingScreen(state: MealPlannerState) {
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
        AddPurchasedToPantryDialog(
            item = item,
            state = state,
            onDismiss = { addToPantryItem = null }
        )
    }

    val shoppingItems by remember {
        derivedStateOf { state.shoppingItems() }
    }
    val included = shoppingItems.filter {
        state.isShoppingIncluded(it.ingredient.id)
    }
    val checkedCount = included.count {
        state.shoppingChecked[it.ingredient.id] == true
    }

    val missing = included.filter {
        state.shoppingChecked[it.ingredient.id] != true
    }

    val bought = included.filter {
        state.shoppingChecked[it.ingredient.id] == true
    }

    val excluded = shoppingItems.filter {
        !state.isShoppingIncluded(it.ingredient.id)
    }

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
                val categories = state.ingredientCategories.toList()
                val knownIds = categories.map { it.id }.toSet()

                categories.forEach { category ->
                    val categoryItems = missing.filter {
                        it.ingredient.shoppingCategoryId == category.id
                    }

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
                                onEditPantry = {
                                    editPantryItem = item
                                }
                            )
                        }
                    }
                }

                val uncategorized = missing.filter {
                    it.ingredient.shoppingCategoryId !in knownIds
                }

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

                items(bought, key = { it.ingredient.id }) { item ->
                    ShoppingItemCard(
                        item = item,
                        state = state,
                        included = true,
                        checked = true,
                        onEditPantry = {
                            editPantryItem = item
                        },
                        onAddToPantry = {
                            addToPantryItem = item
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
                    items = excluded,
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
    onEditPantry: (() -> Unit)? = null,
    onAddToPantry: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
            modifier = Modifier.padding(10.dp)
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.ingredient.name,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (checked) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    }
                )

                Text(
                    "Behov: " +
                        state.formatBaseAmount(
                            item.ingredient,
                            item.requiredBase
                        ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    "Lager: " +
                        state.formatBaseAmount(
                            item.ingredient,
                            item.pantryBase
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FilterChip(
                    selected = included,
                    onClick = {
                        state.setShoppingIncluded(
                            ingredientId = item.ingredient.id,
                            included = !included
                        )
                    },
                    label = { Text("Køb") }
                )

                if (onEditPantry != null) {
                    TextButton(
                        onClick = onEditPantry,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Ret lager")
                    }
                }

                if (checked && onAddToPantry != null) {
                    TextButton(
                        onClick = onAddToPantry,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Til lager")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeCategoriesScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecipeCategoryOption?>(null) }
    var categoryName by remember { mutableStateOf("") }

    if (showAdd || editing != null) {
        AlertDialog(
            onDismissRequest = {
                showAdd = false
                editing = null
            },
            title = {
                Text(
                    if (editing == null) {
                        "Ny kategori"
                    } else {
                        "Omdøb kategori"
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = editing?.let {
                            state.renameRecipeCategory(
                                categoryId = it.id,
                                name = categoryName
                            )
                        } ?: state.addRecipeCategory(categoryName)

                        if (saved) {
                            showAdd = false
                            editing = null
                            categoryName = ""
                        }
                    },
                    enabled = categoryName.trim().isNotBlank()
                ) {
                    Text("Gem")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        editing = null
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    SubpageScaffold(
        title = "Opskriftskategorier",
        onBack = onBack,
        action = {
            IconButton(
                onClick = {
                    editing = null
                    categoryName = ""
                    showAdd = true
                }
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Ny kategori"
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.recipeCategories,
                key = { it.id }
            ) { category ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 14.dp,
                            vertical = 8.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            category.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = {
                                editing = category
                                categoryName = category.name
                            }
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Omdøb kategori"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientCategoriesScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<IngredientCategory?>(null) }
    var categoryName by remember { mutableStateOf("") }

    val activeDialog = showAdd || editing != null
    if (activeDialog) {
        AlertDialog(
            onDismissRequest = {
                showAdd = false
                editing = null
            },
            title = {
                Text(
                    if (editing == null) {
                        "Ny kategori"
                    } else {
                        "Omdøb kategori"
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = editing?.let {
                            state.renameIngredientCategory(
                                categoryId = it.id,
                                name = categoryName
                            )
                        } ?: state.addIngredientCategory(categoryName)

                        if (saved) {
                            showAdd = false
                            editing = null
                            categoryName = ""
                        }
                    },
                    enabled = categoryName.trim().isNotBlank()
                ) {
                    Text("Gem")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        editing = null
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    SubpageScaffold(
        title = "Indkøbskategorier",
        onBack = onBack,
        action = {
            IconButton(
                onClick = {
                    editing = null
                    categoryName = ""
                    showAdd = true
                }
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Ny kategori"
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.ingredientCategories,
                key = { it.id }
            ) { category ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 14.dp,
                            vertical = 8.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            category.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = {
                                editing = category
                                categoryName = category.name
                            }
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Omdøb kategori"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    household: HouseholdDto,
    state: MealPlannerState,
    onBack: () -> Unit,
    onRenameHousehold: suspend (String) -> Boolean,
    onIngredientCategories: () -> Unit,
    onRecipeCategories: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFeedback by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var showRenameHome by remember { mutableStateOf(false) }
    var homeName by remember(household.name) {
        mutableStateOf(household.name)
    }

    if (showRenameHome) {
        AlertDialog(
            onDismissRequest = { showRenameHome = false },
            title = { Text("Omdøb hjem") },
            text = {
                OutlinedTextField(
                    value = homeName,
                    onValueChange = { homeName = it },
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val renamed = onRenameHousehold(homeName.trim())
                            if (renamed) {
                                showRenameHome = false
                            } else {
                                Toast.makeText(
                                    context,
                                    "Kunne ikke ændre navnet",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = homeName.trim().isNotBlank() &&
                        homeName.trim() != household.name
                ) {
                    Text("Gem")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameHome = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    if (showFeedback) {
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            title = { Text("Send forslag") },
            text = {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it.take(2000) },
                    placeholder = { Text("Hvad kan blive bedre?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.submitFeedback(feedback) { sent ->
                            if (sent) {
                                feedback = ""
                                showFeedback = false
                                Toast.makeText(
                                    context,
                                    "Forslag sendt",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = feedback.trim().length >= 3
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFeedback = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    SubpageScaffold(
        title = "Indstillinger",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionCard("Husstand") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            household.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                homeName = household.name
                                showRenameHome = true
                            }
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Omdøb hjem"
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onIngredientCategories,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Indkøbskategorier")
                }
            }

            item {
                OutlinedButton(
                    onClick = onRecipeCategories,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Opskriftskategorier")
                }
            }

            item {
                SectionCard("Invitationskode") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            household.joinCode,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Invitationskode",
                                        household.joinCode
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    "Kopieret",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Kopiér")
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showFeedback = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Send forslag")
                }
            }

            item {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Log ud")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubpageScaffold(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage"
                        )
                    }
                },
                actions = {
                    action?.invoke()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        content = content
    )
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") }
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Ryd søgning"
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = true
    )
}

@Composable
private fun ImagePickerBox(
    imageUri: String?,
    onImageSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    label: String = "Vælg billede"
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onImageSelected(uri.toString())
        }
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { launcher.launch(arrayOf("image/*")) },
        contentAlignment = Alignment.Center
    ) {
        if (!imageUri.isNullOrBlank()) {
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
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
            ) {
                Text(
                    "Fjern",
                    color = Color.White
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LargeImage(
    imageUri: String?,
    fallback: String
) {
    if (!imageUri.isNullOrBlank()) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(22.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    fallback,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AppThumbnail(
    imageUri: String?,
    fallback: String
) {
    if (!imageUri.isNullOrBlank()) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    fallback,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun <T> SelectorChips(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    FilterChip(
                        selected = item == selected,
                        onClick = { onSelected(item) },
                        label = { Text(label(item)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SimpleSelector(
    label: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
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

private fun formatDisplayNumber(value: Double): String {
    if (kotlin.math.abs(value - value.toInt()) < 0.00001) {
        return value.toInt().toString()
    }

    return String.format(
        Locale.forLanguageTag("da-DK"),
        "%.2f",
        value
    ).trimEnd('0').trimEnd(',')
}
