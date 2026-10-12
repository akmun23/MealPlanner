package com.example.mealplanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 1 contracts for the Bilka-only product catalog. Prices are integer øre,
 * and packageBaseAmount uses the same base units as Ingredient (g/ml/pieces/packs).
 * A product ID identifies a specific brand AND package size.
 *
 * Prej is accessed only by a future server-side integration, never by Android.
 */
@Serializable
enum class BilkaQuantityKind {
    MASS, VOLUME, COUNT, PACKAGE
}

@Serializable
enum class BilkaUnitPriceBasis {
    KG, L, PIECE, PACK
}



@Serializable
enum class BilkaDiscoveryStatus {
    PENDING, NO_MATCH,
}

@Serializable
data class BilkaProduct(
    val id: String,
    val name: String,
    val brand: String? = null,
    @SerialName("chain_slug") val chainSlug: String = "bilka",
    @SerialName("package_quantity") val packageQuantity: Double? = null,
    @SerialName("package_unit") val packageUnit: String? = null,
    @SerialName("package_base_amount") val packageBaseAmount: Double? = null,
    @SerialName("quantity_kind") val quantityKind: BilkaQuantityKind? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("price_ore") val priceOre: Long? = null,
    @SerialName("unit_price_ore") val unitPriceOre: Double? = null,
    @SerialName("unit_price_basis") val unitPriceBasis: BilkaUnitPriceBasis? = null,
    @SerialName("is_offer") val isOffer: Boolean? = null,
    @SerialName("is_available") val isAvailable: Boolean? = null,
    @SerialName("price_updated_at") val priceUpdatedAt: String? = null
) {
    val brandLabel: String
        get() = brand?.trim()?.takeIf { it.isNotEmpty() } ?: "Ukendt mærke"
}

@Serializable
data class IngredientBilkaDefault(
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("package_base_amount_override") val packageBaseAmountOverride: Double? = null
)

@Serializable
data class ShoppingProductOverride(
    @SerialName("household_id") val householdId: String,
    @SerialName("weekly_plan_id") val weeklyPlanId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("manual_product_name") val manualProductName: String? = null,
    @SerialName("package_count") val packageCount: Int? = null,
    @SerialName("purchased_amount_base") val purchasedAmountBase: Double? = null,
    @SerialName("paid_total_ore") val paidTotalOre: Long? = null
)

/**
 * The one-off shopping override only affects the active purchase.
 * It never updates the household's saved standard product.
 */
fun productIdForShopping(
    defaultProduct: IngredientBilkaDefault?,
    purchaseOverride: ShoppingProductOverride?
): String? = when {
    purchaseOverride?.productId != null -> purchaseOverride.productId
    purchaseOverride?.manualProductName != null -> null
    else -> defaultProduct?.productId
}

@Serializable
data class BilkaDiscoveryJob(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("search_query") val searchQuery: String? = null,
    val status: BilkaDiscoveryStatus = BilkaDiscoveryStatus.PENDING,
    val attempts: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null
)
