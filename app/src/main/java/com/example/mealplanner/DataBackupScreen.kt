package com.example.mealplanner

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataBackupScreen(
    state: MealPlannerState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val backup = state.exportPortableBackup()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.writer(Charsets.UTF_8).use { writer ->
                            writer.write(backup)
                        }
                    } ?: error("Cannot open selected file")
                }
                Toast.makeText(context, "Sikkerhedskopi gemt", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                error = "Kunne ikke gemme filen. Vælg en anden placering."
            } finally {
                busy = false
            }
        }
    }

    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val output = ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= SnapshotBackupCodec.MAX_FILE_BYTES) {
                                "Backup is too large"
                            }
                            output.write(buffer, 0, n)
                        }
                        output.toString(Charsets.UTF_8.name())
                    } ?: error("Cannot open selected file")
                }
                val snapshot = withContext(Dispatchers.Default) {
                    SnapshotBackupCodec.decode(raw)
                }
                preview = "${snapshot.ingredients.size} ingredienser, " +
                    "${snapshot.recipes.size} opskrifter og " +
                    "${snapshot.pantry.size} lagervarer."
                pendingImport = raw
            } catch (_: Exception) {
                error = "Filen er ikke en gyldig MealPlanner-sikkerhedskopi."
            } finally {
                busy = false
            }
        }
    }

    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null; preview = null },
            title = { Text("Erstat lokale data?") },
            text = {
                Text(
                    "Importen indeholder ${preview.orEmpty()} " +
                        "Den erstatter alle opskrifter, ingredienser, lagervarer og " +
                        "madplanen i denne lokale husstand. " +
                        "Lav en sikkerhedskopi af dine nuværende data først."
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        val raw = pendingImport ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                state.restoreLocalBackup(raw)
                                pendingImport = null
                                preview = null
                                Toast.makeText(
                                    context,
                                    "Lokale data gendannet",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (_: Exception) {
                                error = "Import mislykkedes. Dine data er ikke ændret."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Erstat mine lokale data") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingImport = null; preview = null }
                ) { Text("Annuller") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sikkerhedskopi og data") },
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Dine data tilhører dig",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Eksportér ingredienser, opskrifter, lager, kategorier, " +
                            "ugens madplan og madhistorik til en JSON-fil. " +
                            "Filen indeholder ikke loginoplysninger."
                    )
                    Text(
                        "JSON-filen kan åbnes og redigeres i en teksteditor. " +
                            "Behold en kopi, før du foretager store ændringer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        exportFile.launch("mealplanner-${LocalDate.now()}.json")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Eksportér mine data (JSON)") }
            }
            if (state.isLocalMode) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Gendan eller importér",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "I lokal tilstand kan du importere en tidligere sikkerhedskopi " +
                                "eller en redigeret JSON-fil. Du ser indholdet og bekræfter, " +
                                "før eksisterende data erstattes."
                        )
                        OutlinedButton(
                            onClick = {
                                importFile.launch(arrayOf("application/json", "text/plain"))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Importér data fra fil") }
                        Text(
                            "En madplan fra en tidligere uge nulstilles automatisk; " +
                                "opskrifter og lager bevares.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "Import er kun mulig i lokal tilstand. Direkte import " +
                            "til en delt cloud-husstand kunne overskrive andre brugeres " +
                            "ændringer og er derfor ikke tilladt.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (error != null) {
                item {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { error = null }) {
                        Text("Luk fejlmeddelelse")
                    }
                }
            }
        }
    }
}
