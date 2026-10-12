package com.example.mealplanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

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
internal fun decodeBilkaShoppingContext(raw: JsonObject): BilkaShoppingContext {
    val json = Json { ignoreUnknownKeys = true }
    return BilkaShoppingContext(
        defaults = json.decodeFromJsonElement(raw.getValue("defaults").jsonArray),
        purchases = json.decodeFromJsonElement(raw.getValue("overrides").jsonArray)
    )
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
