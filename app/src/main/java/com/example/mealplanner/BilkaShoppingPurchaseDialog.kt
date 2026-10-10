package com.example.mealplanner

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
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
    val product = catalog.product(standard.productId) ?: return null
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
    var alternativesOpen by remember(ingredient.id) { mutableStateOf(false) }
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
    // Estimated shelf price is informative, NOT an actual receipt amount.
    // Only an amount explicitly typed by the user is saved as paid_total_ore.
    val paidOre = if (paidText.isBlank()) {
        null
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
                    "Vælg kun hvad du købte; standardvaren ændres ikke.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (candidates.isNotEmpty()) {
                    val selectedName = if (manual) "Anden vare" else
                        currentProduct?.let(::productLabel) ?: "Vælg et produkt"
                    Text(
                        "Vare til dette køb",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { alternativesOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = alternativesOpen,
                            onDismissRequest = { alternativesOpen = false },
                            modifier = Modifier.heightIn(max = 290.dp)
                        ) {
                            candidates.forEach { product ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            productLabel(product),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        alternativesOpen = false
                                        manual = false
                                        selectedId = product.id
                                        val size = BilkaPackageMath.applicablePackBase(
                                            ingredient, product,
                                            if (product.id == defaultRow?.productId)
                                                defaultRow.packageBaseAmountOverride else null
                                        )
                                        packageCountText = (
                                            BilkaPackageMath.packageCount(
                                                item.requiredBase, item.pantryBase, size
                                            )?.coerceAtLeast(1) ?: 1
                                        ).toString()
                                        if (!priceEdited) paidText = ""
                                        error = null
                                    }
                                )
                            }
                        }
                    }
                }
                FilterChip(
                    selected = manual,
                    onClick = { manual = true; if (!priceEdited) paidText = "" },
                    label = { Text("Anden vare denne gang") }
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
                    OutlinedTextField(
                        value = packageCountText,
                        onValueChange = { packageCountText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Hele pakker") },
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
                        "Til Lager: " +
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
                    label = { Text("Betalt (kr.) – valgfrit") },
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
            ) { Text(if (saving) "Gemmer…" else "Læg på Lager") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving
            ) { Text("Annuller") }
        }
    )
}
