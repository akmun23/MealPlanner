package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun BilkaApiSettingsScreen(householdId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var suffix by remember { mutableStateOf<String?>(null) }
    var quota by remember { mutableStateOf("–") }
    var importStatus by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun applyStatus(info: JsonObject) {
        connected = info["connected"]?.jsonPrimitive?.content == "true"
        suffix = info["keyLastFour"]?.jsonPrimitive?.content?.takeIf { it != "null" }
        val used = info["locallyReserved"]?.jsonPrimitive?.content ?: "0"
        val budget = info["localBudget"]?.jsonPrimitive?.content ?: "20"
        val remaining = info["providerRemaining"]?.jsonPrimitive?.content
            ?.takeIf { it != "null" } ?: "ukendt"
        quota = "$used / $budget kald reserveret i appen i dag · $remaining tilbage hos Prej"
    }

    LaunchedEffect(Unit) {
        busy = true
        BilkaPrejClient.status()
            .onSuccess(::applyStatus)
            .onFailure { message = it.message }
        BilkaPrejClient.discoveryStatus(householdId)
            .onSuccess { importStatus = it }
        busy = false
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Fjern din Prej-nøgle?") },
            text = { Text("Din gemte nøgle slettes fra Supabase Vault. Husstandens standardprodukter bliver ikke ændret.") },
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
                                suffix = null
                                apiKey = ""
                                message = "Nøglen er fjernet"
                            }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Fjern nøgle") }
            }
        )
    }

    SubpageScaffold(title = "Bilka API", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionCard("Din personlige Prej-konto") {
                    Text(
                        if (connected) "Forbundet · Nøgle slutter på ${suffix ?: "????"}"
                        else "Ingen Prej-nøgle tilføjet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Nøglen er kun din. Du og din partner deler fortsat Bilka-produkter, men ikke API-nøgler eller kvoter.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (connected) {
                        Text(quota, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionCard(if (connected) "Udskift min API-nøgle" else "Tilføj min API-nøgle") {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim().take(300) },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Prej API-nøgle") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "Du finder din nøgle på prej.dk/konto. Den sendes krypteret til Supabase, valideres og gemmes i Vault – aldrig på telefonen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            val submittedKey = apiKey
                            apiKey = ""
                            busy = true
                            scope.launch {
                                BilkaPrejClient.saveKey(submittedKey)
                                    .onSuccess { info ->
                                        connected = true
                                        suffix = info["keyLastFour"]?.jsonPrimitive?.content
                                        message = "Prej-nøglen er kontrolleret og gemt"
                                        BilkaPrejClient.status().onSuccess(::applyStatus)
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kontrollér og gem nøgle")
                    }
                }
            }
            item {
                SectionCard("Find manglende Bilka-produkter") {
                    Text(
                        "Søg efter produktkandidater til ingredienser, som endnu ikke har et standardprodukt. " +
                        "Søgninger grupperes og genbruger gemte resultater for at spare API-kald.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val data = importStatus
                    if (data != null) {
                        fun count(field: String) = data[field]?.jsonPrimitive?.content ?: "0"
                        Text("Standardvalg: ${count("chosen")} · Klar til valg: ${count("ready")}")
                        Text("Mangler søgning: ${count("pending")} · Ingen match: ${count("noMatch")}")
                        if (count("incomplete") != "0") Text("Kræver en mere præcis søgning: ${count("incomplete")}")
                    }
                    Button(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                BilkaPrejClient.discoverProducts(householdId)
                                    .onSuccess { info ->
                                        val used = info["requestsUsed"]?.jsonPrimitive?.content ?: "0"
                                        val cached = info["fromCache"]?.jsonPrimitive?.content ?: "0"
                                        val pending = info["pending"]?.jsonPrimitive?.content ?: "0"
                                        val reason = info["stopReason"]?.jsonPrimitive?.content
                                        importStatus = info
                                        message = "Import: $used Prej-kald, $cached genbrugte søgninger. " +
                                            "$pending ingredienser mangler stadig." +
                                            when (reason) {
                                                "daily_budget_exhausted", "prej_daily_limit" ->
                                                    " Dagens API-budget er opbrugt."
                                                "run_call_limit" ->
                                                    " Tryk igen for at fortsætte inden for budgettet."
                                                "prej_key_invalid", "no_api_key" ->
                                                    " Kontrollér din Prej-nøgle."
                                                "prej_burst_limit" -> " Prøv igen senere."
                                                "prej_unavailable", "invalid_provider_response" ->
                                                    " Prej kunne ikke levere nye resultater."
                                                else -> ""
                                            }
                                        BilkaPrejClient.status().onSuccess(::applyStatus)
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        enabled = connected && !busy &&
                            (importStatus == null ||
                             importStatus?.get("pending")?.jsonPrimitive?.content != "0"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Hent manglende produkter")
                    }
                    Text(
                        "Produktvalg med mærke, billede og pakkestørrelse kommer på næste skærm i fase 4. " +
                        "Ingen produkter vælges automatisk.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (connected) {
                item {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                BilkaPrejClient.refresh(householdId)
                                    .onSuccess { info ->
                                        val count = info["updated"]?.jsonPrimitive?.content ?: "0"
                                        message = "$count Bilka-produkter fik opdateret pris"
                                        BilkaPrejClient.status().onSuccess(::applyStatus)
                                    }
                                    .onFailure { message = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Opdatér valgte Bilka-priser nu")
                    }
                }
                item {
                    TextButton(
                        onClick = { confirmRemove = true },
                        enabled = !busy
                    ) { Text("Fjern min nøgle") }
                }
            }
            if (busy) item { CircularProgressIndicator() }
            message?.let { text ->
                item {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
