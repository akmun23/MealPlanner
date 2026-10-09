package com.example.mealplanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
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
import coil.request.ImageRequest
import io.github.jan.supabase.auth.handleDeeplinks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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

internal val DarkAppColors = darkColorScheme(
    // Use a neutral blue-slate foundation; distinguish the background,
    // elevated cards, dropdowns and disabled/inactive surfaces clearly.
    primary = Color(0xFFA9E9D2),
    onPrimary = Color(0xFF073B2C),
    primaryContainer = Color(0xFF2A5D50),
    onPrimaryContainer = Color(0xFFEDFFF6),
    secondary = Color(0xFFFFDB8A),
    onSecondary = Color(0xFF422D00),
    secondaryContainer = Color(0xFF635029),
    onSecondaryContainer = Color(0xFFFFF3D6),
    tertiary = Color(0xFFAAD6FF),
    onTertiary = Color(0xFF073453),
    tertiaryContainer = Color(0xFF31516B),
    onTertiaryContainer = Color(0xFFECF5FF),
    background = Color(0xFF10191E),
    onBackground = Color(0xFFF5FAFD),
    surface = Color(0xFF26363E),
    onSurface = Color(0xFFF5FAFD),
    surfaceVariant = Color(0xFF354B55),
    onSurfaceVariant = Color(0xFFD4E2E8),
    surfaceTint = Color(0xFFA9E9D2),
    inverseSurface = Color(0xFFE4F0F3),
    inverseOnSurface = Color(0xFF22343C),
    inversePrimary = Color(0xFF2F785F),
    outline = Color(0xFF93B3BE),
    outlineVariant = Color(0xFF698B99),
    scrim = Color.Black,
    surfaceBright = Color(0xFF415660),
    surfaceDim = Color(0xFF10191E),
    surfaceContainerLowest = Color(0xFF121E23),
    surfaceContainerLow = Color(0xFF1D2C33),
    surfaceContainer = Color(0xFF283B44),
    surfaceContainerHigh = Color(0xFF354B55),
    surfaceContainerHighest = Color(0xFF415963),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupabaseProvider.client.handleDeeplinks(intent)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            var themeMode by remember { mutableStateOf(themePreferences.load()) }
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            MaterialTheme(colorScheme = if (dark) DarkAppColors else AppColors) {
                Surface(modifier = Modifier.fillMaxSize().imePadding()) {
                    CloudGate(
                        themeMode = themeMode,
                        onThemeModeChange = { selected ->
                            themePreferences.save(selected)
                            themeMode = selected
                        }
                    )
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
    data object MealHistory : AppPage
    data object Settings : AppPage
    data object PlannerSettings : AppPage
    data object SyncStatus : AppPage
    data object DataBackup : AppPage
    data object IngredientCategories : AppPage
    data object RecipeCategories : AppPage
    data class IngredientEditor(val ingredientId: String?) : AppPage
    data class RecipeDetail(val recipeId: String) : AppPage
    data class RecipeEditor(
        val recipeId: String?,
        val initialMealType: MealType = MealType.DINNER
    ) : AppPage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealPlannerApp(
    household: HouseholdDto,
    onRenameHousehold: suspend (String) -> Boolean,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(household.id) {
        MealPlannerState(
            context = context.applicationContext,
            householdId = household.id,
            parentScope = scope
        )
    }

    LaunchedEffect(state) {
        try {
            awaitCancellation()
        } finally {
            // Composition cancellation must not skip suspending cleanup.
            withContext(NonCancellable) {
                state.close()
            }
        }
    }

    var tab by remember { mutableStateOf(AppTab.WEEK) }
    val pagerState = rememberPagerState(
        initialPage = tab.ordinal,
        pageCount = { AppTab.entries.size }
    )
    LaunchedEffect(pagerState.currentPage) {
        tab = AppTab.entries[pagerState.currentPage]
    }
    var page by remember { mutableStateOf<AppPage?>(null) }
    var showSyncConflictDialog by remember {
        mutableStateOf(false)
    }
    val pantryListState = rememberLazyListState()
    val tabStateHolder = rememberSaveableStateHolder()
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
            AppPage.MealHistory -> null
            AppPage.PlannerSettings -> AppPage.Settings
            AppPage.SyncStatus -> AppPage.Settings
            AppPage.DataBackup -> AppPage.Settings
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
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (state.syncConflictCount == 1) {
                            "En ændring findes også i en nyere fælles version. Intet overskrives automatisk."
                        } else {
                            "${state.syncConflictCount} ændringer findes også i nyere fælles versioner. Intet overskrives automatisk."
                        }
                    )
                    Button(
                        onClick = {
                            showSyncConflictDialog = false
                            state.useSharedConflictingChanges()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Behold fælles")
                    }
                    OutlinedButton(
                        onClick = {
                            showSyncConflictDialog = false
                            state.keepMyConflictingChanges()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Behold mine")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncConflictDialog = false
                    }
                ) {
                    Text("Senere")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = page) {
        AppPage.MealHistory -> MealHistoryScreen(
            state = state,
            onBack = { navigateBack() }
        )

        AppPage.Settings -> SettingsScreen(
            household = household,
            state = state,
            onBack = { navigateBack() },
            onRenameHousehold = onRenameHousehold,
            onIngredientCategories = { page = AppPage.IngredientCategories },
            onRecipeCategories = { page = AppPage.RecipeCategories },
            onPlannerSettings = { page = AppPage.PlannerSettings },
            onSyncStatus = { page = AppPage.SyncStatus },
            onDataBackup = { page = AppPage.DataBackup },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onLogout = onLogout
        )

        AppPage.PlannerSettings -> PlannerSettingsScreen(
            state = state,
            onBack = { navigateBack() }
        )

        AppPage.SyncStatus -> SyncStatusScreen(
            state = state,
            onBack = { navigateBack() }
        )

        AppPage.DataBackup -> DataBackupScreen(
            state = state,
            onBack = { navigateBack() }
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
                    // Give the editor the full height when the keyboard is open.
                    if (!WindowInsets.isImeVisible) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == tab,
                                onClick = {
                                    tab = item
                                    scope.launch {
                                        pagerState.scrollToPage(item.ordinal)
                                    }
                                },
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
                        HorizontalPager(
                            state = pagerState,
                            // Only compose the visible tab; other lists build on demand.
                            beyondViewportPageCount = 0,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            val visibleTab = AppTab.entries[pageIndex]
                            tabStateHolder.SaveableStateProvider(visibleTab.name) {
                            when (visibleTab) {
                                AppTab.WEEK -> WeekScreen(
                                    state = state,
                                    onMealHistory = { page = AppPage.MealHistory },
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
internal fun ScreenColumn(
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
private fun RecipeCategoriesScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecipeCategoryOption?>(null) }
    var categoryName by remember { mutableStateOf("") }
    val recipeCategoryNameAvailable =
        categoryName.isBlank() ||
            state.isRecipeCategoryNameAvailable(
                name = categoryName,
                excludingId = editing?.id
            )

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
                    singleLine = true,
                    isError = !recipeCategoryNameAvailable,
                    supportingText = {
                        if (!recipeCategoryNameAvailable) {
                            Text("Navnet findes allerede")
                        }
                    }
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
                    enabled =
                        categoryName.trim().isNotBlank() &&
                            recipeCategoryNameAvailable
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
            itemsIndexed(
                items = state.recipeCategories,
                key = { _, item -> item.id }
            ) { index, category ->
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
                            onClick = { state.moveRecipeCategory(category.id, -1) },
                            enabled = index > 0
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Flyt kategori op"
                            )
                        }
                        IconButton(
                            onClick = { state.moveRecipeCategory(category.id, 1) },
                            enabled = index < state.recipeCategories.lastIndex
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Flyt kategori ned"
                            )
                        }
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
                        IconButton(
                            onClick = {
                                state.deleteRecipeCategory(category.id)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Slet kategori",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientCategoriesScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<IngredientCategory?>(null) }
    var categoryName by remember { mutableStateOf("") }

    val ingredientCategoryNameAvailable =
        categoryName.isBlank() ||
            state.isIngredientCategoryNameAvailable(
                name = categoryName,
                excludingId = editing?.id
            )

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
                    singleLine = true,
                    isError = !ingredientCategoryNameAvailable,
                    supportingText = {
                        if (!ingredientCategoryNameAvailable) {
                            Text("Navnet findes allerede")
                        }
                    }
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
                    enabled =
                        categoryName.trim().isNotBlank() &&
                            ingredientCategoryNameAvailable
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
            itemsIndexed(
                items = state.ingredientCategories,
                key = { _, item -> item.id }
            ) { index, category ->
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
                            onClick = { state.moveIngredientCategory(category.id, -1) },
                            enabled = index > 0
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Flyt kategori op"
                            )
                        }
                        IconButton(
                            onClick = { state.moveIngredientCategory(category.id, 1) },
                            enabled = index < state.ingredientCategories.lastIndex
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Flyt kategori ned"
                            )
                        }
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
                        IconButton(
                            onClick = {
                                state.deleteIngredientCategory(category.id)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Slet kategori",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    household: HouseholdDto,
    state: MealPlannerState,
    onBack: () -> Unit,
    onRenameHousehold: suspend (String) -> Boolean,
    onIngredientCategories: () -> Unit,
    onRecipeCategories: () -> Unit,
    onPlannerSettings: () -> Unit,
    onSyncStatus: () -> Unit,
    onDataBackup: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFeedback by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var showRenameHome by remember { mutableStateOf(false) }
    var renameBusy by remember { mutableStateOf(false) }
    var feedbackBusy by remember { mutableStateOf(false) }
    var feedbackError by remember {
        mutableStateOf<String?>(null)
    }
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
                        renameBusy = true
                        scope.launch {
                            try {
                                val renamed =
                                    onRenameHousehold(
                                        homeName.trim()
                                    )
                                if (renamed) {
                                    showRenameHome = false
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Kunne ikke ændre navnet",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } finally {
                                renameBusy = false
                            }
                        }
                    },
                    enabled =
                        !renameBusy &&
                            homeName.trim().isNotBlank() &&
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
                    maxLines = 8,
                    isError = feedbackError != null,
                    supportingText = {
                        feedbackError?.let {
                            Text(it)
                        }
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        feedbackBusy = true
                        feedbackError = null
                        state.submitFeedback(feedback) { sent ->
                            feedbackBusy = false
                            if (sent) {
                                feedback = ""
                                feedbackError = null
                                showFeedback = false
                                Toast.makeText(
                                    context,
                                    "Forslag sendt",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                feedbackError =
                                    "Kunne ikke sende forslaget. Prøv igen senere."
                            }
                        }
                    },
                    enabled =
                        !feedbackBusy &&
                            feedback.trim().length >= 3
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
                OutlinedButton(
                    onClick = onPlannerSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Tilpas madplanens prioriteringer")
                }
            }

            item {
                OutlinedButton(
                    onClick = onSyncStatus,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Status for synkronisering")
                }
            }

            item {
                OutlinedButton(
                    onClick = onDataBackup,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Sikkerhedskopi og mine data")
                }
            }

            item {
                SectionCard("Udseende") {
                    Text(
                        "Vælg appens farvetema. System følger telefonens indstilling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { choice ->
                            FilterChip(
                                selected = themeMode == choice,
                                onClick = { onThemeModeChange(choice) },
                                label = { Text(choice.danishLabel) }
                            )
                        }
                    }
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
internal fun SubpageScaffold(
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
internal fun SectionCard(
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
internal fun EmptyCard(text: String) {
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
internal fun SearchField(
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

@Suppress("SameParameterValue")
@Composable
internal fun ImagePickerBox(
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
            MealImage(
                imageUri = imageUri,
                modifier = Modifier.fillMaxSize()
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
internal fun LargeImage(
    imageUri: String?,
    fallback: String
) {
    if (!imageUri.isNullOrBlank()) {
        MealImage(
            imageUri = imageUri,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(22.dp))
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
internal fun AppThumbnail(
    imageUri: String?,
    fallback: String,
    modifier: Modifier = Modifier
) {
    val boxSize = 60.dp
    val actualModifier = if (modifier == Modifier) Modifier.size(boxSize) else modifier

    if (!imageUri.isNullOrBlank()) {
        MealImage(
            imageUri = imageUri,
            modifier = actualModifier.clip(RoundedCornerShape(16.dp)),
            thumbnail = true,
            fallback = fallback
        )
    } else {
        Surface(
            modifier = actualModifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    fallback,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
internal fun <T> SelectorChips(
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
internal fun <T> SimpleSelector(
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

internal fun formatDisplayNumber(value: Double): String {
    if (kotlin.math.abs(value - value.toInt()) < 0.00001) {
        return value.toInt().toString()
    }

    return String.format(
        Locale.forLanguageTag("da-DK"),
        "%.2f",
        value
    ).trimEnd('0').trimEnd(',')
}
