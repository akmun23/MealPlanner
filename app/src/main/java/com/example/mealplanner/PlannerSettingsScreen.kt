package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class PlannerOption(
    val id: String,
    val label: String,
    val description: String,
    val value: Double,
    val maximum: Float,
    val updatedWeights: (Double) -> PlannerWeights
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSettingsScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    val w = state.plannerWeights
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val options = listOf(
        PlannerOption(
            "pantry",
            "Brug varer fra lager",
            "Foretræk opskrifter, der passer til det, du har.",
            w.pantryCoverage,
            100f
        ) { w.copy(pantryCoverage = it) },
        PlannerOption(
            "reuse",
            "Genbrug ingredienser",
            "Foretræk ingredienser, der også bruges andre dage.",
            w.ingredientReuse,
            100f
        ) { w.copy(ingredientReuse = it) },
        PlannerOption(
            "duplicate",
            "Undgå samme opskrift",
            "Højere tal giver mere variation i ugens retter.",
            w.duplicateRecipe,
            300f
        ) { w.copy(duplicateRecipe = it) },
        PlannerOption(
            "repeatProtein",
            "Variér proteinkilder",
            "Undgå det samme protein flere gange på en uge.",
            w.repeatedProtein,
            100f
        ) { w.copy(repeatedProtein = it) },
        PlannerOption(
            "adjacentProtein",
            "Protein på nabodage",
            "Undgå samme protein to dage i træk.",
            w.adjacentProtein,
            100f
        ) { w.copy(adjacentProtein = it) },
        PlannerOption(
            "repeatCategory",
            "Variér opskriftskategorier",
            "Undgå for mange retter fra samme kategori.",
            w.repeatedCategory,
            100f
        ) { w.copy(repeatedCategory = it) },
        PlannerOption(
            "adjacentCategory",
            "Kategori på nabodage",
            "Undgå samme opskriftskategori to dage i træk.",
            w.adjacentCategory,
            100f
        ) { w.copy(adjacentCategory = it) },
        PlannerOption(
            "last7",
            "Spist de sidste 7 dage",
            "Undgå retter, som du lige har lavet.",
            w.recency7Days,
            300f
        ) { w.copy(recency7Days = it) },
        PlannerOption(
            "last14",
            "Spist for 8–14 dage siden",
            "Undgå nylige gentagelser.",
            w.recency14Days,
            300f
        ) { w.copy(recency14Days = it) },
        PlannerOption(
            "last30",
            "Spist for 15–30 dage siden",
            "Læg vægt på længere variation.",
            w.recency30Days,
            300f
        ) { w.copy(recency30Days = it) },
        PlannerOption(
            "sameDay",
            "Skift ret på samme ugedag",
            "Foretræk en anden ret på samme ugedag.",
            w.keepSameDay,
            100f
        ) { w.copy(keepSameDay = it) },
        PlannerOption(
            "newPlan",
            "Undgå gentagelser fra nuværende plan",
            "Foretræk retter, der ikke allerede er med i den nuværende madplan.",
            w.previousPlanReuse,
            150f
        ) { w.copy(previousPlanReuse = it) },
        PlannerOption(
            "jitter",
            "Tilfældighed",
            "Højere tal giver mere overraskelse, lavere mere ensartede planer.",
            w.jitter,
            100f
        ) { w.copy(jitter = it) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tilpas madplan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Din madplan, dine regler",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Ændringer gemmes på denne telefon og bruges næste gang, du genererer en madplan eller skifter en ret. 0 slår en prioritet fra."
                    )
                }
            }
            item {
                Text(
                    "Hurtige valg",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlannerPreset.entries.forEach { preset ->
                        OutlinedButton(
                            onClick = { state.updatePlannerWeights(preset.weights()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (if (w == preset.weights()) "✓ " else "") + preset.title +
                                        " · " + preset.description
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showAdvanced) "Skjul avancerede indstillinger" else "Vis avancerede indstillinger")
                }
            }
            if (showAdvanced) {
                items(options, key = PlannerOption::id) { option ->
                    PlannerOptionSlider(option) { value ->
                        state.updatePlannerWeights(option.updatedWeights(value))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = state::resetPlannerWeights,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Gendan standardindstillinger")
                }
            }
        }
    }
}

@Composable
private fun PlannerOptionSlider(
    option: PlannerOption,
    onCommit: (Double) -> Unit
) {
    var draft by remember(option.id, option.value) {
        mutableFloatStateOf(option.value.toFloat())
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(draft.roundToInt().toString())
            }
            Text(
                option.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = draft.coerceIn(0f, option.maximum),
                onValueChange = { draft = it },
                onValueChangeFinished = {
                    onCommit(draft.roundToInt().toDouble())
                },
                valueRange = 0f..option.maximum
            )
        }
    }
}
