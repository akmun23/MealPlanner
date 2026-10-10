package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class BilkaIngredientStatus(
    val name: String,
    val state: String,
    val suggestions: Int
)

private fun JsonObject?.bilkaNumber(field: String): Int =
    this?.get(field)?.jsonPrimitive?.content?.toIntOrNull() ?: 0

private fun JsonObject?.bilkaText(field: String): String? =
    this?.get(field)?.jsonPrimitive?.content?.takeUnless { it == "null" }

private fun JsonObject?.bilkaIngredients(): List<BilkaIngredientStatus> =
    this?.get("items")?.jsonArray?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.bilkaText("name")?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        BilkaIngredientStatus(
            name = name,
            state = item.bilkaText("state") ?: "PENDING",
            suggestions = item.bilkaNumber("candidateCount")
        )
    }.orEmpty()

private fun importStopMessage(reason: String?): String = when (reason) {
    "daily_budget_exhausted", "prej_daily_limit" ->
        "Dagens søgebudget er brugt. Du kan fortsætte efter næste kvotenulstilling."
    "run_call_limit" ->
        "Søgningen er sat på pause. Tryk igen for at fortsætte."
    "prej_key_invalid", "no_api_key" ->
        "Din Prej-nøgle skal kontrolleres, før vi kan fortsætte."
    "prej_burst_limit" ->
        "Prej har midlertidigt begrænset søgninger. Prøv igen senere."
    "prej_unavailable", "invalid_provider_response" ->
        "Prej kunne ikke hente flere varer lige nu. De fundne varer er gemt."
    else -> "Fundne produktforslag er gemt. Du kan altid fortsætte senere."
}

private fun ingredientStatusLabel(item: BilkaIngredientStatus): String = when (item.state) {
    "CHOSEN" -> "Standard valgt"
    "READY" -> "${item.suggestions} forslag klar"
    "REFINE" -> "Kræver præcisering"
    "NO_MATCH" -> "Ingen forslag fundet"
    else -> "Mangler søgning"
}

