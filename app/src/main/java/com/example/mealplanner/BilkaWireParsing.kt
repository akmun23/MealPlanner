package com.example.mealplanner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Explicit decoding for Bilka's public JSON responses. Do not use generated
 * kotlinx serializers for the Android Bilka wire format: the release build
 * uses R8, and a missing/unknown provider field must never blank the screen.
 *
 * The server speaks camelCase in discovery-status and snake_case in catalog.
 * This parser deliberately accepts both for stable compatibility.
 */
private fun JsonObject.primitive(vararg names: String): JsonPrimitive? =
    names.firstNotNullOfOrNull { this[it] as? JsonPrimitive }

private fun JsonObject.string(vararg names: String): String? =
    primitive(*names)?.contentOrNull

private fun JsonObject.nonBlank(vararg names: String): String? =
    string(*names)?.takeIf { it.isNotBlank() }

private fun JsonObject.double(vararg names: String): Double? =
    primitive(*names)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun JsonObject.long(vararg names: String): Long? =
    primitive(*names)?.contentOrNull?.toLongOrNull()

private fun JsonObject.int(vararg names: String): Int? =
    primitive(*names)?.contentOrNull?.toIntOrNull()

private fun JsonObject.boolean(vararg names: String): Boolean? =
    when (string(*names)) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun <T> parseRows(
    raw: JsonObject,
    key: String,
    parse: (JsonObject) -> T?
): BilkaParsedRows<T> {
    val source = raw[key] as? JsonArray
        ?: throw IllegalArgumentException("Missing or invalid Bilka response array: $key")
    val rows = source.mapNotNull { element ->
        (element as? JsonObject)?.let(parse)
    }
    return BilkaParsedRows(rows, source.size - rows.size)
}

internal data class BilkaParsedRows<T>(
    val rows: List<T>,
    val rejected: Int
)

internal fun parseBilkaIngredientRows(raw: JsonObject): BilkaParsedRows<BilkaListIngredient> =
    parseRows(raw, "items") { row ->
        val id = row.nonBlank("ingredientId", "ingredient_id") ?: return@parseRows null
        val name = row.nonBlank("name") ?: return@parseRows null
        BilkaListIngredient(
            ingredientId = id,
            name = name,
            state = row.nonBlank("state") ?: "PENDING",
            candidateCount = (row.int("candidateCount", "candidate_count") ?: 0).coerceAtLeast(0)
        )
    }

internal fun parseBilkaIngredients(raw: JsonObject): List<BilkaListIngredient> =
    parseBilkaIngredientRows(raw).rows

internal fun parseBilkaCatalog(raw: JsonObject): BilkaProductCatalog {
    val candidates = parseRows(raw, "candidates") { row ->
        val ingredientId = row.nonBlank("ingredient_id", "ingredientId")
            ?: return@parseRows null
        val productId = row.nonBlank("product_id", "productId")
            ?: return@parseRows null
        BilkaListCandidate(
            ingredientId = ingredientId,
            productId = productId,
            displayRank = row.int("display_rank", "displayRank") ?: 0
        )
    }
    val defaults = parseRows(raw, "defaults") { row ->
        val ingredientId = row.nonBlank("ingredient_id", "ingredientId")
            ?: return@parseRows null
        val productId = row.nonBlank("product_id", "productId")
            ?: return@parseRows null
        BilkaListDefault(ingredientId = ingredientId, productId = productId)
    }
    val products = parseRows(raw, "products") { row ->
        val id = row.nonBlank("id") ?: return@parseRows null
        val name = row.nonBlank("name") ?: return@parseRows null
        val kind = row.string("quantity_kind", "quantityKind")?.let { value ->
            BilkaQuantityKind.entries.firstOrNull { it.name == value }
        }
        val basis = row.string("unit_price_basis", "unitPriceBasis")?.let { value ->
            BilkaUnitPriceBasis.entries.firstOrNull { it.name == value }
        }
        BilkaProduct(
            id = id,
            name = name,
            brand = row.string("brand"),
            chainSlug = row.nonBlank("chain_slug", "chainSlug") ?: "bilka",
            packageQuantity = row.double("package_quantity", "packageQuantity"),
            packageUnit = row.string("package_unit", "packageUnit"),
            packageBaseAmount = row.double("package_base_amount", "packageBaseAmount"),
            quantityKind = kind,
            imageUrl = row.string("image_url", "imageUrl"),
            priceOre = row.long("price_ore", "priceOre"),
            unitPriceOre = row.double("unit_price_ore", "unitPriceOre"),
            unitPriceBasis = basis,
            isOffer = row.boolean("is_offer", "isOffer"),
            isAvailable = row.boolean("is_available", "isAvailable"),
            priceUpdatedAt = row.string("price_updated_at", "priceUpdatedAt")
        )
    }
    return BilkaProductCatalog(
        candidates = candidates.rows,
        defaults = defaults.rows,
        products = products.rows,
        parseWarnings = buildList {
            if (candidates.rejected > 0) add("${candidates.rejected} produktforslag")
            if (defaults.rejected > 0) add("${defaults.rejected} faste varevalg")
            if (products.rejected > 0) add("${products.rejected} varer")
        }
    )
}
