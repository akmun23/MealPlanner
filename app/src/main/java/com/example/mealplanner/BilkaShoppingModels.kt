package com.example.mealplanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class BilkaShoppingDefault(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("package_base_amount_override") val packageBaseAmountOverride: Double? = null
)
@Serializable
internal data class BilkaShoppingPurchase(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("manual_product_name") val manualProductName: String? = null,
    @SerialName("package_count") val packageCount: Int? = null,
    @SerialName("purchased_amount_base") val purchasedAmountBase: Double? = null,
    @SerialName("paid_total_ore") val paidTotalOre: Long? = null
)
internal data class BilkaShoppingContext(
    val defaults: List<BilkaShoppingDefault>,
    val purchases: List<BilkaShoppingPurchase>
) {
    fun default(ingredientId: String): BilkaShoppingDefault? =
        defaults.firstOrNull { it.ingredientId == ingredientId }

    fun purchase(ingredientId: String): BilkaShoppingPurchase? =
        purchases.firstOrNull { it.ingredientId == ingredientId }
}
private fun JsonObject.field(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.decimal(name: String): Double? =
    field(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun decodeBilkaShoppingContext(raw: JsonObject): BilkaShoppingContext {
    val defaultsRaw = raw["defaults"] as? JsonArray
        ?: throw IllegalArgumentException("Bilka purchase context missing defaults")
    val overridesRaw = raw["overrides"] as? JsonArray
        ?: throw IllegalArgumentException("Bilka purchase context missing overrides")
    val defaults = defaultsRaw.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val ingredientId = row.field("ingredient_id")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val productId = row.field("product_id")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        BilkaShoppingDefault(
            ingredientId = ingredientId,
            productId = productId,
            packageBaseAmountOverride = row.decimal("package_base_amount_override")
        )
    }
    val purchases = overridesRaw.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val ingredientId = row.field("ingredient_id")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        BilkaShoppingPurchase(
            ingredientId = ingredientId,
            productId = row.field("product_id"),
            manualProductName = row.field("manual_product_name"),
            packageCount = row.field("package_count")?.toIntOrNull(),
            purchasedAmountBase = row.decimal("purchased_amount_base"),
            paidTotalOre = row.field("paid_total_ore")?.toLongOrNull()
        )
    }
    return BilkaShoppingContext(defaults, purchases)
}

/** Preserve a successful catalogue or weekly context when the other fails. */
internal data class BilkaShoppingLoad(
    val catalog: BilkaProductCatalog?,
    val context: BilkaShoppingContext?,
    val hasError: Boolean
)

internal fun decodeBilkaShoppingLoad(
    catalogResponse: Result<JsonObject>,
    contextResponse: Result<JsonObject>
): BilkaShoppingLoad {
    val catalog = catalogResponse.mapCatching(::parseBilkaCatalog)
    val context = contextResponse.mapCatching(::decodeBilkaShoppingContext)
    return BilkaShoppingLoad(
        catalog = catalog.getOrNull(),
        context = context.getOrNull(),
        hasError = catalog.isFailure || context.isFailure
    )
}
