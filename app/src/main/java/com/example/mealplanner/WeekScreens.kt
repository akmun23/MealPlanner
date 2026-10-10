package com.example.mealplanner

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

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
    val lunchOptions = state.recipes.filter { it.mealType == MealType.LUNCH }.sortedBy { it.name.lowercase() }
    var showLunchPicker by remember { mutableStateOf(false) }
    var showSandwichPicker by remember { mutableStateOf(false) }
    var showGenerateConfirmation by remember { mutableStateOf(false) }

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

    // Day cards are expensive to measure (photos, menus, status chips).
    // Compose only the visible days instead of laying out the whole week
    // for every scroll frame.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "week-summary") {
            Column {
                Text(
            "Madplan",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        val start = state.displayedWeekStart
        val end = start.plusDays(6)
        val dateFormat = DateTimeFormatter.ofPattern("d. MMM", Locale.forLanguageTag("da-DK"))
        val weekNumber = start.get(WeekFields.ISO.weekOfWeekBasedYear())
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
                Text(
                    "Uge $weekNumber · ${start.year}",
                    fontWeight = FontWeight.SemiBold
                )
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
        if (start != LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) &&
            !state.isLocalMode
        ) {
            TextButton(
                onClick = { state.showCurrentWeek() },
                enabled = !state.isWeekChanging,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Tilbage til denne uge")
            }
        }
        if (state.isWeekChanging) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onMealHistory) {
            Text("Spiste måltider / Madhistorik")
        }
        Spacer(Modifier.height(8.dp))

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

            }
        }

        items(
            items = orderedWeekDays,
            key = { it.name },
            contentType = { "day-card" }
        ) { day ->
            DayCard(
                day = day,
                state = state,
                onOpenRecipe = onOpenRecipe
            )
        }

        item(key = "week-generate") {
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
                !state.isWeekChanging,
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
fun DayCard(
    day: DayOfWeek,
    state: MealPlannerState,
    onOpenRecipe: (String) -> Unit
) {
    val away = state.dayModes[day] == DayMode.AWAY
    // Compose observes these lists and maps directly. Remembering by size/id
    // leaves stale titles, locks and categories when existing rows change.
    val recipe = state.recipeFor(day)
    val isSandwich = recipe?.id != null && recipe.id == state.weeklySandwichRecipeId
    val isLocked = day in state.lockedDays
    val maxPrepMinutes = state.dayMaxPrepMinutes[day]
    val mealDate = state.displayedWeekStart
        .plusDays((day.value - 1).toLong())
    val eatenId = state.history.find { it.eatenOn == mealDate }?.recipeId
    val recipeCategory = if (recipe != null) {
        recipeCategoryDisplayName(
            state.recipeCategoryName(recipe),
            state.recipeProteinNames(recipe)
        )
    } else ""

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

                // Let long categories and the time filter wrap on small screens.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (recipe != null) {
                        if (isSandwich) StatusPill("Ugens sandwich", true)
                        if (recipeCategory.isNotBlank()) StatusPill(recipeCategory, false)
                        recipe.prepMinutes?.let {
                            StatusPill("$it min", false)
                        }
                    }

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
                            listOf(null, 30, 60).forEach { minutes ->
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
                }

                if (mealDate <= LocalDate.now() && recipe != null) {
                    Spacer(Modifier.height(5.dp))
                    if (eatenId == null) {
                        TextButton(onClick = {
                            state.recordEaten(mealDate, recipe.id)
                        }) {
                            Text("Bekræft spist")
                        }
                    } else {
                        val eatenName = state.recipes.find { it.id == eatenId }?.name
                            ?: "Ukendt opskrift"
                        Text(
                            "Spist: $eatenName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                                    .sortedBy { it.name.lowercase() }
                                    .forEach { candidate ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    buildString {
                                                        append(candidate.name)
                                                        candidate.prepMinutes?.let {
                                                            append(" · $it min")
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
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 3.dp)
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
