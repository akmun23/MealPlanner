package com.example.mealplanner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

private val da = Locale.forLanguageTag("da-DK")
private fun kroner(ore: Long): String =
    NumberFormat.getCurrencyInstance(da).format(ore.toDouble() / 100.0)

private fun productLabel(product: BilkaProduct): String =
    product.brandLabel + " · " + product.name +
        if (product.packageQuantity != null && product.packageUnit != null)
            " · " + product.packageQuantity + " " + product.packageUnit else ""

internal fun bilkaShoppingLine(
    item: ShoppingItem,
    context: BilkaShoppingContext?,
    catalog: BilkaProductCatalog?
): String? {
    if (context == null || catalog == null) return null
    val recorded = context.purchase(item.ingredient.id)
    if (recorded?.purchasedAmountBase != null) {
        return "Allerede lagt på Lager: " +
            (recorded.packageCount?.let { "$it pakker" }
                ?: "registreret køb") +
            (recorded.paidTotalOre?.let { " · Betalt: " + kroner(it) } ?: "")
    }
    val standard = context.default(item.ingredient.id) ?: return null
    val product = catalog.products.firstOrNull { it.id == standard.productId } ?: return null
    val amount = BilkaPackageMath.applicablePackBase(
        item.ingredient, product, standard.packageBaseAmountOverride
    )
    val packs = BilkaPackageMath.packageCount(item.requiredBase, item.pantryBase, amount)
    return when {
        packs == null -> "Bilka: " + product.brandLabel + " · pakkestørrelse skal bekræftes"
        packs == 0 -> "Bilka: nok på Lager · " + product.brandLabel
        else -> "Bilka: $packs × " + (product.packageQuantity?.let {
            formatDisplayNumber(it) + " " + (product.packageUnit ?: "")
        } ?: product.name) +
            (BilkaPackageMath.estimatedCostOre(product.priceOre, packs)?.let {
                " · ca. " + kroner(it)
            } ?: "")
    }
}

/**
 * One purchase of whole packs. Explicitly chosen alternatives belong ONLY to this
 * week, never to ingredient_bilka_defaults. The server performs an atomic
 * one-time stock credit so retry/double-tap cannot add the purchase twice.
 */
