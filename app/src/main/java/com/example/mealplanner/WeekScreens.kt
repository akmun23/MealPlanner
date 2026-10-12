package com.example.mealplanner

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WeekScreen(
    state: MealPlannerState,
    onOpenRecipe: (String) -> Unit,
    onNewLunchRecipe: () -> Unit,
    onMealHistory: () -> Unit
) {
    val sandwichDay = state.sandwichDay()
    val homeCount = orderedWeekDays.count { state.dayModes[it] != DayMode.AWAY }
    val selectedLunch = state.lunchRecipes()
    val lunchOptions =
        state.recipes.filter { it.mealType == MealType.LUNCH }.sortedBy { it.name.lowercase() }
    // A dedicated drag handle leaves the rest of every day card scrollable.
    // The same action is also accessible via a tap-to-choose-day dialog.
    val weekListState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    // Layout positions are updated on every scroll frame: do not turn them
    // into Compose state or the whole week would recompose during scrolling.
    val listRootTop = remember { floatArrayOf(0f) }
    // Read inside DraggedDayPreview rather than recomposing the full week
    // for each pixel the finger moves.
    val dragPositionY = remember { mutableFloatStateOf(0f) }
    var draggingDay by remember { mutableStateOf<DayOfWeek?>(null) }
    var hoveredDay by remember { mutableStateOf<DayOfWeek?>(null) }
    val scrollJob = remember { arrayOfNulls<Job>(1) }

    fun hoverAt(y: Float): DayOfWeek? {
        val item = weekListState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key is String &&
                (it.key as String).startsWith("meal-day-") &&
                y >= it.offset && y < it.offset + it.size
        } ?: return null
        val name = (item.key as String).removePrefix("meal-day-")
        return DayOfWeek.entries.firstOrNull { it.name == name }
    }

    fun updateHover() {
        val origin = draggingDay
        val target = hoverAt(dragPositionY.floatValue)
        hoveredDay = if (
            origin != null && target != null &&
            state.canMoveDinner(origin, target)
        ) target else null
    }

    var showLunchPicker by remember { mutableStateOf(false) }
    var showSandwichPicker by remember { mutableStateOf(false) }
    var showGenerateConfirmation by remember { mutableStateOf(false) }
    var showWeekUnlockConfirmation by remember { mutableStateOf(false) }
    var finishingDinner by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    finishingDinner?.let { (date, recipeId) ->
        state.recipes.find { it.id == recipeId }?.let { meal ->
            FinishMealDialog(
                date = date,
                recipe = meal,
                state = state,
                onDismiss = { finishingDinner = null }
            )
        }
    }


    if (showWeekUnlockConfirmation) {
        AlertDialog(
            onDismissRequest = { showWeekUnlockConfirmation = false },
            title = { Text("Lås ugens genplanlægning op?") },
            text = {
                Text(
                    "Denne uge indeholder allerede spiste måltider. " +
                            "Genplanlægning kan ændre øvrige dage, men færdigregistrerede dage " +
                            "forbliver låst. Næste uge kan planlægges normalt."
                )
            },
            dismissButton = {
                TextButton(onClick = { showWeekUnlockConfirmation = false }) {
                    Text("Annuller")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWeekUnlockConfirmation = false
                    state.unlockWeekRegeneration()
                }) { Text("Lås op") }
            }
        )
    }

    if (showGenerateConfirmation) {
        AlertDialog(
            onDismissRequest = { showGenerateConfirmation = false },
            title = { Text("Erstat den nuværende madplan?") },
            text = {
                Text(
                    "En ny madplan erstatter retterne på dage, der ikke er låst. " +
                            "Dine markeringer på indkøbslisten bliver nulstillet. " +
                            "Låste retter bevares."
                )
            },
            dismissButton = {
                TextButton(onClick = { showGenerateConfirmation = false }) {
                    Text("Annuller")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGenerateConfirmation = false
                        state.generateWeek()
                    }
                ) {
                    Text("Lav ny madplan")
                }
            }
        )
    }

    if (showSandwichPicker) {
        val sandwichOptions = state.recipes.filter {
            it.mealType == MealType.DINNER
        }.sortedBy { it.name.lowercase() }

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

    // Keep date-dependent values outside lazy item lambdas so each overview
    // section can be measured and recycled independently when scrolling.
    val start = state.displayedWeekStart
    val end = start.plusDays(6)
    val dateFormat = DateTimeFormatter.ofPattern("d. MMM", Locale.forLanguageTag("da-DK"))
    val weekNumber = start.get(WeekFields.ISO.weekOfWeekBasedYear())
    val today = LocalDate.now()
    val todayRecipe = if (today in start..end &&
        state.dayModes[today.dayOfWeek] != DayMode.AWAY
    ) state.recipeFor(today.dayOfWeek) else null
    val todayEaten = state.history.find { it.eatenOn == today }

    // Day cards are expensive to measure (photos, menus, status chips).
    // Compose only visible overview sections and day cards, not a single
    // enormous summary item on every scroll frame.
    // Draw the scrolling list first, then the floating card above it.
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = weekListState,
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { listRootTop[0] = it.positionInRoot().y },
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "week-navigation", contentType = "week-section") {
            Column {
                Text(
                    "Madplan",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { state.changeDisplayedWeek(-1) },
                                enabled = !state.isLocalMode && !state.isWeekChanging
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "Forrige uge")
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        "Uge $weekNumber · ${start.get(WeekFields.ISO.weekBasedYear())}",
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 4.dp
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${start.format(dateFormat)} – ${end.format(dateFormat)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { state.changeDisplayedWeek(1) },
                                enabled = !state.isLocalMode && !state.isWeekChanging
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "Næste uge")
                            }
                        }

                        if (start != LocalDate.now()
                                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) &&
                            !state.isLocalMode
                        ) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { state.showCurrentWeek() },
                                enabled = !state.isWeekChanging,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Today,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Tilbage til denne uge")
                            }
                        }
                    }
                }
            }
        }

        if (today in start..end) {
            item(key = "week-today", contentType = "week-section") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Today,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "I dag · Aftensmad",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            TextButton(onClick = onMealHistory) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Historik")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            todayEaten?.recipeId?.let { eaten ->
                                state.recipes.find { it.id == eaten }?.name
                            } ?: todayRecipe?.name ?: "Ingen ret planlagt",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (todayEaten == null) Spacer(Modifier.height(10.dp))
                        if (todayEaten == null && todayRecipe != null) {
                            Button(
                                onClick = { finishingDinner = today to todayRecipe.id },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Færdig – registrér dagens ret")
                            }
                        } else if (todayEaten == null) {
                            TextButton(onClick = onMealHistory) {
                                Text("Registrér en anden ret")
                            }
                        }
                    }
                }
            }
        }

        item(key = "week-generate", contentType = "week-section") {
            Column {
                Button(
                    onClick = {
                        if (WeekRules.needsRegenerationConfirmation(
                                currentPlan = state.plan,
                                sandwichRecipeId = state.weeklySandwichRecipeId
                            )
                        ) {
                            showGenerateConfirmation = true
                        } else {
                            state.generateWeek()
                        }
                    },
                    enabled = state.recipes.any { it.mealType == MealType.DINNER } &&
                            homeCount > 0 &&
                            !state.isPlanning &&
                            !state.isWeekChanging &&
                            !state.isWeekRegenerationLocked(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
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
                if (state.isWeekRegenerationLocked()) {
                    Text(
                        "Ugen er låst efter registrering af spiste måltider. " +
                                "Brug pilen til næste uge for at planlægge videre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showWeekUnlockConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Lås ugens genplanlægning op")
                    }
                }
                if (state.recipes.none { it.mealType == MealType.DINNER }) {
                    Text(
                        "Tilføj mindst én aftensmadsopskrift for at generere en madplan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (homeCount == 0) {
                    Text(
                        "Vælg mindst én dag, hvor du spiser hjemme.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.isWeekChanging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        item(key = "week-stats", contentType = "week-section") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard("Hjemme", "$homeCount dage", Modifier.weight(1f))
                    SummaryCard(
                        "Sandwich",
                        sandwichDay?.shortDanishName() ?: "–",
                        Modifier.weight(1f),
                        highlighted = true
                    )
                }
                if (state.weeklySandwichRecipeId == null ||
                    sandwichDay?.let(state::isCompletedDayLocked) != true
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                if (state.weeklySandwichRecipeId != null)
                                    state.removeSandwichThisWeek()
                                else showSandwichPicker = true
                            }
                        ) {
                            Text(
                                if (state.weeklySandwichRecipeId != null) "Fjern sandwich"
                                else "Tilføj sandwich"
                            )
                        }
                    }
                }
            }
        }

        item(key = "week-lunch", contentType = "week-section") {
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
        }

        item(key = "week-drag-help", contentType = "week-section") {
            Text(
                "Hold på flyttehåndtaget ved en ret, og træk den til en anden dag. " +
                    "Du kan også trykke på håndtaget for at vælge dagen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = orderedWeekDays,
            key = { "meal-day-${it.name}" },
            contentType = { "day-card" }
        ) { day ->
            DayCard(
                day = day,
                state = state,
                dropTarget = hoveredDay == day,
                dragging = draggingDay == day,
                onStartDrag = { source, rootY ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    draggingDay = source
                    dragPositionY.floatValue = rootY - listRootTop[0]
                    hoveredDay = null
                },
                onDrag = { deltaY ->
                    dragPositionY.floatValue += deltaY
                    updateHover()
                    val end = weekListState.layoutInfo.viewportEndOffset
                    val amount = when {
                        dragPositionY.floatValue > end - edgeThresholdPx -> 24f
                        dragPositionY.floatValue < edgeThresholdPx -> -24f
                        else -> 0f
                    }
                    if (amount == 0f) {
                        scrollJob[0]?.cancel()
                        scrollJob[0] = null
                    } else if (scrollJob[0]?.isActive != true) {
                        // Keep scrolling while the finger is held at the edge.
                        // A one-off scrollBy made distant days hard to reach.
                        scrollJob[0] = dragScope.launch {
                            while (draggingDay != null) {
                                val currentY = dragPositionY.floatValue
                                val viewportEnd = weekListState.layoutInfo.viewportEndOffset
                                val direction = when {
                                    currentY > viewportEnd - edgeThresholdPx -> 24f
                                    currentY < edgeThresholdPx -> -24f
                                    else -> break
                                }
                                if (kotlin.math.abs(weekListState.scrollBy(direction)) < 0.5f) break
                                updateHover()
                                delay(32L)
                            }
                        }
                    }
                },
                onEndDrag = {
                    val source = draggingDay
                    val destination = hoveredDay
                    scrollJob[0]?.cancel()
                    scrollJob[0] = null
                    draggingDay = null
                    hoveredDay = null
                    if (source != null && destination != null) {
                        state.moveDinner(source, destination)
                    }
                },
                onCancelDrag = {
                    scrollJob[0]?.cancel()
                    scrollJob[0] = null
                    draggingDay = null
                    hoveredDay = null
                },
                onOpenRecipe = onOpenRecipe,
                onFinishDinner = { date, recipeId ->
                    finishingDinner = date to recipeId
                }
            )
        }
    }

        // A fixed status banner stays visible even if the floating preview
        // is obscured by a finger or an autoscrolling day card.
        draggingDay?.let { source ->
            state.recipeFor(source)?.let { recipe ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .zIndex(4f)
                        .testTag("week-drag-status"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 12.dp
                ) {
                    Text(
                        when (val destination = hoveredDay) {
                            null -> "Flytter: ${recipe.name} · Træk over en ulåst dag"
                            else -> "Flytter: ${recipe.name} · Slip på ${destination.danishName()}"
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // The floating card follows the pointer while the source remains
        // visibly selected and a valid destination gets a high-contrast frame.
        draggingDay?.let { source ->
            state.recipeFor(source)?.let { recipe ->
                DraggedDayPreview(
                    day = source,
                    recipeName = recipe.name,
                    targetDay = hoveredDay,
                    targetHasMeal = hoveredDay?.let(state::recipeFor) != null,
                    pointerY = dragPositionY
                )
            }
        }
    }
}

/** Floating visual only: pointer gestures remain owned by the day handle. */
@Composable
private fun DraggedDayPreview(
    day: DayOfWeek,
    recipeName: String,
    targetDay: DayOfWeek?,
    targetHasMeal: Boolean,
    pointerY: FloatState
) {
    val fingerCenterPx = with(LocalDensity.current) { 44.dp.toPx() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .offset {
                IntOffset(0, (pointerY.floatValue - fingerCenterPx).roundToInt())
            }
            .border(
                width = 3.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp)
            )
            .graphicsLayer {
                alpha = 1f
                scaleX = 1.03f
                scaleY = 1.03f
            }
            .zIndex(3f)
            .clearAndSetSemantics {}
            .testTag("week-drag-preview"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "FLYTTER NU · ${day.danishName()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    recipeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        targetDay == null -> "Træk til en anden ulåst dag"
                        targetHasMeal -> "Slip for at bytte med ${targetDay.danishName()}"
                        else -> "Slip for at flytte til ${targetDay.danishName()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
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
        Column(Modifier.padding(10.dp)) {
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
fun DayCard(
    day: DayOfWeek,
    state: MealPlannerState,
    onOpenRecipe: (String) -> Unit,
    onFinishDinner: (LocalDate, String) -> Unit,
    dropTarget: Boolean = false,
    dragging: Boolean = false,
    onStartDrag: (DayOfWeek, Float) -> Unit = { _, _ -> },
    onDrag: (Float) -> Unit = {},
    onEndDrag: () -> Unit = {},
    onCancelDrag: () -> Unit = {}
) {
    val away = state.dayModes[day] == DayMode.AWAY
    // Compose observes these lists and maps directly. Remembering by size/id
    // leaves stale titles, locks and categories when existing rows change.
    val mealDate = state.displayedWeekStart.plusDays((day.value - 1).toLong())
    val eatenId = state.history.find { it.eatenOn == mealDate }?.recipeId
    val completedLocked = state.isCompletedDayLocked(day)
    val plannedRecipe = state.recipeFor(day)
    // A corrected history entry should show what was actually eaten, not the
    // stale planned recipe. While explicitly unlocked, show the editable plan.
    val recipe = if (completedLocked && eatenId != null) {
        state.recipes.find { it.id == eatenId } ?: plannedRecipe
    } else plannedRecipe
    val isSandwich = day == state.sandwichDay &&
        recipe?.id != null && recipe.id == state.weeklySandwichRecipeId
    val isLocked = state.isDayLocked(day)
    val maxPrepMinutes = state.dayMaxPrepMinutes[day]
    val recipeCategory = if (recipe != null) {
        recipeCategoryDisplayName(
            state.recipeCategoryName(recipe),
            state.recipeProteinNames(recipe)
        )
    } else ""

    val canDrag = !away && recipe != null &&
        !isLocked && state.weeklyPlanId != null &&
        !state.isWeekChanging && !state.isPlanning
    val gripRootTop = remember { floatArrayOf(0f) }
    var showMovePicker by remember(day, state.displayedWeekStart) {
        mutableStateOf(false)
    }
    if (showMovePicker) {
        AlertDialog(
            onDismissRequest = { showMovePicker = false },
            title = { Text("Flyt ${recipe?.name ?: "retten"}") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(
                        items = orderedWeekDays.filter { state.canMoveDinner(day, it) },
                        key = { it.name }
                    ) { destination ->
                        TextButton(
                            onClick = {
                                state.moveDinner(day, destination)
                                showMovePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Byt med ${destination.danishName()}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMovePicker = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    var showRecipePicker by remember { mutableStateOf(false) }
    var recipeSearch by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingRecipeId by remember { mutableStateOf<String?>(null) }
    var showChangeConfirmation by remember { mutableStateOf(false) }
    var showCompletedUnlockConfirmation by remember(day, state.displayedWeekStart) {
        mutableStateOf(false)
    }

    if (showCompletedUnlockConfirmation) {
        AlertDialog(
            onDismissRequest = { showCompletedUnlockConfirmation = false },
            title = { Text("Lås spist måltid op?") },
            text = {
                Text(
                    "Retten er allerede registreret som spist. Du kan midlertidigt " +
                            "ændre madplanen efter oplåsning, men måltidshistorik og " +
                            "lagerforbrug ændres ikke automatisk."
                )
            },
            dismissButton = {
                TextButton(onClick = { showCompletedUnlockConfirmation = false }) {
                    Text("Annuller")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompletedUnlockConfirmation = false
                    state.toggleLockDay(day)
                }) { Text("Lås dagen op") }
            }
        )
    }

    fun chooseRecipe(id: String?) {
        if (completedLocked) return
        showRecipePicker = false
        recipeSearch = ""
        if (recipe?.id == id && !away) return
        val hasShoppingSelections = state.shoppingChecked.any { it.value } ||
                state.shoppingIncluded.any { !it.value }
        if (hasShoppingSelections || isLocked) {
            pendingRecipeId = id
            showChangeConfirmation = true
        } else {
            state.setRecipeForDay(day, id)
        }
    }

    if (showChangeConfirmation) {
        AlertDialog(
            onDismissRequest = { showChangeConfirmation = false },
            title = { Text("Skift ret for ${day.danishName()}?") },
            text = {
                Text(
                    "Manuelle ændringer er også mulige på låste dage. " +
                            "Købsmarkeringer bevares for ingredienser med uændret behov, " +
                            "mens berørte mængder skal kontrolleres igen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showChangeConfirmation = false
                    state.setRecipeForDay(day, pendingRecipeId)
                }) { Text("Skift ret") }
            },
            dismissButton = {
                TextButton(onClick = { showChangeConfirmation = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    if (showRecipePicker) {
        val choices = state.recipes
            .filter {
                it.mealType == MealType.DINNER &&
                        it.id != state.weeklySandwichRecipeId &&
                        (recipeSearch.isBlank() ||
                                it.name.contains(recipeSearch.trim(), ignoreCase = true))
            }
            .sortedBy { it.name.lowercase() }
        AlertDialog(
            onDismissRequest = { showRecipePicker = false },
            title = { Text("Vælg ret til ${day.danishName()}") },
            text = {
                Column {
                    if (isLocked && !completedLocked) {
                        Text(
                            "Låsen beskytter mod automatisk genplanlægning. Du kan stadig vælge en ret manuelt.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    SearchField(
                        value = recipeSearch,
                        onValueChange = { recipeSearch = it },
                        placeholder = "Søg efter ret"
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        item {
                            TextButton(onClick = {
                                chooseRecipe(null)
                            }) { Text("Ingen ret") }
                        }
                        items(choices, key = { it.id }) { candidate ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chooseRecipe(candidate.id)
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(candidate.name, fontWeight = FontWeight.SemiBold)
                                candidate.prepMinutes?.let {
                                    Text(
                                        "$it min",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (choices.isEmpty()) {
                            item { EmptyCard("Ingen retter matcher søgningen.") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecipePicker = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    dropTarget -> Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = RoundedCornerShape(20.dp)
                    )
                    dragging -> Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(20.dp)
                    )
                    else -> Modifier
                }
            )
            .graphicsLayer { alpha = if (dragging) 0.66f else 1f },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                dropTarget -> MaterialTheme.colorScheme.tertiaryContainer
                dragging -> MaterialTheme.colorScheme.primaryContainer
                away -> MaterialTheme.colorScheme.surfaceVariant
                isSandwich -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging || dropTarget) 5.dp else if (away) 0.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (away) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            day.shortDanishName(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (away) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    }
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
                    Surface(
                        shape = CircleShape,
                        color = if (isLocked) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (completedLocked) showCompletedUnlockConfirmation = true
                                else state.toggleLockDay(day)
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = if (isLocked) "Lås op for ${day.danishName()}" else "Lås ret for ${day.danishName()}",
                                tint = if (isLocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }

                if (canDrag) {
                    // Short tap opens an accessible destination picker.
                    // Long press and drag drops onto another visible day card.
                    IconButton(
                        onClick = { showMovePicker = true },
                        modifier = Modifier
                            // Keep the full 48dp touch target while leaving
                            // room for the adjacent home/away switch on narrow phones.
                            .size(48.dp)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .testTag("week-drag-handle-${day.name}")
                            .onGloballyPositioned {
                                gripRootTop[0] = it.positionInRoot().y
                            }
                            .pointerInput(day, canDrag) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset: Offset ->
                                        onStartDrag(day, gripRootTop[0] + offset.y)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onDrag(amount.y)
                                    },
                                    onDragEnd = onEndDrag,
                                    onDragCancel = onCancelDrag
                                )
                            }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "Flyt ${recipe.name} fra ${day.danishName()}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Flyt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                Switch(
                    checked = !away,
                    enabled = !completedLocked,
                    onCheckedChange = { state.toggleEatingAtHome(day) },
                    modifier = Modifier.semantics {
                        contentDescription = "Spiser hjemme ${day.danishName()}"
                    }
                )
            }

            if (!away) {
                if (!recipe?.imageUri.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    MealImage(
                        imageUri = recipe.imageUri,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        fallback = recipe.name.take(1).uppercase()
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Category tags have variable widths/heights. Keep them in
                // their own row so they can never displace the time selector.
                if (recipe != null) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSandwich) StatusPill("Ugens sandwich", true)
                        if (recipeCategory.isNotBlank() &&
                            !(isSandwich && recipeCategory.equals("Sandwich", ignoreCase = true))
                        ) StatusPill(recipeCategory, false)
                        recipe.prepMinutes?.let { StatusPill("$it min", false) }
                    }
                }

                if (!completedLocked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tidsgrænse",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            FilterChip(
                                selected = maxPrepMinutes != null,
                                onClick = { showTimePicker = true },
                                label = {
                                    Text(prepLimitLabel(maxPrepMinutes), maxLines = 1)
                                }
                            )
                            DropdownMenu(
                                expanded = showTimePicker,
                                onDismissRequest = { showTimePicker = false }
                            ) {
                                listOf(null, 30, 60).forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text(prepLimitOptionLabel(minutes)) },
                                        onClick = {
                                            state.setDayMaxPrepMinutes(day, minutes)
                                            showTimePicker = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (eatenId == null &&
                    mealDate <= LocalDate.now() &&
                    !mealDate.isBefore(LocalDate.now().minusDays(89)) &&
                    recipe != null
                ) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onFinishDinner(mealDate, recipe.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Færdig – registrér lagerforbrug") }
                }

                // A completed day has no editable actions until the user
                // explicitly unlocks its padlock in the header.
                if (!completedLocked) {
                    Spacer(Modifier.height(8.dp))
                    if (isSandwich) {
                        OutlinedButton(
                            onClick = { state.moveSandwich() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Flyt sandwich") }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { showRecipePicker = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Skift ret") }
                            if (!isLocked && !state.isWeekRegenerationLocked() && recipe != null) {
                                IconButton(onClick = { state.reroll(day) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Ny ret")
                                }
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
        modifier = Modifier.heightIn(min = 32.dp),
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .widthIn(max = 210.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
