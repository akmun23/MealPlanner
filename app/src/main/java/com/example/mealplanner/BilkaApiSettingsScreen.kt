package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject?.bilkaNumber(field: String): Int =
    this?.get(field)?.jsonPrimitive?.content?.toIntOrNull() ?: 0

private fun JsonObject?.bilkaText(field: String): String? =
    this?.get(field)?.jsonPrimitive?.content?.takeUnless { it == "null" }

@Composable
internal fun BilkaApiSettingsScreen(
    householdId: String,
    onBack: () -> Unit,
    onViewProducts: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var keyInputOpen by remember { mutableStateOf(value = false) }
    var connected by remember { mutableStateOf(value = false) }
    var keyStatusLoaded by remember { mutableStateOf(value = false) }
    var verified by remember { mutableStateOf(value = false) }
    var suffix by remember { mutableStateOf<String?>(null) }
    var localUsed by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var localBudget by remember { androidx.compose.runtime.mutableIntStateOf(20) }
    var providerRemaining by remember { mutableStateOf<String?>(null) }
    var nightlyEnabled by remember(householdId) { mutableStateOf(value = false) }
    var nightlyLastRun by remember(householdId) { mutableStateOf<String?>(null) }
    var nightlyLoaded by remember(householdId) { mutableStateOf(value = false) }
    var schedulerReady by remember(householdId) { mutableStateOf(value = false) }
    var importStatus by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(value = false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(value = false) }

    fun applyStatus(info: JsonObject) {
        connected = info.bilkaText("connected") == "true"
        verified = info.bilkaText("verifiedAt") != null
        suffix = info.bilkaText("keyLastFour")
        localUsed = info.bilkaNumber("locallyReserved")
        localBudget = info.bilkaNumber("localBudget").coerceAtLeast(1)
        providerRemaining = info.bilkaText("providerRemaining")
    }

    LaunchedEffect(householdId) {
        // Never lock the key form while status, catalogue or nightly data load.
        // The user may open the form while the initial status call is pending.
        BilkaPrejClient.status()
            .onSuccess(::applyStatus)
            .onFailure { message = it.message }
        keyStatusLoaded = true
        BilkaPrejClient.discoveryStatus(householdId)
            .onSuccess { importStatus = it }
            .onFailure { message = it.message }
        BilkaPrejClient.nightlyStatus(householdId)
            .onSuccess {
                nightlyEnabled = it.bilkaText("enabled") == "true"
                schedulerReady = it.bilkaText("schedulerReady") == "true"
                nightlyLastRun = it.bilkaText("lastUpdatedAt")
                nightlyLoaded = true
            }
            .onFailure { nightlyLoaded = false }
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
                                nightlyEnabled = false
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
    // Automatic discovery reserves no more than 18 daily requests, even
    // though the full account budget (including price checks) is 20.
    val discoveryQuotaExhausted = connected && (localUsed >= minOf(localBudget, 18))

    SubpageScaffold(title = "BilkaToGo · Indstillinger", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Her kan du tilføje eller udskifte din personlige Prej-nøgle, kontrollere søgekvoten og styre prisopdateringer for BilkaToGo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                SectionCard("Prej API-nøgle · din konto") {
                    Text(
                        if (!keyStatusLoaded) "Henter din Prej-nøglestatus … Du kan tilføje en nøgle med det samme."
                        else if (!connected) "Tilføj din personlige API-nøgle for at finde BilkaToGo-varer."
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
                            Text(
                                "Nøglen er gemt, men endnu ikke verificeret. " +
                                        "Det bruger ét Prej-kald at verificere den. " +
                                        "Når din dagskvote er brugt, kan du kontrollere den " +
                                        "efter næste nulstilling kl. 00.00 UTC.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (connected || !keyStatusLoaded) {
                        OutlinedButton(
                            onClick = {
                                keyInputOpen = !keyInputOpen
                                if (!keyInputOpen) apiKey = ""
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (keyInputOpen) "Annuller nøgleskift" else "Tilføj / udskift min Prej API-nøgle")
                        }
                    }

                    if ((keyStatusLoaded && !connected) || keyInputOpen) {
                        Text(
                            if (connected)
                                "Indsæt din nye nøgle her. Kun nøglen på din egen konto udskiftes."
                            else "Indsæt din personlige Prej API-nøgle for at aktivere BilkaToGo-søgning.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it.trim().take(300) },
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Prej API-nøgle") },
                            placeholder = { Text("prej_dev_…") },
                            isError = apiKey.isNotBlank() && !BilkaKeyInputRules.isValid(apiKey),
                            supportingText = {
                                Text(
                                    if (apiKey.isNotBlank() && !BilkaKeyInputRules.isValid(apiKey))
                                        "Nøglen har ikke et gyldigt Prej-format. Kontrollér, at du har kopieret hele nøglen."
                                    else "Gemmes krypteret på din konto – ikke i appen eller hos din partner."
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val submitted = apiKey
                                busy = true
                                message = null
                                scope.launch {
                                    BilkaPrejClient.saveKey(submitted)
                                        .onSuccess { response ->
                                            apiKey = ""
                                            keyInputOpen = false
                                            connected = true
                                            verified =
                                                response.bilkaText("verificationPending") != "true"
                                            suffix = response.bilkaText("keyLastFour")
                                            BilkaPrejClient.status().onSuccess(::applyStatus)
                                            message =
                                                if (response.bilkaText("verificationPending") == "true") {
                                                    "Den nye nøgle er gemt krypteret, men ikke " +
                                                            "verificeret. Dagens kvote er brugt, så " +
                                                            "søgninger kan først genoptages efter " +
                                                            "næste kvotenulstilling og nøglekontrol."
                                                } else {
                                                    "Din Prej-konto er nu forbundet."
                                                }
                                        }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            enabled = !busy && BilkaKeyInputRules.isValid(apiKey),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Gemmer nøgle …" else "Gem og kontrollér Prej-nøgle") }
                    }
                    Text(
                        "Nøglen er personlig og gemmes krypteret. Den deles ikke med andre medlemmer af husstanden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard("Find BilkaToGo-varerne") {
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
                                Text(
                                    ready.toString(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("Klar til valg", style = MaterialTheme.typography.bodySmall)
                            }
                            Column {
                                Text(
                                    pending.toString(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("Mangler søgning", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (discoveryQuotaExhausted && (pending > 0)) {
                            Text(
                                "Søgningen er sat på pause: dagens Prej-kvote til " +
                                        "produktimport er brugt. $pending ingredienser er " +
                                        "endnu ikke færdigsøgt – det betyder ikke, at Bilka " +
                                        "mangler varerne. Du kan fortsætte næste UTC-dag.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if ((noMatch > 0) || (incomplete > 0)) {
                            Text(
                                "$noMatch uden match · $incomplete kræver præcisering.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        "Automatisk massesøgning er sat på pause, fordi Prej " +
                                "gav forkerte varer og brugte for mange API-kald. " +
                                "Ingen nye kald foretages uden at du selv søger " +
                                "på en bestemt ingrediens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onViewProducts,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Vælg ingrediens og søg selv")
                    }
                    Text(
                        when {
                            !connected -> "Tilføj din Prej-nøgle, hvis du vil søge manuelt efter produkter."
                            !verified -> "Kontrollér den gemte nøgle før manuel varesøgning."
                            importStatus != null && pending == 0 && ready > 0 ->
                                "Du kan nu vælge mærke og størrelse i produktoversigten."

                            (importStatus != null) && (pending == 0) ->
                                "Der mangler ingen automatiske søgninger lige nu."

                            else ->
                                "Vi søger efter ingrediensernes faktiske navne. " +
                                        "Resultater gemmes, og du kan fortsætte senere uden at starte forfra."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard("Vælg dine faste BilkaToGo-varer") {
                    Text(
                        "Se alle ingredienser og sammenlign konkrete Bilka-varer " +
                                "på den selvstændige produktside.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = onViewProducts,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Gå til produktoversigten") }
                }
            }

            if (connected) {
                item {
                    SectionCard("Automatisk opdatering af priser") {
                        Text(
                            "Opdatér priser på valgte varer, uden at skifte mærke eller størrelse.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automatisk prisopdatering", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (!verified) "Kontrollér først din nøgle"
                                    else if (!nightlyLoaded) "Kunne ikke hente indstillingen"
                                    else if (!schedulerReady) "Afventer aktivering på serveren"
                                    else if (nightlyEnabled) "Slået til for din konto"
                                    else "Slået fra",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = nightlyEnabled,
                                onCheckedChange = { desired ->
                                    busy = true
                                    message = null
                                    scope.launch {
                                        BilkaPrejClient.setNightlyEnabled(householdId, desired)
                                            .onSuccess {
                                                nightlyEnabled = desired
                                                message = if (desired)
                                                    "Automatisk prisopdatering er slået til."
                                                else "Automatisk prisopdatering er slået fra."
                                            }
                                            .onFailure { message = it.message }
                                        busy = false
                                    }
                                },
                                enabled = !busy && nightlyLoaded &&
                                        ((verified && schedulerReady) || nightlyEnabled)
                            )
                        }
                        nightlyLastRun?.let { last ->
                            Text(
                                "Seneste kørsel: " + last.take(16).replace("T", " ") + " UTC",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Bruger kun din egen Prej-kvote. " +
                                    "Aktivering kræver, at serverens tidsplan er klar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                BilkaPrejClient.refresh(householdId)
                                    .onSuccess { info ->
                                        val count = info.bilkaNumber("updated")
                                        message =
                                            "$count valgte Bilka-produkter fik opdateret pris."
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
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
