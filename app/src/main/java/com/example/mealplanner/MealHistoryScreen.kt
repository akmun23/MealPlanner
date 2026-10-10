package com.example.mealplanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyDateFormat = DateTimeFormatter.ofPattern(
    "EEE d. MMM", Locale.forLanguageTag("da-DK")
)

@Composable
internal fun MealHistoryScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
    var search by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val dates = remember(today) {
        (0 until 35).map { today.minusDays(it.toLong()) }
    }

    editingDate?.let { date ->
        val available = state.recipes
            .filter {
                it.mealType == MealType.DINNER &&
                    (search.isBlank() || it.name.contains(search, ignoreCase = true))
            }
            .sortedBy { it.name.lowercase() }

        AlertDialog(
            onDismissRequest = { editingDate = null },
            title = { Text("Spist ${date.format(historyDateFormat)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vælg den ret, I faktisk spiste.")
                    SearchField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "Find opskrift"
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item {
                            TextButton(
                                onClick = {
                                    state.recordEaten(date, null)
                                    editingDate = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Fjern registrering")
                            }
                        }
                        items(available, key = { it.id }) { recipe ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        state.recordEaten(date, recipe.id)
                                        editingDate = null
                                    }
                                    .padding(vertical = 11.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(recipe.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingDate = null }) {
                    Text("Luk")
                }
            }
        )
    }

    SubpageScaffold(
        title = "Spiste måltider",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Registrér det, I faktisk spiste. Kun disse måltider " +
                        "skal tælle som historik, når næste madplan genereres.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ældre historik kan indeholde retter, som appen tidligere " +
                        "registrerede automatisk. Du kan rette dem her.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                )
                Text(
                    "Hvis du retter eller fjerner et spist måltid her, ændres " +
                        "lagerbeholdningen ikke automatisk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(dates, key = { it.toString() }) { date ->
                val logged = state.history.find { it.eatenOn == date }
                val mealName = logged?.recipeId?.let { id ->
                    state.recipes.find { it.id == id }?.name
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                date.format(historyDateFormat),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                mealName ?: if (logged == null) {
                                    "Ikke registreret"
                                } else {
                                    "Opskriften findes ikke længere"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            search = ""
                            editingDate = date
                        }) {
                            Text(if (logged == null) "Registrér" else "Ret")
                        }
                    }
                }
            }
        }
    }
}