@Composable
internal fun BilkaApiSettingsScreen(householdId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var keyInputOpen by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var suffix by remember { mutableStateOf<String?>(null) }
    var localUsed by remember { mutableStateOf(0) }
    var localBudget by remember { mutableStateOf(20) }
    var providerRemaining by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<JsonObject?>(null) }
    var showingAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun applyStatus(info: JsonObject) {
        connected = info.bilkaText("connected") == "true"
        verified = info.bilkaText("verifiedAt") != null
        suffix = info.bilkaText("keyLastFour")
        localUsed = info.bilkaNumber("locallyReserved")
        localBudget = info.bilkaNumber("localBudget").coerceAtLeast(1)
        providerRemaining = info.bilkaText("providerRemaining")
        if (connected) keyInputOpen = false
    }

    LaunchedEffect(householdId) {
        busy = true
        BilkaPrejClient.status()
            .onSuccess(::applyStatus)
            .onFailure { message = it.message }
        BilkaPrejClient.discoveryStatus(householdId)
            .onSuccess { importStatus = it }
            .onFailure { message = it.message }
        busy = false
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Fjern din Prej-nøgle?") },
            text = {
                Text("Nøglen slettes fra din konto. Husstandens produktvalg, varer og indkøbsliste beholdes.")
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Annuller") }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    busy = true
                    scope.launch {
                        BilkaPrejClient.removeKey()
                            .onSuccess {
                                connected = false
                                verified = false
                                suffix = null
                                apiKey = ""
                                keyInputOpen = false
                                message = "Din nøgle er fjernet."
                            }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Fjern nøgle") }
            }
        )
    }

    val total = importStatus.bilkaNumber("total")
    val chosen = importStatus.bilkaNumber("chosen")
    val ready = importStatus.bilkaNumber("ready")
    val pending = importStatus.bilkaNumber("pending")
    val noMatch = importStatus.bilkaNumber("noMatch")
    val incomplete = importStatus.bilkaNumber("incomplete")
    val items = importStatus.bilkaIngredients()
    val shownItems = if (showingAll) items else items.take(12)
    val actionable = pending > 0

    SubpageScaffold(title = "Bilka-produkter", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Find dine faste varer i Bilka. Vælg selv mærke og størrelse – vi skifter aldrig dit valg automatisk.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                SectionCard("1. Forbind din Prej-konto") {
                    Text(
                        if (!connected) "Tilføj din personlige API-nøgle for at finde Bilka-varer."
                        else if (verified) "Din konto er forbundet. Nøglen slutter på ${suffix ?: "****"}."
                        else "Din nøgle er gemt, men skal kontrolleres én gang.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (connected) {
                        Text(
                            "Brugt i dag: $localUsed af $localBudget kald i MealPlanner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        providerRemaining?.let {
                            Text(
                                "Tilbage hos Prej: $it kald.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!verified) {
                            Button(
                                onClick = {
                                    busy = true
                                    message = null
                                    scope.launch {
                                        BilkaPrejClient.verifySavedKey()
                                            .onSuccess {
                                                BilkaPrejClient.status().onSuccess(::applyStatus)
                                                message = "Nøglen er kontrolleret og klar."
                                            }
                                            .onFailure { message = it.message }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Kontrollér min gemte nøgle") }
                            Text(
                                "Kontrollen bruger ét API-kald. Du behøver ikke skrive nøglen igen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { keyInputOpen = !keyInputOpen },
                            enabled = !busy
                        ) { Text(if (keyInputOpen) "Annuller" else "Brug en anden nøgle") }
                    }

                    if (!connected || keyInputOpen) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it.trim().take(300) },
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Din Prej API-nøgle") },
                            placeholder = { Text("Indsæt nøglen fra prej.dk/konto") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val submitted = apiKey
                                apiKey = ""
                                busy = true
                                message = null
                                scope.launch {
                                    BilkaPrejClient.saveKey(submitted)
                                        .onSuccess {
                                            BilkaPrejClient.status().onSuccess(::applyStatus)
                                            message = "Din Prej-konto er nu forbundet."
                                        }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            enabled = !busy && apiKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Kontrollér og gem nøgle") }
                    }
                    Text(
                        "Nøglen er personlig og gemmes krypteret. Den deles ikke med andre medlemmer af husstanden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard("2. Find Bilka-varerne") {
                    if (importStatus == null) {
                        Text("Henter overblik over dine ingredienser …")
                    } else {
                        Text(
                            "$chosen af $total ingredienser har et fast produktvalg.",
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("$ready", style = MaterialTheme.typography.titleMedium)
                                Text("Klar til valg", style = MaterialTheme.typography.bodySmall)
                            }
                            Column {
                                Text("$pending", style = MaterialTheme.typography.titleMedium)
                                Text("Mangler søgning", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (noMatch > 0 || incomplete > 0) {
                            Text(
                                "$noMatch uden match · $incomplete kræver præcisering.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                BilkaPrejClient.discoverProducts(householdId)
                                    .onSuccess { result ->
                                        importStatus = result
                                        val used = result.bilkaNumber("requestsUsed")
                                        val cached = result.bilkaNumber("fromCache")
                                        val reason = result.bilkaText("stopReason")
                                        message =
                                            "Fandt og gemte produktforslag. " +
                                            "$used nye søgninger, $cached genbrugte resultater. " +
                                            importStopMessage(reason)
                                        BilkaPrejClient.status().onSuccess(::applyStatus)
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        enabled = connected && !busy && (importStatus == null || actionable),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                importStatus != null && pending == 0 -> "Alle mulige søgninger er udført"
                                importStatus != null && ready > 0 -> "Fortsæt med at finde varer"
                                else -> "Find manglende Bilka-varer"
                            }
                        )
                    }
                    Text(
                        when {
                            !connected -> "Tilføj din Prej-nøgle ovenfor for at starte søgningen."
                            importStatus != null && pending == 0 && ready > 0 ->
                                "Varerne er klar til, at du kan vælge mærke og størrelse i næste fase."
                            importStatus != null && pending == 0 ->
                                "Der mangler ingen automatiske søgninger lige nu."
                            else ->
                                "Vi samler lignende ingredienser i færre søgninger. " +
                                "Du kan stoppe og fortsætte uden at miste resultater."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (items.isNotEmpty()) {
                item {
                    SectionCard("3. Se dine ingredienser") {
                        Text(
                            "Her kan du se, hvilke ingredienser der har fået forslag. " +
                            "Du skal selv vælge det endelige produkt – vi vælger ikke automatisk.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        shownItems.forEachIndexed { index, ingredient ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    ingredient.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    ingredientStatusLabel(ingredient),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (items.size > 12) {
                            TextButton(onClick = { showingAll = !showingAll }) {
                                Text(if (showingAll) "Vis færre" else "Vis alle ${items.size} ingredienser")
                            }
                        }
                    }
                }
            }

            if (connected) {
                item {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                BilkaPrejClient.refresh(householdId)
                                    .onSuccess { info ->
                                        val count = info.bilkaNumber("updated")
                                        message = "$count valgte Bilka-produkter fik opdateret pris."
                                        BilkaPrejClient.status().onSuccess(::applyStatus)
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Opdatér priser på valgte varer") }
                }
                item {
                    TextButton(
                        onClick = { confirmRemove = true },
                        enabled = !busy
                    ) { Text("Fjern min Prej-nøgle") }
                }
            }

            if (busy) item { CircularProgressIndicator() }
            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
