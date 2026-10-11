package com.example.mealplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class BilkaListIngredient(
    val ingredientId: String,
    val name: String,
    val state: String,
    val candidateCount: Int = 0
)
@Serializable
internal data class BilkaListCandidate(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("display_rank") val displayRank: Int = 0
)
@Serializable
internal data class BilkaListDefault(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String
)

internal data class BilkaProductCatalog(
    val candidates: List<BilkaListCandidate>,
    val defaults: List<BilkaListDefault>,
    val products: List<BilkaProduct>
) {
    // Preindex once when the catalog arrives; rows should not repeatedly scan
    // every suggestion and product during fast scrolling or recomposition.
    private val productsById = products.associateBy { it.id }
    private val defaultsByIngredient = defaults.associateBy { it.ingredientId }
    private val candidatesByIngredient = candidates.groupBy { it.ingredientId }

    fun product(id: String): BilkaProduct? = productsById[id]
    fun defaultId(ingredientId: String): String? =
        defaultsByIngredient[ingredientId]?.productId
    fun suggestions(ingredientId: String): Set<String> =
        candidatesByIngredient[ingredientId].orEmpty().mapTo(mutableSetOf()) { it.productId }
    fun productsFor(ingredientId: String): List<BilkaProduct> {
        val allowed = suggestions(ingredientId) + listOfNotNull(defaultId(ingredientId))
        return allowed.mapNotNull(productsById::get)
    }
    fun ranks(ingredientId: String): Map<String, Int> =
        candidatesByIngredient[ingredientId].orEmpty()
            .associate { it.productId to it.displayRank }
}

private val bilkaParser = Json { ignoreUnknownKeys = true }
internal fun parseBilkaCatalog(value: JsonObject) = BilkaProductCatalog(
    candidates = bilkaParser.decodeFromJsonElement(
        value.getValue("candidates").jsonArray
    ),
    defaults = bilkaParser.decodeFromJsonElement(
        value.getValue("defaults").jsonArray
    ),
    products = bilkaParser.decodeFromJsonElement(
        value.getValue("products").jsonArray
    )
)
internal fun parseBilkaIngredients(value: JsonObject): List<BilkaListIngredient> =
    bilkaParser.decodeFromJsonElement(value.getValue("items").jsonArray)

private val danish = Locale.forLanguageTag("da-DK")
private fun money(ore: Number?): String =
    ore?.let { NumberFormat.getCurrencyInstance(danish).format(it.toDouble() / 100) }
        ?: "Pris mangler"
private fun packageLabel(p: BilkaProduct): String =
    if (p.packageQuantity != null && !p.packageUnit.isNullOrBlank())
        NumberFormat.getNumberInstance(danish).format(p.packageQuantity) + " " + p.packageUnit
    else "Størrelse ukendt"
private fun unitLabel(p: BilkaProduct): String? {
    val unit = when (p.unitPriceBasis) {
        BilkaUnitPriceBasis.KG -> "kg"
        BilkaUnitPriceBasis.L -> "l"
        BilkaUnitPriceBasis.PIECE -> "stk."
        BilkaUnitPriceBasis.PACK -> "pakke"
        null -> null
    }
    return if (unit != null && p.unitPriceOre != null) money(p.unitPriceOre) + "/" + unit else null
}
private fun statusLabel(row: BilkaListIngredient): String = when (row.state) {
    "CHOSEN" -> "Fast vare valgt"
    "READY" -> row.candidateCount.toString() + " forslag klar"
    "NO_MATCH" -> "Ingen forslag fundet"
    "REFINE" -> "Prøv mere præcis søgning"
    else -> "Mangler søgning"
}

@Composable
private fun BilkaProductPhoto(p: BilkaProduct) {
    val boxModifier = Modifier.requiredSize(76.dp)
        .clip(RoundedCornerShape(12.dp))
    val context = LocalContext.current
    // A remote product photo may be thousands of pixels. Only decode a thumbnail.
    val model = remember(context, p.imageUrl) {
        ImageRequest.Builder(context)
            .data(p.imageUrl)
            .size(228, 228)
            .crossfade(false)
            .build()
    }
    Box(
        modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (p.imageUrl.isNullOrBlank()) {
            Icon(Icons.Filled.ImageNotSupported, contentDescription = "Intet billede")
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = "Produktbillede af " + p.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {
                    Icon(Icons.Filled.ImageNotSupported, contentDescription = null)
                },
                error = {
                    Icon(Icons.Filled.ImageNotSupported, contentDescription = null)
                }
            )
        }
    }
}