@Composable
internal fun BilkaPurchaseDialog(
    item: ShoppingItem,
    state: MealPlannerState,
    catalog: BilkaProductCatalog,
    shopping: BilkaShoppingContext,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ingredient = item.ingredient
    val defaultRow = shopping.default(ingredient.id)
    val previous = shopping.purchase(ingredient.id)
    val candidates = catalog.productsFor(ingredient.id)
        .filter { p ->
            p.quantityKind == null || p.quantityKind.name == ingredient.quantityType.name
        }
    val initial = previous?.productId ?: defaultRow?.productId
        ?: candidates.firstOrNull()?.id

    val scope = rememberCoroutineScope()
    var selectedId by remember(ingredient.id) { mutableStateOf(initial) }
    var manual by remember(ingredient.id) {
        mutableStateOf(initial == null || previous?.manualProductName != null)
    }
    var manualName by remember(ingredient.id) {
        mutableStateOf(previous?.manualProductName ?: "")
    }
    var units by remember(ingredient.id) { mutableStateOf(ingredient.preferredUnit) }
    val currentProduct = candidates.firstOrNull { it.id == selectedId }
    val packBase = currentProduct?.let { product ->
        BilkaPackageMath.applicablePackBase(
            ingredient, product,
            if (product.id == defaultRow?.productId)
                defaultRow.packageBaseAmountOverride else null
        )
    }
    val recommended = BilkaPackageMath.packageCount(
        item.requiredBase, item.pantryBase, packBase
    )?.coerceAtLeast(1) ?: 1
    var packageCountText by remember(ingredient.id) {
        mutableStateOf(recommended.toString())
    }
    var amountText by remember(ingredient.id) {
        mutableStateOf(formatDisplayNumber(
            ingredient.preferredUnit.fromBase(
                BilkaPackageMath.missing(item.requiredBase, item.pantryBase)
                    .coerceAtLeast(1.0)
            )
        ))
    }
    var paidText by remember(ingredient.id) { mutableStateOf("") }
    var priceEdited by remember(ingredient.id) { mutableStateOf(false) }
    var saving by remember(ingredient.id) { mutableStateOf(false) }
    var error by remember(ingredient.id) { mutableStateOf<String?>(null) }

    val count = packageCountText.toIntOrNull()
    val baseAmount = if (!manual && packBase != null && count != null)
        BilkaPackageMath.purchasedBase(packBase, count)
    else parseDanishDecimal(amountText)?.takeIf { it > 0.0 }?.let(units::toBase)
    val estimated = if (!manual && count != null)
        BilkaPackageMath.estimatedCostOre(currentProduct?.priceOre, count)
    else null
    val paidOre = if (paidText.isBlank()) {
        estimated
    } else {
        parseDanishDecimal(paidText)?.let {
            (it * 100).takeIf { n -> n.isFinite() && n >= 0 && n <= 1e9 }
                ?.roundToLong()
        }
    }
    val valid = baseAmount != null &&
        baseAmount <= 10_000_000.0 &&
        (if (manual) manualName.trim().length >= 2
         else currentProduct != null && count != null && count in 1..500) &&
        (paidText.isBlank() || paidOre != null)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Hvad købte du?") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    ingredient.name,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Vælg varen, som du købte denne gang. Husstandens faste Bilka-valg ændres ikke.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (candidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEach { product ->
                            FilterChip(
                                selected = !manual && selectedId == product.id,
                                onClick = {
                                    manual = false
                                    selectedId = product.id
                                    val size = BilkaPackageMath.applicablePackBase(
                                        ingredient, product,
                                        if (product.id == defaultRow?.productId)
                                            defaultRow.packageBaseAmountOverride else null
                                    )
                                    packageCountText = (BilkaPackageMath.packageCount(
                                        item.requiredBase, item.pantryBase, size
                                    )?.coerceAtLeast(1) ?: 1).toString()
                                    if (!priceEdited) paidText = ""
                                },
                                label = {
                                    Text(
                                        productLabel(product),
                                        maxLines = 2
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                FilterChip(
                    selected = manual,
                    onClick = { manual = true; if (!priceEdited) paidText = "" },
                    label = { Text("Anden vare (kun dette køb)") }
                )

                if (manual) {
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it.take(160) },
                        label = { Text("Hvilken vare købte du?") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    Text(
                        "Købte pakker",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = packageCountText,
                        onValueChange = { packageCountText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Antal hele pakker") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (manual || packBase == null) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }
                        },
                        label = { Text("Faktisk købt mængde") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    SimpleSelector(
                        label = units.selectorLabel(),
                        options = MeasurementUnit.compatibleWith(ingredient.quantityType),
                        optionText = { it.selectorLabel() },
                        onSelected = { new ->
                            val amount = parseDanishDecimal(amountText)
                            val originalBase = amount?.let(units::toBase)
                            units = new
                            if (originalBase != null) amountText =
                                formatDisplayNumber(new.fromBase(originalBase))
                        }
                    )
                }
                if (baseAmount != null) {
                    Text(
                        "Tilføjes til Lager: " +
                            state.formatBaseAmount(ingredient, baseAmount),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                estimated?.let { Text("Forventet pris: " + kroner(it)) }
                OutlinedTextField(
                    value = paidText,
                    onValueChange = {
                        paidText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }
                        priceEdited = true
                    },
                    label = { Text("Faktisk betalt i kr. (valgfrit)") },
                    placeholder = { Text("F.eks. 24,95") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !saving,
                onClick = {
                    val amount = baseAmount ?: return@Button
                    val weekId = state.weeklyPlanId ?: return@Button
                    saving = true
                    scope.launch {
                        BilkaPrejClient.recordPurchase(
                            householdId = state.householdIdentifier,
                            weekId = weekId,
                            ingredientId = ingredient.id,
                            productId = if (manual) null else selectedId,
                            manualProductName = if (manual) manualName.trim() else null,
                            packageCount = if (manual) null else count,
                            amountBase = amount,
                            paidTotalOre = paidOre
                        ).onSuccess {
                            state.refreshNow()
                            onSaved()
                        }.onFailure { error = it.message ?: "Købet kunne ikke gemmes." }
                        saving = false
                    }
                }
            ) { Text(if (saving) "Gemmer…" else "Gem køb og tilføj til Lager") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving
            ) { Text("Annuller") }
        }
    )
}
