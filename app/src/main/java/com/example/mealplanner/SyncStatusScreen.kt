package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncStatusScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    val statusText = when (state.syncIndicator) {
        SyncIndicator.IDLE -> "Alt er gemt"
        SyncIndicator.SAVING -> "Gemmer ændringer…"
        SyncIndicator.SAVED -> "Ændringer gemt"
        SyncIndicator.PENDING -> "Venter på forbindelse"
        SyncIndicator.DELAYED -> "Synkronisering forsinket"
        SyncIndicator.CONFLICT -> "Kræver dit valg"
        SyncIndicator.ERROR -> "Kunne ikke gemme ændringen"
        SyncIndicator.OFFLINE -> "Du arbejder offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synkronisering") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Arbejd videre, selvom nettet er væk",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Lager og opskrifter gemmes på telefonen først. Ændringerne sendes til husstanden, når forbindelsen virker igen.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(statusText, fontWeight = FontWeight.Bold)
                        SyncStat("Afventende ændringer", state.pendingSyncCount)
                        SyncStat("Forsinkede ændringer", state.failedSyncCount)
                        SyncStat("Ændringer med konflikt", state.syncConflictCount)
                    }
                }
            }
            item {
                Button(
                    onClick = state::retryPendingSync,
                    enabled = state.pendingSyncCount > 0 &&
                        state.syncConflictCount == 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Prøv at synkronisere nu")
                }
            }
            if (state.syncConflictCount > 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "En anden enhed har ændret de samme oplysninger. Vælg hvilken version, du vil beholde.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = state::keepMyConflictingChanges,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Behold mine ændringer")
                        }
                        OutlinedButton(
                            onClick = state::useSharedConflictingChanges,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Behold husstandens ændringer")
                        }
                    }
                }
            }
            item {
                Text(
                    "Hvis der står 0 afventende ændringer, er den lokale kø tom. Du kan stadig se dine gemte data offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncStat(label: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
    }
}