/** Dedicated ingredient overview, not embedded in shopping, recipes or pantry. */
@Composable
internal fun BilkaProductsScreen(
    householdId: String,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onPick: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rows by remember(householdId) { mutableStateOf<List<BilkaListIngredient>>(emptyList()) }
    var catalog by remember(householdId) { mutableStateOf<BilkaProductCatalog?>(null) }
    var busy by remember(householdId) { mutableStateOf(true) }
    var error by remember(householdId) { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Alle") }
    var bulkSelection by remember(householdId) { mutableStateOf(setOf<String>()) }
    var bulkConfirm by remember { mutableStateOf(false) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        busy = true
        error = null
        val status = BilkaPrejClient.discoveryStatus(householdId)
        val products = BilkaPrejClient.catalog(householdId)
        if (status.isSuccess && products.isSuccess) {
            runCatching {
                rows = parseBilkaIngredients(status.getOrThrow())
                catalog = parseBilkaCatalog(products.getOrThrow())
            }.onFailure { error = "Produktoversigten kunne ikke læses." }
        } else error = "Kunne ikke hente Bilka-produkter. Kontrollér forbindelsen."
        busy = false
    }
    LaunchedEffect(householdId) { reload() }
    val visible = rows.filter {
        (search.isBlank() || it.name.contains(search.trim(), ignoreCase = true)) &&
            when (filter) {
                "Klar" -> it.state == "READY"
                "Valgte" -> it.state == "CHOSEN"
                "Mangler" -> it.state !in setOf("READY", "CHOSEN")
                else -> true
            }
    }.sortedWith(compareBy(
        { if (it.state == "READY") 0 else if (it.state == "CHOSEN") 1 else 2 },
        { it.name }
    ))
    if (bulkConfirm) {
        AlertDialog(
            onDismissRequest = { bulkConfirm = false },
            title = { Text("Søg efter ${bulkSelection.size} ingredienser?") },
            text = {
                Text(
                    "Højst ${bulkSelection.size} nye Prej-kald. Hvert opslag viser op " +
                        "til 100 varer, også de irrelevante. Allerede gemte opslag " +
                        "bruger ingen nye kald. Massesøgningen stopper, hvis kvoten er brugt."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val requested = bulkSelection.toList()
                    bulkConfirm = false
                    bulkBusy = true
                    scope.launch {
                        BilkaPrejClient.bulkSearch(householdId, requested)
                            .onSuccess { response ->
                                val used = response["requestsUsed"]?.toString() ?: "0"
                                val shown = response["totalShown"]?.toString() ?: "0"
                                val stopped = response["stoppedReason"]?.toString()
                                bulkMessage = "Fandt $shown varer fordelt på de søgte ingredienser. " +
                                    "Brugte $used API-kald." +
                                    (if (stopped != null && stopped != "null")
                                        " Stoppet: $stopped" else "")
                                bulkSelection = emptySet()
                                reload()
                            }
                            .onFailure { bulkMessage = it.message }
                        bulkBusy = false
                    }
                }) { Text("Start massesøgning") }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = false }) {
                    Text("Annuller")
                }
            }
        )
    }
    SubpageScaffold(title = "Mine BilkaToGo-produkter", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Vælg selv mærke og pakkestørrelse. Dine faste varer deles i husstanden og ændres ikke automatisk."
                )
            }
            item {
                Text(
                    rows.count { it.state == "CHOSEN" }.toString() + " valgt · " +
                        rows.count { it.state == "READY" }.toString() + " klar til valg · " +
                        rows.count { it.state !in setOf("CHOSEN", "READY") }.toString() + " mangler",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (rows.isNotEmpty() &&
                rows.none { it.state == "READY" || it.state == "CHOSEN" } &&
                rows.any { it.state == "PENDING" }) {
                item {
                    Text(
                        "Der er stadig ingredienser, der ikke er søgt færdigt. " +
                            "En tom forslagsliste betyder ikke, at Bilka ikke sælger varerne. " +
                            "Kontrollér den resterende Prej-kvote under Bilka-indstillinger.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Text(
                    "Alle søgeresultater vises, også dem uden ordmatch. " +
                        "Vælg op til 5 ingredienser nedenfor med afkrydsningsfeltet, " +
                        "og start massesøgningen. Det koster højst ét kald per " +
                        "ingrediens (0 ved identisk gemt søgning).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { bulkConfirm = true },
                    enabled = bulkSelection.isNotEmpty() && !bulkBusy && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Massesøg ${bulkSelection.size} ingredienser")
                }
                bulkMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (bulkBusy) CircularProgressIndicator()
            }
            item {
                SearchField(search, { search = it }, "Søg efter ingrediens")
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Alle", "Klar", "Valgte", "Mangler").forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f) }
                        )
                    }
                }
            }
            if (busy) item { CircularProgressIndicator() }
            else if (error != null) {
                item {
                    EmptyCard(error ?: "Fejl")
                    OutlinedButton(onClick = { scope.launch { reload() } }) {
                        Text("Prøv igen")
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    EmptyCard(
                        if (rows.isEmpty()) "Ingen varer endnu. Tryk på Hent flere varer."
                        else "Ingen ingredienser matcher søgningen."
                    )
                }
            } else {
                items(visible, key = { it.ingredientId }) { item ->
                    Card(
                        onClick = { onPick(item.ingredientId, item.name) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    statusLabel(item),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val selected = catalog?.defaultId(item.ingredientId)?.let { id ->
                                    catalog?.product(id)
                                }
                                if (selected != null) {
                                    Text(
                                        selected.brandLabel + " · " + packageLabel(selected),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Checkbox(
                                checked = item.ingredientId in bulkSelection,
                                onCheckedChange = { checked ->
                                    bulkSelection = if (checked) {
                                        if (bulkSelection.size < 5)
                                            bulkSelection + item.ingredientId else bulkSelection
                                    } else bulkSelection - item.ingredientId
                                },
                                enabled = !bulkBusy && !busy
                            )
                            Text(
                                if (item.state == "CHOSEN") "Ændr" else "Vælg",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { scope.launch { reload() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Opdatér oversigt") }
            }
        }
    }
}

/** The visual SKU choice gets its own full page, one ingredient at a time. */
@Composable
internal fun BilkaProductPickerScreen(
    householdId: String,
    ingredientId: String,
    ingredientName: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onImport: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var data by remember(ingredientId) { mutableStateOf<BilkaProductCatalog?>(null) }
    var selected by remember(ingredientId) { mutableStateOf<String?>(null) }
    var busy by remember(ingredientId) { mutableStateOf(true) }
    var saving by remember(ingredientId) { mutableStateOf(false) }
    var saveError by remember(ingredientId) { mutableStateOf<String?>(null) }
    var error by remember(ingredientId) { mutableStateOf<String?>(null) }
    var search by remember(ingredientId) { mutableStateOf("") }
    // Prefer ingredient-name matches. Unit-price ranking stays an explicit option.
    var cheapest by remember(ingredientId) { mutableStateOf(false) }
    var visibleCount by remember(ingredientId) { mutableStateOf(12) }
    var moreQuery by remember(ingredientId) { mutableStateOf(ingredientName) }
    var eanText by remember(ingredientId) { mutableStateOf("") }
    var searchOtherBrand by remember(ingredientId) { mutableStateOf(false) }
    var searchingMore by remember(ingredientId) { mutableStateOf(false) }
    var moreMessage by remember(ingredientId) { mutableStateOf<String?>(null) }
    var lastQuery by remember(ingredientId) { mutableStateOf(ingredientName) }
    var nextPage by remember(ingredientId) { mutableStateOf<Int?>(null) }

    suspend fun reload() {
        busy = true
        error = null
        BilkaPrejClient.catalog(householdId, listOf(ingredientId))
            .onSuccess { raw ->
                runCatching {
                    data = parseBilkaCatalog(raw)
                    selected = data?.defaultId(ingredientId)
                }.onFailure { error = "Kunne ikke læse varerne." }
            }
            .onFailure { error = "Kunne ikke hente varerne." }
        busy = false
    }
    LaunchedEffect(householdId, ingredientId) { reload() }

    val currentId = data?.defaultId(ingredientId)
    val suggestions = data?.suggestions(ingredientId).orEmpty()
    val options = remember(data, ingredientId, ingredientName, cheapest, search) {
        BilkaSelectionRules.sortForIngredient(
            data?.productsFor(ingredientId).orEmpty(),
            data?.ranks(ingredientId).orEmpty(),
            cheapest,
            chosenProductId = data?.defaultId(ingredientId),
            ingredientName = ingredientName
        ).filter {
            search.isBlank() || it.name.contains(search.trim(), true) ||
                it.brandLabel.contains(search.trim(), true)
        }
    }

    LaunchedEffect(search, cheapest, ingredientId) { visibleCount = 12 }

    SubpageScaffold(title = ingredientName, onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Vælg dit faste BilkaToGo-produkt", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sammenlign mærke, størrelse og pris. Vi gemmer kun det produkt, du vælger.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!busy && data != null) {
                item {
                    TextButton(onClick = { searchOtherBrand = !searchOtherBrand }) {
                        Text(if (searchOtherBrand) "Skjul varesøgning" else "Mangler dit mærke? Søg i BilkaToGo")
                    }
                }
                if (searchOtherBrand) item {
                    SectionCard("Find en anden vare") {
                        Text(
"Indtast et konkret produktnavn. En ny søgning bruger 1 Prej-kald, " +
                                "men et identisk gemt opslag bruger 0. " +
                                "Kun du vælger den endelige vare.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = moreQuery,
                            onValueChange = { moreQuery = it.take(80) },
                            label = { Text("Varenavn eller mærke") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                searchingMore = true
                                moreMessage = null
                                scope.launch {
                                    BilkaPrejClient.manualSearch(
                                        householdId, ingredientId, moreQuery, 1
                                    ).onSuccess { info ->
                                        lastQuery = moreQuery
                                        nextPage = if (info["hasMore"]?.toString() == "true") 2 else null
                                        val count = info["count"]?.toString()?.trim('"') ?: "0"
                                        val received = info["receivedCount"]?.toString()?.toIntOrNull()
                                        val withPrice = info["withToGoPrice"]?.toString()?.toIntOrNull()
                                        val total = info["providerTotal"]?.toString()?.toIntOrNull()
                                        val cached = info["fromCache"]?.toString() == "true"
                                        moreMessage = buildString {
                                            append("$count varer fra BilkaToGo. ")
                                            append(if (cached) "Gemt søgning – 0 API-kald." else "1 Prej-kald.")
                                            if (received != null && withPrice != null) {
                                                append(" $withPrice af $received har BilkaToGo-pris.")
                                                if (total != null && total > received)
                                                    append(" Flere varer kan hentes.")
                                            }
                                        }
                                        reload()
                                    }.onFailure { moreMessage = it.message }
                                    searchingMore = false
                                }
                            },
                            enabled = !searchingMore && moreQuery.trim().length >= 2,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (searchingMore) "Søger …" else "Søg i BilkaToGo") }
                        Text(
                            "Har du EAN-nummeret fra BilkaToGo? Et nyt nummer bruger " +
                                "1 Prej-kald, mens et allerede gemt EAN koster 0.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = eanText,
                            onValueChange = { eanText = it.filter(Char::isDigit).take(14) },
                            label = { Text("EAN / stregkode") },
                            supportingText = {
                                Text("8, 12, 13 eller 14 cifre. Søgningen kræver et præcist BilkaToGo-stregkodematch.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                searchingMore = true
                                moreMessage = null
                                scope.launch {
                                    BilkaPrejClient.lookupEan(
                                        householdId, ingredientId, eanText
                                    ).onSuccess { info ->
                                        val found = info["found"]?.toString() == "true"
                                        val cached = info["fromCache"]?.toString() == "true"
                                        val name = info["productName"]?.jsonPrimitive?.contentOrNull
                                        moreMessage = when {
                                            found ->
                                                "EAN fundet hos BilkaToGo – " +
                                                    (if (cached) "0 API-kald." else "1 API-kald.") +
                                                    " Kontrollér varen og vælg den selv."
                                            else ->
                                                "Intet sikkert BilkaToGo-match til denne stregkode. " +
                                                    (if (cached) "0 API-kald." else "1 API-kald.") +
                                                    " Prøv eventuelt at søge efter varenavn."
                                        }
                                        if (found) {
                                            reload()
                                            // Narrow the existing suggestions to the exact product;
                                            // do not select or save a product automatically.
                                            if (!name.isNullOrBlank()) search = name
                                        }
                                    }.onFailure { moreMessage = it.message }
                                    searchingMore = false
                                }
                            },
                            enabled = !searchingMore &&
                                eanText.length in listOf(8, 12, 13, 14),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (searchingMore) "Slår op …" else "Find vare via EAN")
                        }
                        moreMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        nextPage?.let { page ->
                            OutlinedButton(
                                enabled = !searchingMore,
                                onClick = {
                                    searchingMore = true
                                    scope.launch {
                                        BilkaPrejClient.manualSearch(
                                            householdId, ingredientId, lastQuery, page
                                        ).onSuccess { info ->
                                            val added = info["count"]?.toString() ?: "0"
                                            moreMessage = "Side $page: $added varer tilføjet til listen."
                                            nextPage = if (info["hasMore"]?.toString() == "true")
                                                page + 1 else null
                                            reload()
                                        }.onFailure { moreMessage = it.message }
                                        searchingMore = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Hent næste 100 varer – op til 1 kald") }
                        }
                    }
                }
            }
            if (busy) item { CircularProgressIndicator() }
            else if (error != null) {
                item {
                    EmptyCard(error ?: "Fejl")
                    OutlinedButton(onClick = { scope.launch { reload() } }) { Text("Prøv igen") }
                }
            } else if (options.isEmpty() && data?.productsFor(ingredientId).isNullOrEmpty()) {
                item {
                    EmptyCard("Ingen produktforslag til " + ingredientName + " endnu.")
                    Button(
                        onClick = { searchOtherBrand = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Søg selv efter Bilka-varer")
                    }
                }
            } else {
                item { SearchField(search, { search = it }, "Søg efter mærke eller produkt") }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !cheapest,
                            onClick = { cheapest = false },
                            label = { Text("Bedste match") }
                        )
                        FilterChip(
                            selected = cheapest,
                            onClick = { cheapest = true },
                            label = { Text("Laveste enhedspris") }
                        )
                    }
                }
                if (options.isEmpty()) item {
                    EmptyCard("Ingen varer passer til søgningen.")
                }
                item {
                    Text(
                        "Mest sandsynlige match først. Alle varer kan stadig ses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(options.take(visibleCount), key = { it.id }) { product ->
                    val chosen = selected == product.id
                    // Raw results without a ToGo price remain visible but
                    // cannot be chosen as a priced shopping SKU.
                    val selectable = (product.id in suggestions && product.priceOre != null) ||
                        product.id == currentId
                    Card(
                        onClick = { selected = product.id },
                        enabled = selectable,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (chosen)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BilkaProductPhoto(product)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    product.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    product.brandLabel + " · " + packageLabel(product) +
                                        (if (product.chainSlug == "bilkatogo") " · BilkaToGo" else " · Bilka") +
                                        (if (product.id == currentId) " · Fast valg" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    money(product.priceOre) +
                                        (unitLabel(product)?.let { "  ·  $it" } ?: "") +
                                        (if (product.isOffer == true) " · Tilbud" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (chosen) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Valgt")
                            }
                            if (!selectable) {
                                Text(
                                    "Ikke valgbart",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (chosen && product.id != currentId && product.id in suggestions) {
                            Button(
                                onClick = {
                                    saving = true
                                    saveError = null
                                    scope.launch {
                                        BilkaPrejClient.chooseProduct(
                                            householdId, ingredientId, product.id
                                        ).onSuccess { onSaved() }
                                            .onFailure {
                                                saveError = "Valget kunne ikke gemmes. Prøv igen."
                                            }
                                        saving = false
                                    }
                                },
                                enabled = !saving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            ) {
                                Text(if (saving) "Gemmer…" else "Gem som fast vare")
                            }
                            saveError?.let { message ->
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                if (options.size > visibleCount) {
                    item {
                        OutlinedButton(
                            onClick = { visibleCount = (visibleCount + 20).coerceAtMost(options.size) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Vis flere varer (${visibleCount.coerceAtMost(options.size)} af ${options.size})")
                        }
                    }
                }
                item {
                    Text(
                        "Dit faste valg deles i husstanden. Det skifter ikke automatisk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
